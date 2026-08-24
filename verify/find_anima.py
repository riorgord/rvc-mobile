import os

print("=== PC 搜 anima 目录/源码 ===")
roots = [r"D:\AI\claw_repair", r"D:\AI"]
seen = set()
for root in roots:
    if not os.path.exists(root):
        continue
    for dp, dn, fn in os.walk(root):
        if any(x in dp for x in ["node_modules", ".git", "qwen3tts"]):
            continue
        base = os.path.basename(dp).lower()
        if "anima" in base:
            print("[dir]", dp)
        for f in fn:
            fl = f.lower()
            if "anima" in fl or fl in ("gsv_qnn_service.c",):
                p = os.path.join(dp, f)
                if p not in seen:
                    seen.add(p)
                    print(" ", p)
print("done")
