package com.rvc.app

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RVC 手机实时变声 M0:unsigned PD 激活 + gen_fp32 单模型自检。
 * - 启动解压 assets/models + assets/hexagon-v69 + assets/testdata → filesDir
 * - profiling 开关 → 传 rvc_api,输出到 /sdcard/rvc_exp
 * - "跑 gen 自检" 按钮:Python rvc_api.self_test → 对比 PC 参考 corr/SNR
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var profSwitch: Switch
    private lateinit var keyInput: EditText
    private lateinit var rmsInput: EditText
    private lateinit var idxInput: EditText
    private lateinit var protInput: EditText
    private lateinit var budgetInput: EditText
    private lateinit var latencyView: TextView
    private lateinit var f0Spinner: Spinner
    private lateinit var f0Progress: ProgressBar
    private lateinit var brightnessSeek: SeekBar
    private lateinit var brightnessVal: TextView
    private val f0Loaded = HashMap<String, Boolean>()
    private val f0Loading = HashMap<String, Boolean>()
    @Volatile private var preloadDone = false
    @Volatile private var streamRunning = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("rvc_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val scroll = ScrollView(this)
        logView = TextView(this).apply { textSize = 12f }
        scroll.addView(logView)

        profSwitch = Switch(this).apply {
            text = "profiling → /sdcard/rvc_exp"
        }
        profSwitch.isChecked = intent.getBooleanExtra("profile", false)
        val runBtn = Button(this).apply {
            text = "跑 gen 自检"
            setOnClickListener { runGen() }
        }
        val runAllBtn = Button(this).apply {
            text = "3模型全检"
            setOnClickListener { runAll() }
        }
        val runFullBtn = Button(this).apply {
            text = "全链路"
            setOnClickListener { runFull() }
        }
        val liveBtn = Button(this).apply {
            text = "实时"
            setOnClickListener { runLive() }
        }
        val route2Btn = Button(this).apply {
            text = "②拆分"
            setOnClickListener { runRoute2() }
        }
        val istftBtn = Button(this).apply {
            text = "iSTFT拆分"
            setOnClickListener { runRoute2Istft() }
        }
        val simBtn = Button(this).apply {
            text = "模拟"
            setOnClickListener { runSim() }
        }
        val streamBtn = Button(this).apply {
            text = "实时流式"
            setOnClickListener {
                if (streamRunning) {
                    streamRunning = false
                    log("停止实时流式")
                } else {
                    runStreamLive()
                }
            }
        }
        val testLatBtn = Button(this).apply {
            text = "测延迟"
            setOnClickListener { runLatencyTest() }
        }
        budgetInput = EditText(this).apply {
            setText(prefs.getString("latency_budget_ms", "370"))
            hint = "延迟预算(ms)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(16, 0, 16, 0)
        }
        latencyView = TextView(this).apply {
            text = "实测延迟: ${prefs.getString("latency_measured_ms", "未测")} ms"
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        // 开关单独一行;按钮一行均分(防挤出屏幕)
        val rowSwitch = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(profSwitch)
        }
        keyInput = EditText(this).apply {
            setText("0")
            hint = "f0up_key 变调(半音)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(16, 0, 16, 0)
        }
        rmsInput = EditText(this).apply {
            setText("0.25")
            hint = "rms_mix_rate"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(16, 0, 16, 0)
        }
        idxInput = EditText(this).apply {
            setText("0.5")
            hint = "index_rate"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(16, 0, 16, 0)
        }
        protInput = EditText(this).apply {
            setText("0.4")
            hint = "protect 0-0.5"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(16, 0, 16, 0)
        }
        f0Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_item,
                arrayOf("fcpe", "rmvpe", "gf_ref")).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    ensureF0Loaded(p?.getItemAtPosition(pos)?.toString() ?: "fcpe")
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        f0Progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        fun paramCell(label: String, edit: EditText): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 12f
                })
                addView(edit)
            }
        }
        val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val rowParams1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(paramCell("f0up_key 变调(半音)", keyInput), w)
            addView(paramCell("rms_mix_rate", rmsInput), w)
        }
        val rowParams2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(paramCell("index_rate 音色(0-1)", idxInput), w)
            addView(paramCell("protect 清音(0-0.5)", protInput), w)
        }
        val rowF0 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = "F0 提取"
                textSize = 12f
                setPadding(0, 8, 8, 0)
            }, w)
            addView(f0Spinner, w)
        }
        brightnessVal = TextView(this).apply {
            text = "亮度 0"
            textSize = 12f
            setPadding(0, 8, 8, 0)
        }
        brightnessSeek = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    brightnessVal.text = "亮度 $p"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        val rowBright = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(brightnessVal, w)
            addView(brightnessSeek, w)
        }
        val rowBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(runBtn, w)
            addView(runAllBtn, w)
            addView(runFullBtn, w)
        }
        val rowBtns2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(liveBtn, w)
            addView(route2Btn, w)
            addView(istftBtn, w)
            addView(simBtn, w)
        }
        val rowLatency = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(latencyView, w)
            addView(testLatBtn, w)
        }
        val rowStream = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(paramCell("延迟预算(ms) 实时需<=370", budgetInput), w)
            addView(streamBtn, w)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(rowSwitch)
            addView(rowBtns)
            addView(rowBtns2)
            addView(rowParams1)
            addView(rowParams2)
            addView(rowF0)
            addView(rowBright)
            addView(rowLatency)
            addView(rowStream)
            addView(f0Progress)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        log("RVC App M0")
        log("uid=" + android.os.Process.myUid())
        log("filesDir=" + filesDir.absolutePath)
        // 解压 assets → filesDir(幂等)
        extract("models", File(filesDir, "models"))
        extract("hexagon-v69", File(filesDir, "hexagon-v69"))
        extract("testdata", File(filesDir, "testdata"))
        extract("periphery", File(filesDir, "periphery"))
        log("assets 解压 OK: models %d / hexagon %d / testdata %d / periphery %d".format(
            File(filesDir, "models").listFiles()?.size ?: 0,
            File(filesDir, "hexagon-v69").listFiles()?.size ?: 0,
            File(filesDir, "testdata").listFiles()?.size ?: 0,
            File(filesDir, "periphery").listFiles()?.size ?: 0))
        // 默认管线预载:hubert + fcpe + gen 常驻(rmvpe 切换时现场加载+进度条)
        // (rmvpe 自动全链路验证改由"全链路"按钮手动触发)
        log("预载 fcpe 管线…")
        preloadDefault()
    }

    /** 启动预载默认 fcpe 管线(hubert+fcpe+gen 常驻)。 */
    private fun preloadDefault() {
        Thread {
            try {
                ensurePy()
                Python.getInstance().getModule("rvc_api")
                    .callAttr("preload_default", nativeLibDir(), filesDir.absolutePath,
                        android.os.Process.myUid())
                f0Loaded["fcpe"] = true
                log("fcpe 管线预载完成")
            } catch (e: Throwable) {
                log("预载失败: " + e)
            } finally {
                preloadDone = true
            }
        }.start()
    }

    /** 切换 F0 提取器:未加载则现场加载(进度条显示,成功才标记,失败可重试)。
     * fcpe 在预载完成前交给 preloadDefault(避免并发 init 损坏 gsv slot)。 */
    private fun ensureF0Loaded(m: String) {
        if (f0Loaded[m] == true) return
        if (f0Loading[m] == true) return
        if (m == "fcpe" && !preloadDone) return          // 预载负责 fcpe
        f0Loading[m] = true
        f0Progress.visibility = View.VISIBLE
        Thread {
            try {
                ensurePy()
                Python.getInstance().getModule("rvc_api")
                    .callAttr("init_f0", nativeLibDir(), filesDir.absolutePath,
                        android.os.Process.myUid(), m)
                f0Loaded[m] = true
                log("F0 %s 已就绪" .format(m))
            } catch (e: Throwable) {
                f0Loaded.remove(m)                       // 失败允许下次重试
                log("F0 加载失败: " + e)
            } finally {
                f0Loading[m] = false
                runOnUiThread { f0Progress.visibility = View.GONE }
            }
        }.start()
    }

    private fun runGen() {
        val profile = profSwitch.isChecked
        log("跑 gen 自检 (profile=" + profile + ")…")
        Thread {
            try {
                ensurePy()
                val out = Python.getInstance().getModule("rvc_api")
                    .callAttr("self_test", nativeLibDir(), filesDir.absolutePath,
                        android.os.Process.myUid(), profile, "/sdcard/rvc_exp")
                log("RESULT: " + out.toString())
            } catch (e: Throwable) {
                log("PY FAIL: " + e)
            }
        }.start()
    }

    private fun runAll() {
        val profile = profSwitch.isChecked
        log("3 模型全检 (profile=" + profile + ")…")
        Thread {
            try {
                ensurePy()
                val out = Python.getInstance().getModule("rvc_api")
                    .callAttr("self_test_all", nativeLibDir(), filesDir.absolutePath,
                        android.os.Process.myUid(), profile, "/sdcard/rvc_exp")
                log("RESULT_ALL: " + out.toString())
            } catch (e: Throwable) {
                log("PY FAIL: " + e)
            }
        }.start()
    }

    private fun runFull(profileOverride: Boolean? = null) {
        val profile = profileOverride ?: profSwitch.isChecked
        log("全链路 (profile=" + profile + ")…")
        Thread {
            try {
                ensurePy()
                val out = Python.getInstance().getModule("rvc_api")
                    .callAttr("self_test_full", nativeLibDir(), filesDir.absolutePath,
                        android.os.Process.myUid(), profile, "/sdcard/rvc_exp")
                log("RESULT_FULL: " + out.toString())
            } catch (e: Throwable) {
                log("PY FAIL: " + e)
            }
        }.start()
    }

    /** ② 拆分测速:z_producer + dec_short 滑窗,对比 jielaide gen 参考。 */
    private fun runRoute2() {
        val profile = profSwitch.isChecked
        log("② 拆分测速 (profile=" + profile + ")…")
        Thread {
            try {
                ensurePy()
                val out = Python.getInstance().getModule("rvc_api")
                    .callAttr("self_test_route2", nativeLibDir(),
                        filesDir.absolutePath, android.os.Process.myUid(),
                        profile, "/sdcard/rvc_exp")
                log("RESULT_ROUTE2: " + out.toString())
            } catch (e: Throwable) {
                log("PY FAIL: " + e)
            }
        }.start()
    }

    /** iSTFT dec 块级流式自测:z_producer + 新蒸馏 4lvl dec(T=37窗) + numpy iSTFT。 */
    private fun runRoute2Istft() {
        val profile = profSwitch.isChecked
        val f0m = f0Spinner.selectedItem.toString()
        val br = brightnessSeek.progress / 100f
        log("iSTFT 拆分测速 (f0=$f0m b=$br profile=" + profile + ")…")
        Thread {
            try {
                ensurePy()
                val out = Python.getInstance().getModule("rvc_api")
                    .callAttr("self_test_route2_istft", nativeLibDir(),
                        filesDir.absolutePath, android.os.Process.myUid(),
                        profile, "/sdcard/rvc_exp", f0m, br)
                log("RESULT_ROUTE2_ISTFT: " + out.toString())
            } catch (e: Throwable) {
                log("PY FAIL: " + e)
            }
        }.start()
    }

    /** 模拟实时:打包参考音频(gya_audio.raw)走 process_audio 实时链路,播放。
     * 对比 麦克风实时(runLive):参考音频走同链路,判断是链路问题还是麦克风输入问题。 */
    private fun runSim() {
        val profile = profSwitch.isChecked
        val key = keyInput.text.toString().toIntOrNull() ?: 0
        val rms = rmsInput.text.toString().toFloatOrNull() ?: 0.25f
        val idx = idxInput.text.toString().toFloatOrNull() ?: 0.75f
        val prot = protInput.text.toString().toFloatOrNull() ?: 0.33f
        val f0m = f0Spinner.selectedItem.toString()
        log("模拟实时:参考音频→实时链路→播放 key=%d rms=%.2f idx=%.2f prot=%.2f f0=%s".format(key, rms, idx, prot, f0m))
        Thread {
            try {
                ensurePy()
                val outBytes = Python.getInstance().getModule("rvc_api")
                    .callAttr("process_stream_v2_ref", nativeLibDir(),
                        filesDir.absolutePath, android.os.Process.myUid(),
                        profile, key, rms, idx, prot, f0m)
                    .toJava(ByteArray::class.java)
                val outF = FloatArray(outBytes.size / 4)
                ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(outF)
                val tr = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(40000)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(outBytes.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                tr.write(outF, 0, outF.size, AudioTrack.WRITE_BLOCKING)
                tr.play()
                log("模拟播放 " + outF.size + " samples @40k (" +
                    String.format("%.2f", outF.size / 40000.0) + "s)")
            } catch (e: Throwable) {
                log("SIM FAIL: " + e)
            }
        }.start()
    }

    /** M2 内存链路验证:process_audio(gya 内存音频) vs PC 参考。 */
    private fun runLiveIo() {
        val profile = profSwitch.isChecked
        log("内存链路验证 (profile=" + profile + ")…")
        Thread {
            try {
                ensurePy()
                val out = Python.getInstance().getModule("rvc_api")
                    .callAttr("self_test_live_io", nativeLibDir(),
                        filesDir.absolutePath, android.os.Process.myUid(),
                        profile)
                log("RESULT_LIVE: " + out.toString())
            } catch (e: Throwable) {
                log("PY FAIL: " + e)
            }
        }.start()
    }

    /** M2 实时:录音 2.24s(16k) → process_audio → AudioTrack 播放(40k)。 */
    private fun runLive() {
        val profile = profSwitch.isChecked
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            log("需授权麦克风,授权后重按 实时")
            return
        }
        val key = keyInput.text.toString().toIntOrNull() ?: 0
        val rms = rmsInput.text.toString().toFloatOrNull() ?: 0.25f
        val idx = idxInput.text.toString().toFloatOrNull() ?: 0.75f
        val prot = protInput.text.toString().toFloatOrNull() ?: 0.33f
        val f0m = f0Spinner.selectedItem.toString()
        log("实时:录音→变声→播放 key=%d rms=%.2f idx=%.2f prot=%.2f f0=%s".format(key, rms, idx, prot, f0m))
        Thread {
            try {
                ensurePy()
                val sr = 16000
                val n = 35840   // 2.24s
                val minBuf = AudioRecord.getMinBufferSize(
                    sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, minBuf * 2)
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    log("LIVE FAIL: 录音初始化失败"); return@Thread
                }
                rec.startRecording()
                val buf = FloatArray(n)
                var rd = 0
                while (rd < n) {
                    val r = rec.read(buf, rd, n - rd, AudioRecord.READ_BLOCKING)
                    if (r > 0) rd += r else break
                }
                rec.stop(); rec.release()
                log("录音完成 " + rd + " samples")
                if (rd < n) { log("LIVE FAIL: 录音不足"); return@Thread }
                val inBytes = ByteArray(n * 4)
                ByteBuffer.wrap(inBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(buf)
                val outBytes = Python.getInstance().getModule("rvc_api")
                    .callAttr("process_audio", nativeLibDir(), filesDir.absolutePath,
                        android.os.Process.myUid(), inBytes, profile, key, rms, idx, prot, f0m)
                    .toJava(ByteArray::class.java)
                val outF = FloatArray(outBytes.size / 4)
                ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(outF)
                val tr = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(40000)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(outBytes.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                tr.write(outF, 0, outF.size, AudioTrack.WRITE_BLOCKING)
                tr.play()
                log("变声播放 " + outF.size + " samples @40k (" +
                    String.format("%.2f", outF.size / 40000.0) + "s)")
            } catch (e: Throwable) {
                log("LIVE FAIL: " + e)
            }
        }.start()
    }

    /** 延迟自测: 合成音频推入测稳态 T_proc, 结果持久化(杀后台不丢)。
     * 测延迟固定 idx=0(纯 NPU T_proc, 不依赖索引资源; 索引是音色不改变处理延迟)。 */
    private fun runLatencyTest() {
        val profile = profSwitch.isChecked
        val key = keyInput.text.toString().toIntOrNull() ?: 0
        val rms = rmsInput.text.toString().toFloatOrNull() ?: 0.25f
        val prot = protInput.text.toString().toFloatOrNull() ?: 0.33f
        log("测延迟 (key=%d rms=%.2f idx=0 prot=%.2f)…".format(key, rms, prot))
        Thread {
            try {
                ensurePy()
                Python.getInstance().getModule("rvc_api").callAttr(
                    "stream_create", nativeLibDir(), filesDir.absolutePath,
                    android.os.Process.myUid(), profile, key, rms, 0.0f, prot, 64, 12)
                val ms = Python.getInstance().getModule("rvc_api")
                    .callAttr("stream_measure_latency", 4).toDouble()
                val s = "%.0f".format(ms)
                prefs.edit().putString("latency_measured_ms", s).apply()
                runOnUiThread { latencyView.text = "实测延迟: $s ms" }
                log("实测延迟 = $s ms (块时长预算 370ms, 实时需 <=370)")
            } catch (e: Throwable) {
                log("测延迟失败: " + e)
            }
        }.start()
    }

    /** 实时流式: AudioRecord(16k) 连续录音 → RVCStream 状态机逐块变声 → AudioTrack(40k) 连续播放。
     * 三线程(录音/处理/播放)+队列, 处理线程唯一调 Python。再按一次按钮停止。 */
    private fun runStreamLive() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            log("需授权麦克风,授权后重按 实时流式")
            return
        }
        if (streamRunning) { log("实时流式已在运行, 再按一次停止"); return }
        val profile = profSwitch.isChecked
        val key = keyInput.text.toString().toIntOrNull() ?: 0
        val rms = rmsInput.text.toString().toFloatOrNull() ?: 0.25f
        val idx = idxInput.text.toString().toFloatOrNull() ?: 0.0f
        val prot = protInput.text.toString().toFloatOrNull() ?: 0.33f
        val budget = budgetInput.text.toString().toIntOrNull() ?: 370
        prefs.edit().putString("latency_budget_ms", budget.toString()).apply()   // 持久化
        streamRunning = true
        log("实时流式启动: 16k录音→RVCStream→40k播放 key=%d rms=%.2f idx=%.2f prot=%.2f 预算=%dms".format(
            key, rms, idx, prot, budget))
        Thread {
            try {
                ensurePy()
                val mod = Python.getInstance().getModule("rvc_api")
                // 预热: 触发 rmvpe64/hubert/z/dec 首次 init + 索引 160MB 加载, 避免首块卡几秒
                log("预热 NPU 管线(约数秒)…")
                mod.callAttr(
                    "stream_create", nativeLibDir(), filesDir.absolutePath,
                    android.os.Process.myUid(), profile, key, rms, idx, prot, 64, 12)
                val warmIn = ByteArray(5920 * 3 * 4)   // 3 块静音
                val w0 = System.currentTimeMillis()
                mod.callAttr("stream_push", warmIn)
                log("预热完成(%.0fs), 重建状态机".format((System.currentTimeMillis() - w0) / 1000.0))
                mod.callAttr("stream_reset")
                mod.callAttr(
                    "stream_create", nativeLibDir(), filesDir.absolutePath,
                    android.os.Process.myUid(), profile, key, rms, idx, prot, 64, 12)
                val inQueue = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
                val outQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
                // 录音线程
                val recThread = Thread {
                    try {
                        val srIn = 16000
                        val minBuf = AudioRecord.getMinBufferSize(
                            srIn, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, srIn,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, minBuf * 4)
                        if (rec.state != AudioRecord.STATE_INITIALIZED) {
                            log("LIVE FAIL: 录音初始化失败"); return@Thread
                        }
                        rec.startRecording()
                        val chunk = 5920   // 370ms@16k
                        val buf = FloatArray(chunk)
                        var nRead = 0; var nFull = 0; var nShort = 0
                        var lastShortR = 0
                        while (streamRunning) {
                            // READ_BLOCKING: 攒满 5920(370ms) 才返回, 否则碎块(320采样)把 inQueue/处理端拖垮
                            val r = rec.read(buf, 0, chunk, AudioRecord.READ_BLOCKING)
                            if (r > 0) {
                                nRead++
                                if (r == chunk) nFull++ else { nShort++; lastShortR = r }
                                if (nRead % 30 == 0) {
                                    log("录音: 30次read 满块=%d 短块=%d(最近短块r=%d/5920)".format(nFull, nShort, lastShortR))
                                    nFull = 0; nShort = 0
                                }
                                inQueue.put(if (r == chunk) buf else buf.copyOf(r))
                            } else if (r < 0) { break }
                            else { Thread.sleep(5) }
                        }
                        rec.stop(); rec.release()
                    } catch (e: Throwable) { log("录音线程: " + e) }
                }
                // 处理线程(唯一调 Python 流式)
                val procThread = Thread {
                    try {
                        val mod = Python.getInstance().getModule("rvc_api")
                        var last = System.currentTimeMillis()
                        var recent = java.util.ArrayList<Long>()
                        var gaps = java.util.ArrayList<Long>()
                        while (streamRunning || inQueue.isNotEmpty()) {
                            val f = inQueue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                            val now = System.currentTimeMillis()
                            gaps.add(now - last); last = now
                            val inBytes = ByteArray(f.size * 4)
                            ByteBuffer.wrap(inBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(f)
                            val out = mod.callAttr("stream_push", inBytes)
                                .toJava(ByteArray::class.java)
                            val dt = System.currentTimeMillis() - now
                            if (out.size > 0) {
                                recent.add(dt)
                                if (recent.size >= 6) {
                                    log("T_proc最近6块=[%s]ms 块间gap均值%dms 录音队%d 输出队%d".format(
                                        recent.joinToString { "%.0f".format(it.toDouble()) },
                                        gaps.sum() / gaps.size, inQueue.size, outQueue.size))
                                    recent.clear(); gaps.clear()
                                }
                                outQueue.put(out)
                            }
                        }
                    } catch (e: Throwable) { log("处理线程: " + e) }
                }
                // 播放线程
                val playThread = Thread {
                    try {
                        val srOut = 40000
                        val minOut = AudioTrack.getMinBufferSize(
                            srOut, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                        var played = 0; var emptyCnt = 0
                        // 播放预填: "延迟预算(ms)" 框 → 预填块数(吸收处理突刺, 端到端延迟≈预算)
                        // budget 370→1块, 700→2块, 1110→3块... 每块=370ms@40k
                        val blkOut = 59200   // 370ms 一块 = 14800 采样 × 4B
                        val prefillBlocks = maxOf(1, (budget + 369) / 370)
                        val needBuf = maxOf(minOut * 4, prefillBlocks * blkOut + blkOut)
                        val tr = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(AudioFormat.Builder()
                                .setSampleRate(srOut)
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(needBuf)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        var preFilled = 0
                        while (preFilled < prefillBlocks && (streamRunning || outQueue.isNotEmpty())) {
                            val p = outQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                            if (p == null) { if (!streamRunning) break else continue }
                            val pf = FloatArray(p.size / 4)
                            ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pf)
                            tr.write(pf, 0, pf.size, AudioTrack.WRITE_BLOCKING)
                            preFilled++
                        }
                        log("播放: 预填 $preFilled 块(≈${preFilled * 370}ms 缓冲) 后 play")
                        tr.play()
                        while (streamRunning || outQueue.isNotEmpty()) {
                            val out = outQueue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)
                            if (out == null) { emptyCnt++; continue }
                            val outF = FloatArray(out.size / 4)
                            ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(outF)
                            tr.write(outF, 0, outF.size, AudioTrack.WRITE_BLOCKING)
                            played++
                            if (played % 10 == 0) log("播放: %d段 空等%d次 队列%d".format(played, emptyCnt, outQueue.size))
                        }
                        tr.stop(); tr.release()
                    } catch (e: Throwable) { log("播放线程: " + e) }
                }
                recThread.start(); procThread.start(); playThread.start()
                recThread.join(); procThread.join(); playThread.join()
                log("实时流式结束")
            } catch (e: Throwable) {
                log("LIVE STREAM FAIL: " + e)
            } finally {
                streamRunning = false
            }
        }.start()
    }

    /** 递归复制 assets 子目录到 filesDir,已存在文件跳过、缺失补拷(参考 GSV)。 */
    private fun extract(src: String, dst: File) {
        if (!dst.exists()) dst.mkdirs()
        assets.list(src)?.forEach { name ->
            val full = "$src/$name"
            val child = File(dst, name)
            var isDir = false
            try { assets.open(full).use { } } catch (e: Exception) { isDir = true }
            if (isDir) extract(full, child)
            else if (!child.exists())
                assets.open(full).use { ins -> child.outputStream().use { ins.copyTo(it) } }
        }
    }

    private fun nativeLibDir(): String =
        try { applicationInfo.nativeLibraryDir } catch (e: Exception) { "?" }

    private fun ensurePy() {
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
    }

    private fun log(s: String) {
        runOnUiThread { logView.append(s + "\n") }
        android.util.Log.i("RVC", s)
    }
}
