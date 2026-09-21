package com.rvc.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
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
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import kotlin.concurrent.thread
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RVC 手机实时变声 M0:unsigned PD 激活 + gen_fp32 单模型自检。
 * - 启动解压 assets/hexagon-v69 + assets/testdata → filesDir(共享件 models/periphery 已移出 APK,首启下载)
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
    private var roles = listOf<RoleInfo>()
    private lateinit var roleSpinner: Spinner
    private lateinit var setupProgress: ProgressBar
    private lateinit var setupStatus: TextView
    private lateinit var halBtn: Button
    private val REQ_ROLE = 1001
    private val REQ_SHARED = 1002

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
        val testLatBtn = Button(this).apply {
            text = "测延迟"
            setOnClickListener { runLatencyTest() }
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
            setText("0.75")
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
        halBtn = Button(this)
        halBtn.text = "HAL桥接(开)"
        halBtn.setOnClickListener {
            if (HalRvcBridge.isActive()) {
                HalRvcBridge.stop()
                if (!streamRunning) runCatching { stopService(Intent(this@MainActivity, MicrophoneService::class.java)) }
                halBtn.text = "HAL桥接(开)"
                log("HAL 桥接已停止(恢复纯透传)")
            } else {
                if (!isHalModuleActive()) {
                    log("需要 root + 安装 RVC HAL 模块(ro.hardware.audio.primary 非 rvc),无法启动变声")
                    Toast.makeText(this@MainActivity,
                        "需要 root 权限并安装 RVC HAL 模块", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val key = keyInput.text.toString().toIntOrNull() ?: 0
                val rms = rmsInput.text.toString().toFloatOrNull() ?: 0.75f
                val idx = idxInput.text.toString().toFloatOrNull() ?: 0.75f
                val prot = protInput.text.toString().toFloatOrNull() ?: 0.33f
                val roleDir = RoleManager.currentRoleDir(filesDir, prefs)
                if (roleDir == null) log("当前未选角色,将用 files_dir 旧路径")
                HalRvcBridge.start(this@MainActivity, key, rms, idx, prot, roleDir)
                runCatching { startForegroundService(Intent(this@MainActivity, MicrophoneService::class.java)) }
                halBtn.text = "HAL桥接(关)"
                log("HAL 桥接启动: 已连 socket,去微信发语音测试")
            }
        }
        val modBtn = Button(this).apply {
            text = "安装/更新 HAL 模块"
            setOnClickListener { installHalModule() }
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
        val rowHal = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(halBtn, w)
            addView(modBtn, w)
        }

        // ---- 角色/共享件管理 ----
        roleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter<String>(this@MainActivity,
                android.R.layout.simple_spinner_item, emptyList()).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        val useRoleBtn = Button(this).apply {
            text = "用选中角色"
            setOnClickListener { useSelectedRole() }
        }
        val importRoleBtn = Button(this).apply {
            text = "导入角色包"
            setOnClickListener { pickFile(REQ_ROLE) }
        }
        val importSharedBtn = Button(this).apply {
            text = "SAF导入shared"
            setOnClickListener { pickFile(REQ_SHARED) }
        }
        val downloadSharedBtn = Button(this).apply {
            text = "下载共享件"
            setOnClickListener { downloadShared() }
        }
        setupProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        setupStatus = TextView(this).apply {
            textSize = 12f
            setPadding(0, 4, 0, 0)
        }
        val rowRole = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(roleSpinner, w)
            addView(useRoleBtn, w)
        }
        val rowRoleBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(importRoleBtn, w)
            addView(importSharedBtn, w)
            addView(downloadSharedBtn, w)
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
            addView(rowHal)
            addView(TextView(this@MainActivity).apply {
                text = "── 角色/共享件 ──"
                textSize = 12f
                setPadding(0, 16, 0, 4)
            })
            addView(rowRole)
            addView(rowRoleBtns)
            addView(setupProgress)
            addView(setupStatus)
            addView(f0Progress)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        log("RVC App M0")
        log("uid=" + android.os.Process.myUid())
        log("filesDir=" + filesDir.absolutePath)
        // 解压 assets → filesDir(幂等)

        extract("hexagon-v69", File(filesDir, "hexagon-v69"))
        extract("testdata", File(filesDir, "testdata"))

        log("assets 解压 OK: hexagon %d / testdata %d".format(

            File(filesDir, "hexagon-v69").listFiles()?.size ?: 0,
            File(filesDir, "testdata").listFiles()?.size ?: 0))

        // 默认管线预载:hubert + fcpe + gen 常驻(rmvpe 切换时现场加载+进度条)
        // (rmvpe 自动全链路验证改由"全链路"按钮手动触发)
        refreshRoles()
        refreshSharedStatus()
        if (RoleManager.isSharedReady(filesDir)) {
            log("预载 fcpe 管线…")
            preloadDefault()
        } else {
            log("共享件缺失:请先 下载共享件 或 SAF 导入 shared.zip")
        }
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
        val rms = rmsInput.text.toString().toFloatOrNull() ?: 0.75f
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
        val rms = rmsInput.text.toString().toFloatOrNull() ?: 0.75f
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
        val rms = rmsInput.text.toString().toFloatOrNull() ?: 0.75f
        val prot = protInput.text.toString().toFloatOrNull() ?: 0.33f
        log("测延迟 (key=%d rms=%.2f idx=0 prot=%.2f)…".format(key, rms, prot))
        Thread {
            try {
                ensurePy()
                Python.getInstance().getModule("rvc_api").callAttr(
                    "stream_create", nativeLibDir(), filesDir.absolutePath,
                    android.os.Process.myUid(), profile, key, rms, 0.0f, prot, 64, 12,
                    RoleManager.currentRoleDir(filesDir, prefs))
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

    // ---------------- 角色/共享件管理 ----------------

    private fun refreshRoles() {
        roles = RoleManager.scanRoles(filesDir)
        val names = roles.map { "${it.name} (${it.modelId})" }
        roleSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val cur = RoleManager.currentRoleId(prefs)
        val idx = roles.indexOfFirst { it.modelId == cur }
        if (idx >= 0) roleSpinner.setSelection(idx)
        log("角色列表: ${roles.size} 个")
    }

    private fun useSelectedRole() {
        val idx = roleSpinner.selectedItemPosition
        if (idx < 0 || idx >= roles.size) {
            log("请先导入角色包")
            return
        }
        val r = roles[idx]
        RoleManager.setCurrentRole(prefs, r.modelId)
        if (HalRvcBridge.isActive()) {
            HalRvcBridge.stop()
            if (!streamRunning) runCatching { stopService(Intent(this@MainActivity, MicrophoneService::class.java)) }
            halBtn.text = "HAL桥接(开)"
            log("角色已切换 → ${r.name} (${r.modelId}),HAL 已停止,请重新点「HAL桥接」启动")
        } else {
            log("当前角色 → ${r.name} (${r.modelId}),启动 HAL 后生效")
        }
    }

    private fun refreshSharedStatus() {
        if (RoleManager.isSharedReady(filesDir)) {
            setupStatus.text = "共享件就绪 ✔"
        } else {
            setupStatus.text = "共享件缺失 → 请下载或 SAF 导入 shared.zip"
        }
    }

    /* 轻量检测 HAL 模块是否生效:ro.hardware.audio.primary 应为 rvc。
     * 无需 root 权限,读系统属性即可;非 root/未装模块时会拦截 HAL 启动。 */
    private fun isHalModuleActive(): Boolean {
        return try {
            val p = ProcessBuilder("getprop", "ro.hardware.audio.primary").start()
            val s = p.inputStream.bufferedReader().readText().trim().lowercase()
            p.waitFor()
            s.contains("rvc")
        } catch (t: Throwable) {
            false
        }
    }

    private fun runSu(cmd: String): Pair<Int, String> {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val rc = p.waitFor()
            rc to out
        } catch (t: Throwable) {
            -1 to ""
        }
    }

    private fun hasRoot(): Boolean {
        val (rc, out) = runSu("id")
        return rc == 0 && out.contains("uid=0")
    }

    private fun moduleInstalledVersion(): String? {
        val (rc, out) = runSu("grep '^version=' /data/adb/modules/rvc_virtual_mic_hal/module.prop 2>/dev/null")
        if (rc != 0) return null
        return out.trim().removePrefix("version=").ifBlank { null }
    }

    private fun copyBundledModuleZip(): File? {
        return try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, "rvc_module.zip")
            assets.open("rvc_module.zip").use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
            f
        } catch (t: Throwable) {
            null
        }
    }

    private fun installHalModule() {
        thread {
            log("① 检查 root…")
            if (!hasRoot()) {
                log("✗ 未获得 root 授权:请先在 Magisk/KernelSU 中允许本应用")
                return@thread
            }
            log("✓ root 正常")
            val cur = moduleInstalledVersion()
            log(if (cur != null) "当前模块版本: $cur" else "未检测到已装模块")
            log("② 解出内置模块包…")
            val zip = copyBundledModuleZip()
            if (zip == null) {
                log("✗ 内置模块包读取失败")
                return@thread
            }
            log("✓ 已解出: ${zip.absolutePath}")
            log("③ 执行 magisk --install-module …")
            var (rc, out) = runSu("magisk --install-module \"${zip.absolutePath}\"")
            if (rc == 0) {
                log("✓ Magisk 安装成功")
            } else {
                log("magisk 失败(rc=$rc),试 ksud module install …\n$out")
                val (rc2, out2) = runSu("ksud module install \"${zip.absolutePath}\"")
                if (rc2 == 0) {
                    rc = 0
                    log("✓ KernelSU 安装成功")
                } else {
                    log("✗ KernelSU 也失败(rc=$rc2)\n$out2")
                }
            }
            if (rc != 0) {
                log("✗ 自动安装失败,请用 Magisk 应用手动刷 rvc_module.zip")
                return@thread
            }
            log("④ 安装/更新已提交,重启后生效")
            handler.post { showRebootDialog() }
        }
    }

    private fun showRebootDialog() {
        AlertDialog.Builder(this)
            .setTitle("HAL 模块已更新")
            .setMessage("重启后新的 HAL 模块才会生效。现在重启吗?")
            .setPositiveButton("立即重启") { _, _ ->
                thread { runSu("reboot") }
            }
            .setNegativeButton("稍后", null)
            .show()
    }



    private fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/zip"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        startActivityForResult(i, req)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri: Uri = data.data!!
        when (requestCode) {
            REQ_ROLE -> Thread {
                try {
                    val id = RoleManager.importUri(this, uri, filesDir)
                    runOnUiThread {
                        refreshRoles()
                        if (roles.isNotEmpty()) {
                            val i = roles.indexOfFirst { it.modelId == id }.coerceAtLeast(0)
                            roleSpinner.setSelection(i)
                        }
                        RoleManager.setCurrentRole(prefs, id)
                        log("角色导入成功: $id")
                    }
                } catch (e: Throwable) {
                    log("角色导入失败: $e")
                }
            }.start()
            REQ_SHARED -> Thread {
                try {
                    RoleManager.importUri(this, uri, filesDir)
                    runOnUiThread {
                        refreshSharedStatus()
                        log("共享件导入成功")
                    }
                } catch (e: Throwable) {
                    log("共享件导入失败: $e")
                }
            }.start()
        }
    }

    private fun downloadShared() {
        setupProgress.visibility = View.VISIBLE
        setupProgress.progress = 0
        setupStatus.text = "探测下载源…"
        Thread {
            val src = SharedDownloader.probeAndPick()
            if (src == null) {
                runOnUiThread {
                    setupStatus.text = "下载源不可达/未配置 → 请用 SAF 导入 shared.zip"
                    setupProgress.visibility = View.GONE
                }
                log("下载源不可达或未配置,改用 SAF 导入")
                return@Thread
            }
            val dest = File(filesDir, "shared.zip")
            runOnUiThread { setupStatus.text = "从 ${src.name} 下载…" }
            val ok = SharedDownloader.download(src, dest, { done, total ->
                runOnUiThread {
                    if (total != null && total > 0) {
                        setupProgress.progress = ((done * 100) / total).toInt()
                        setupStatus.text = "下载 ${done / 1048576}MB / ${total / 1048576}MB"
                    } else {
                        setupStatus.text = "下载 ${done / 1048576}MB…"
                    }
                }
            })
            if (!ok) {
                runOnUiThread {
                    setupStatus.text = "下载失败 → 请用 SAF 导入"
                    setupProgress.visibility = View.GONE
                }
                return@Thread
            }
            try {
                RoleManager.importZipFile(dest, filesDir)
                dest.delete()
                runOnUiThread {
                    refreshSharedStatus()
                    setupStatus.text = "共享件下载+解压完成 ✔"
                    setupProgress.visibility = View.GONE
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    setupStatus.text = "解压/校验失败: $e"
                    setupProgress.visibility = View.GONE
                }
                log("shared 解压失败: $e")
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
