import os, re

root = r"D:\AI\claw_repair\docs\qnn-api\pages"
hits = []
for dp, dn, fn in os.walk(root):
    for f in fn:
        if not f.endswith((".md", ".txt")):
            continue
        p = os.path.join(dp, f)
        try:
            txt = open(p, encoding="utf-8", errors="replace").read()
        except Exception:
            continue
        if any(k in txt for k in ["QnnProfile_create", "QnnProfile_getEvents",
                                  "QnnProfile_createFromOpPackage",
                                  "QNN_PROFILE", "profileCreate",
                                  "per-event", "Per-Operator"]):
            # 提取相关行
            lines = txt.splitlines()
            for i, ln in enumerate(lines):
                if re.search(r"QnnProfile|profile event|per-event|profileCreate", ln, re.I):
                    hits.append((p.replace(root, ""), i + 1, ln.strip()[:160]))
print("total hits:", len(hits))
for h in hits[:40]:
    print(h[0], ":", h[1], ":", h[2])
