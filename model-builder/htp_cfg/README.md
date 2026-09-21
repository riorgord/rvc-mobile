# htp_cfg

HTP 编译配置（qnn-context-binary-generator 用）。每个组件一对文件：

- `<name>.htp.json`：htp 配置（图名必须 = DLC 图名，否则 vtcm/O3 静默失效）
- `<name>.backend.json`：backend_extensions 包装（顶层必须是 `backend_extensions`，直接传 graphs/devices 会 Unknown Key 静默失效）

## 已验证组合（K50 Ultra / SM8475 / V69，FP32）

| 组件 | 图名 | O | vtcm | hvx | 说明 |
|---|---|---|---|---|---|
| z_producer | z_producer | 0 | 4MB | 0 | 与 App 现网 bin 真机逐字节一致 |
| dec_short_T61 | dec_short_T61 | 3 | 8MB | 4 | 与 App 现网 bin 真机 cos>0.999 |

## 纪律

- 图名 = dlc 文件名 = htp 配置 graph name，三者必须一致。
- 只准 FP32 compute（V69 fp16 卷积系统性 NaN）。
- 换机型改 `--arch`/soc_id 时，配置里的 dsp_arch/soc_id 要一起对（sm8475=v69, soc_id=42）。
