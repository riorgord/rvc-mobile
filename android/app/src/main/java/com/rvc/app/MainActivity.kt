package com.rvc.app

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val scroll = ScrollView(this)
        logView = TextView(this).apply { textSize = 12f }
        scroll.addView(logView)

        profSwitch = Switch(this).apply {
            text = "profiling → /sdcard/rvc_exp"
        }
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
        val simBtn = Button(this).apply {
            text = "模拟"
            setOnClickListener { runSim() }
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
            setText("0.75")
            hint = "index_rate"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(16, 0, 16, 0)
        }
        protInput = EditText(this).apply {
            setText("0.33")
            hint = "protect 0-0.5"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(16, 0, 16, 0)
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
            addView(simBtn, w)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(rowSwitch)
            addView(rowBtns)
            addView(rowBtns2)
            addView(rowParams1)
            addView(rowParams2)
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
        // 验证阶段:启动自动跑全链路(M1b,无后处理,corr 对比 PC 参考)
        // (process_audio 实时路径含 UV 插值+rms,听感验证走"实时"按钮)
        log("自动 全链路…")
        runFull(intent.getBooleanExtra("profile", profSwitch.isChecked))
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

    /** 模拟实时:打包参考音频(gya_audio.raw)走 process_audio 实时链路,播放。
     * 对比 麦克风实时(runLive):参考音频走同链路,判断是链路问题还是麦克风输入问题。 */
    private fun runSim() {
        val profile = profSwitch.isChecked
        val key = keyInput.text.toString().toIntOrNull() ?: 0
        val rms = rmsInput.text.toString().toFloatOrNull() ?: 0.25f
        val idx = idxInput.text.toString().toFloatOrNull() ?: 0.75f
        val prot = protInput.text.toString().toFloatOrNull() ?: 0.33f
        log("模拟实时:参考音频→实时链路→播放 key=%d rms=%.2f idx=%.2f prot=%.2f".format(key, rms, idx, prot))
        Thread {
            try {
                ensurePy()
                val outBytes = Python.getInstance().getModule("rvc_api")
                    .callAttr("process_audio_ref", nativeLibDir(),
                        filesDir.absolutePath, android.os.Process.myUid(),
                        profile, key, rms, idx, prot)
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
        log("实时:录音→变声→播放 key=%d rms=%.2f idx=%.2f prot=%.2f".format(key, rms, idx, prot))
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
                        android.os.Process.myUid(), inBytes, profile, key, rms, idx, prot)
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
