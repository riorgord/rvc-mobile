import numpy as np, sys, faiss
sys.path.insert(0, r"D:\AI\claw_repair\research\rvc\rvc_app\android\app\src\main\python")
from rvc_periphery import index_mix
PH = r"D:\AI\claw_repair\research\rvc\mobile_prep\qnn\phone_pipe"
PER = r"D:\AI\claw_repair\research\rvc\rvc_app\android\app\src\main\assets\periphery"

idx = faiss.read_index(r"E:\BaiduNetdiskDownload\RVC-beta\RVC20240604Nvidia\RVC20240604Nvidia\logs\naiqiawang.index")
idx_vecs = idx.reconstruct_n(0, idx.ntotal).astype(np.float32)
print("idx", idx_vecs.shape)
# 用 gya hubert 特征(112帧)当 query
hf = [np.fromfile(f"{PH}/pull_hf{k}.raw", np.float32).reshape(14, 768) for k in range(8)]
feats = np.concatenate(hf, 0)[:112].astype(np.float32)
print("feats", feats.shape)

rate = 0.75
# faiss 参考(RVC pipeline 逻辑)
score, ix = idx.search(feats, 8)
w = np.square(1.0 / score)
w /= w.sum(axis=1, keepdims=True)
npy_faiss = np.sum(idx_vecs[ix] * np.expand_dims(w, axis=2), axis=1)
ref = feats * rate + npy_faiss * (1 - rate)

# numpy index_mix
mine = index_mix(feats, idx_vecs, rate, k=8)
print("index_mix vs faiss corr=%.6f maxerr=%.6f" % (
    np.corrcoef(ref.ravel(), mine.ravel())[0, 1],
    np.abs(ref.ravel() - mine.ravel()).max()))
