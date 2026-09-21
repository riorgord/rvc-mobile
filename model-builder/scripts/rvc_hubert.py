"""Faithful PyTorch replica of the fairseq HuBERT base used by RVC v2.

Source of truth: `assets/hubert/hubert_base.pt` (fairseq checkpoint, extractor_mode="default")
+ the deployed `models/hubert.onnx` graph (verified structure).
Copied from research/rvc/mobile_prep/stream/rvc_hubert.py (已验证).
"""
from __future__ import annotations

import sys
import types

import torch
import torch.nn as nn
import torch.nn.functional as F


def load_fairseq_hubert_checkpoint(path: str) -> dict:
    class DummyCls:
        def __init__(self, *a, **k):
            pass

        def __setstate__(self, st):
            pass

    class FakeMod(types.ModuleType):
        def __getattr__(self, name):
            return DummyCls

    for name in [
        "fairseq",
        "fairseq.data",
        "fairseq.data.dictionary",
        "fairseq.models",
        "fairseq.models.hubert",
        "fairseq.models.wav2vec",
        "fairseq.models.wav2vec.wav2vec2",
        "fairseq.modules",
        "fairseq.modules.transformer_sentence_encoder",
        "fairseq.modules.quant_noise",
        "fairseq.modules.gumbel_vector_quantizer",
    ]:
        sys.modules.setdefault(name, FakeMod(name))

    ck = torch.load(path, map_location="cpu", weights_only=False)
    return ck["model"]


class ConvExtractBlock(nn.Module):
    def __init__(self, in_d, out_d, k, s, first=False):
        super().__init__()
        self.conv = nn.Conv1d(in_d, out_d, k, stride=s, bias=False)
        self.first = first
        if first:
            self.instnorm = nn.InstanceNorm1d(out_d, eps=1e-5, affine=True,
                                              track_running_stats=False)

    def forward(self, x):
        x = self.conv(x)
        if self.first:
            x = self.instnorm(x)
        x = F.gelu(x)
        return x


