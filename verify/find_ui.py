import subprocess, re, sys

xml = subprocess.run(['adb', 'shell', 'cat', '/sdcard/ui2.xml'],
                     capture_output=True).stdout.decode('utf-8', 'ignore')
for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="(\[[^"]*\])"', xml):
    t = m.group(1).strip()
    if t:
        b = m.group(2)
        nums = [int(x) for x in re.findall(r'\d+', b)]
        cx = (nums[0] + nums[2]) // 2
        cy = (nums[1] + nums[3]) // 2
        print("%s -> %d,%d" % (t, cx, cy))
