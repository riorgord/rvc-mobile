import sys, os, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

p = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\qnn-profiling-data_0.log"
raw = open(p, "rb").read()
# 尝试多种解码
text = None
for enc in ("utf-8", "latin-1"):
    try:
        text = raw.decode(enc)
        break
    except Exception:
        continue
if text is None:
    text = raw.decode("utf-8", "replace")
print(text)