class PosConvEmbed(nn.Module):
    def __init__(self, d=768, k=128, groups=16, pos_mode="symmetric"):
        super().__init__()
        self.d = d
        self.k = k
        self.groups = groups
        self.conv = nn.Conv1d(d, d, k, groups=groups, bias=True)
        self.pos_mode = pos_mode

    def _pad_tuple(self):
        if self.pos_mode == "causal":
            return (self.k - 1, 0)
        return (self.k // 2, self.k // 2 - 1)

    def forward(self, x):
        x = x.transpose(1, 2)
        pad_l, pad_r = self._pad_tuple()
        x = F.pad(x, (pad_l, pad_r))
        x = self.conv(x)
        x = x.transpose(1, 2)
        x = F.gelu(x)
        return x

    def _set_effective_weight(self, wg, wv, bias):
        v = wv.float()
        g = wg.float()
        norm = v.norm(2, dim=(0, 1), keepdim=True) + 1e-12
        w = g * (v / norm)
        self.conv.weight.data.copy_(w)
        if bias is not None:
            self.conv.bias.data.copy_(bias.float())


class MultiHeadSelfAttn(nn.Module):
    def __init__(self, d=768, heads=12, bias=True):
        super().__init__()
        self.d = d
        self.heads = heads
        self.hd = d // heads
        self.q = nn.Linear(d, d, bias=bias)
        self.k = nn.Linear(d, d, bias=bias)
        self.v = nn.Linear(d, d, bias=bias)
        self.out = nn.Linear(d, d, bias=bias)
        self.scaling = self.hd ** -0.5

    def forward(self, x, w_back=None, w_fwd=None):
        B, T, C = x.shape
        H, hd = self.heads, self.hd
        q = self.q(x).view(B, T, H, hd).transpose(1, 2)
        k = self.k(x).view(B, T, H, hd).transpose(1, 2)
        v = self.v(x).view(B, T, H, hd).transpose(1, 2)
        attn = torch.matmul(q, k.transpose(-2, -1)) * self.scaling
        if w_back is not None or w_fwd is not None:
            idx = torch.arange(T, device=x.device)
            rel = idx[None, :] - idx[:, None]
            allowed = torch.ones_like(rel, dtype=torch.bool)
            if w_back is not None:
                allowed &= rel <= w_back
            if w_fwd is not None:
                allowed &= rel >= -w_fwd
            mask = torch.zeros_like(attn)
            mask.masked_fill_(~allowed[None, None], float("-inf"))
            attn = attn + mask
        attn = F.softmax(attn, dim=-1)
        out = torch.matmul(attn, v)
        out = out.transpose(1, 2).reshape(B, T, C)
        return self.out(out)


class EncoderLayer(nn.Module):
    def __init__(self, d=768, ff=3072, heads=12):
        super().__init__()
        self.attn = MultiHeadSelfAttn(d, heads)
        self.attn_ln = nn.LayerNorm(d, eps=1e-5)
        self.fc1 = nn.Linear(d, ff)
        self.fc2 = nn.Linear(ff, d)
        self.final_ln = nn.LayerNorm(d, eps=1e-5)

    def forward(self, x, w_back=None, w_fwd=None):
        r = x
        x = r + self.attn(x, w_back, w_fwd)
        x = self.attn_ln(x)
        r = x
        x = r + self.fc2(F.gelu(self.fc1(x)))
        x = self.final_ln(x)
        return x


class RvcHubert(nn.Module):
    def __init__(self, pos_mode="symmetric"):
        super().__init__()
        self.pos_mode = pos_mode
        self.conv_blocks = nn.ModuleList(
            [
                ConvExtractBlock(1, 512, 10, 5, first=True),
                ConvExtractBlock(512, 512, 3, 2),
                ConvExtractBlock(512, 512, 3, 2),
                ConvExtractBlock(512, 512, 3, 2),
                ConvExtractBlock(512, 512, 3, 2),
                ConvExtractBlock(512, 512, 2, 2),
                ConvExtractBlock(512, 512, 2, 2),
            ]
        )
        self.feat_ln = nn.LayerNorm(512, eps=1e-5)
        self.feat_proj = nn.Linear(512, 768)
        self.pos_conv = PosConvEmbed(768, 128, 16, pos_mode=pos_mode)
        self.enc_ln = nn.LayerNorm(768, eps=1e-5)
        self.layers = nn.ModuleList([EncoderLayer() for _ in range(12)])

    def load_fairseq_sd(self, sd: dict):
        for i, blk in enumerate(self.conv_blocks):
            blk.conv.weight.data.copy_(sd[f"feature_extractor.conv_layers.{i}.0.weight"].float())
            if blk.first:
                blk.instnorm.weight.data.copy_(sd[f"feature_extractor.conv_layers.{i}.2.weight"].float())
                blk.instnorm.bias.data.copy_(sd[f"feature_extractor.conv_layers.{i}.2.bias"].float())
        self.feat_ln.weight.data.copy_(sd["layer_norm.weight"].float())
        self.feat_ln.bias.data.copy_(sd["layer_norm.bias"].float())
        self.feat_proj.weight.data.copy_(sd["post_extract_proj.weight"].float())
        self.feat_proj.bias.data.copy_(sd["post_extract_proj.bias"].float())
        self.pos_conv._set_effective_weight(
            sd["encoder.pos_conv.0.weight_g"],
            sd["encoder.pos_conv.0.weight_v"],
            sd["encoder.pos_conv.0.bias"],
        )
        self.enc_ln.weight.data.copy_(sd["encoder.layer_norm.weight"].float())
        self.enc_ln.bias.data.copy_(sd["encoder.layer_norm.bias"].float())
        for i, layer in enumerate(self.layers):
            p = f"encoder.layers.{i}."
            a = layer.attn
            a.q.weight.data.copy_(sd[p + "self_attn.q_proj.weight"].float())
            a.q.bias.data.copy_(sd[p + "self_attn.q_proj.bias"].float())
            a.k.weight.data.copy_(sd[p + "self_attn.k_proj.weight"].float())
            a.k.bias.data.copy_(sd[p + "self_attn.k_proj.bias"].float())
            a.v.weight.data.copy_(sd[p + "self_attn.v_proj.weight"].float())
            a.v.bias.data.copy_(sd[p + "self_attn.v_proj.bias"].float())
            a.out.weight.data.copy_(sd[p + "self_attn.out_proj.weight"].float())
            a.out.bias.data.copy_(sd[p + "self_attn.out_proj.bias"].float())
            layer.attn_ln.weight.data.copy_(sd[p + "self_attn_layer_norm.weight"].float())
            layer.attn_ln.bias.data.copy_(sd[p + "self_attn_layer_norm.bias"].float())
            layer.fc1.weight.data.copy_(sd[p + "fc1.weight"].float())
            layer.fc1.bias.data.copy_(sd[p + "fc1.bias"].float())
            layer.fc2.weight.data.copy_(sd[p + "fc2.weight"].float())
            layer.fc2.bias.data.copy_(sd[p + "fc2.bias"].float())
            layer.final_ln.weight.data.copy_(sd[p + "final_layer_norm.weight"].float())
            layer.final_ln.bias.data.copy_(sd[p + "final_layer_norm.bias"].float())

    @classmethod
    def from_fairseq(cls, path: str, pos_mode="symmetric"):
        sd = load_fairseq_hubert_checkpoint(path)
        model = cls(pos_mode=pos_mode)
        model.load_fairseq_sd(sd)
        model.eval()
        return model

    def forward(self, wav, w_back=None, w_fwd=None, output_layer=12):
        x = wav[:, None]
        for blk in self.conv_blocks:
            x = blk(x)
        x = x.transpose(1, 2)
        x = self.feat_ln(x)
        x = self.feat_proj(x)
        pos = self.pos_conv(x)
        x = x + pos
        x = self.enc_ln(x)
        for i, layer in enumerate(self.layers):
            x = layer(x, w_back, w_fwd)
            if i + 1 == output_layer:
                break
        return x

    def n_frames(self, n_samples):
        return n_samples // 320
