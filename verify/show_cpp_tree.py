import os

# apk_source cpp 目录完整
root = r"D:\AI\claw_repair\archive\gsv\apk_source\gsv-app\app\src\main"
print("=== app/src/main 完整树 (3层) ===")
for dp, dn, fn in os.walk(root):
    depth = dp.replace(root, "").count(os.sep)
    if depth <= 3:
        for f in fn:
            rel = os.path.join(dp, f).replace(root, "")
            if not any(x in rel for x in ["jniLibs", ".gradle", "res/", "assets/"]):
                print(" ", rel)
