import re, sys

p = r"D:\AI\claw_repair\archive\gsv\apk_source\gsv-app\app\src\main\jniLibs\arm64-v8a\libgsv_qnn.so"
data = open(p, "rb").read()
print("lib size:", len(data))
# 提取 ASCII 字符串
strs = re.findall(rb"[\x20-\x7e]{6,}", data)
texts = [s.decode() for s in strs]

# gsv 导出符号
gsv_sym = [t for t in texts if t.startswith("gsv_")]
print("\n=== gsv_* 符号 ===")
for t in sorted(set(gsv_sym)):
    print(" ", t)

# profile / perf 相关
print("\n=== profile/perf 相关字符串 ===")
for t in sorted(set(texts)):
    tl = t.lower()
    if ("profile" in tl or "perf" in tl or "event" in tl) and len(t) < 80:
        print(" ", t)

# QnnProfile API 符号
print("\n=== QnnProfile API 符号引用 ===")
for t in sorted(set(texts)):
    if "Profile" in t and "Qnn" in t:
        print(" ", t)
