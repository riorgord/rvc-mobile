import os, subprocess

# apk_source 完整树 (cpp + python + 根)
print("=== apk_source/gsv-app 完整树 (2层) ===")
root = r"D:\AI\claw_repair\archive\gsv\apk_source\gsv-app"
for dp, dn, fn in os.walk(root):
    depth = dp.replace(root, "").count(os.sep)
    if depth <= 2:
        for f in fn:
            print(" ", os.path.join(dp, f).replace(root, ""))
        for d in dn:
            pass

# 搜 qnn_tts.py / gsv python 源码 (PC)
print("\n=== 搜 qnn_tts.py / gsv_ort.py / gsv python ===")
for dp, dn, fn in os.walk(r"D:\AI\claw_repair"):
    if any(x in dp for x in ["node_modules", ".git", "qwen3tts"]):
        continue
    for f in fn:
        if f in ("qnn_tts.py", "gsv_ort.py", "qnn_stage_svc.py", "gsv_qnn_service.c"):
            print(" ", os.path.join(dp, f))
