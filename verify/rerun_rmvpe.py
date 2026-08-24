import subprocess

cmd = ("export LD_LIBRARY_PATH=/data/data/com.termux/files/home/rvc/lib:/vendor/dsp/cdsp:/vendor/lib64; "
       "export ADSP_LIBRARY_PATH=/data/data/com.termux/files/home/rvc/lib/hexagon-v69; "
       "printf '%s\\n' /data/local/tmp/ph_chain2/gya_mel_t.bin > /data/local/tmp/ph_chain2/il_gya_rv2.txt; "
       "rm -rf /data/local/tmp/ph_chain2/out_gya_rv2; "
       "/data/data/com.termux/files/home/rvc/bin/qnn-net-run "
       "--retrieve_context /data/data/com.termux/files/home/rvc/models/rmvpe_fp32_256.bin "
       "--backend /data/data/com.termux/files/home/rvc/lib/libQnnHtp.so "
       "--input_list /data/local/tmp/ph_chain2/il_gya_rv2.txt "
       "--output_dir /data/local/tmp/ph_chain2/out_gya_rv2 2>&1 | tail -3")
r = subprocess.run(['adb', 'shell', 'su', '-c', cmd], capture_output=True, text=True, timeout=120)
print("STDOUT:", r.stdout)
print("STDERR:", r.stderr)
