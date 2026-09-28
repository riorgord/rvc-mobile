package com.rvc.app

/**
 * ⚠ P2.6 已退役(2026-09-28):旧手写 View UI。
 *
 * - launcher 已切到 RvcComposeActivity(Compose + Miuix 新 UI);
 * - manifest 已移除本 Activity 声明(不再有外部启动入口);
 * - 全部调试功能已由新 UI 调试页承接(版本号 5 连击进入):
 *   设备检测 / 3模型全检 / 全链路 / ②拆分 / iSTFT / 模拟 / 实时 / 测延迟 / 参数面板 / 日志流
 * - 本文件保留留档(git 历史中亦有);如需恢复,加回 manifest 声明即可。
 */

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
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
import java.io.File
import kotlin.concurrent.thread
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RVC 手机实时变声 M0:unsigned PD 激活 + gen_fp32 单模型自检。
 * - 启动解压 assets/testdata → filesDir(QNN 运行时全在 jniLibs,按 SoC 自动选 arch;共享件 models/periphery 已移出 APK,首启下载)
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
    private val core by lazy { RvcCore(applicationContext) }
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
        // 恢复用户手动选择的 SoC(识别失败时弹窗选择的)
        RoleManager.setManualSoc(prefs.getString("soc_override", null))

        val scroll = ScrollView(this)
        logView = TextView(this).apply { textSize = 12f }
        scroll.addView(logView)

        profSwitch = Switch(this).apply {
            text = "profiling → /sdcard/rvc_exp"
        }
        profSwitch.isChecked = intent.getBooleanExtra("profile", false)
        val runBtn = Button(this).apply {
            text = "设备检测"
            setOnClickListener { runDeviceCheck() }
            setOnLongClickListener {
                showSimulateTierDialog()
                true
            }
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
                if (!core.isHalModuleActive()) {
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
        val onlineBtn = Button(this).apply {
            text = "在线角色库"
            setOnClickListener { openOnlineLibrary() }
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
            addView(onlineBtn, w)
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

        core.extract("testdata", File(filesDir, "testdata"))

        log("assets 解压 OK: testdata %d".format(
            File(filesDir, "testdata").listFiles()?.size ?: 0))

        // 默认管线预载:hubert + fcpe + gen 常驻(rmvpe 切换时现场加载+进度条)
        // (rmvpe 自动全链路验证改由"全链路"按钮手动触发)
        refreshRoles()
        refreshSharedStatus()
        val installedVer = RoleManager.installedSharedVersion(filesDir)
        if (RoleManager.isSharedReady(filesDir)) {
            if (installedVer in 1 until RoleManager.MIN_MODEL_VERSION) {
                log("模型包版本过旧(v$installedVer < v${RoleManager.MIN_MODEL_VERSION}),跳过预载")
                promptOldModelVersion()
            } else {
                log("预载 fcpe 管线…")
                preloadDefault()
            }
        } else {
            log("共享件缺失:请先 下载共享件 或 SAF 导入 shared.zip")
        }
        // SoC 探测失败且未手动选择 → 启动即弹窗让用户手动选(列表没有=不支持)
        if (RoleManager.effectiveSoc().isEmpty()) {
            resolveSoc(null) { _ -> }
        }
        // 启动静默检查模型更新(一天最多一次)
        thread {
            val last = prefs.getLong("last_model_check_ts", 0)
            if (System.currentTimeMillis() - last < 24 * 3600 * 1000L) return@thread
            val c = Catalog.fetch() ?: return@thread
            val soc = RoleManager.effectiveSoc()
            if (soc.isEmpty()) return@thread
            checkModelVersion(c, soc, fromUser = false)
        }
    }

    /** 启动预载默认 fcpe 管线(hubert+fcpe+gen 常驻)。 */
    private fun preloadDefault() {
        Thread {
            try {
                core.pyPreloadDefault()
                f0Loaded["fcpe"] = true
                log("fcpe 管线预载完成")
            } catch (e: Throwable) {
                log("预载失败: " + e)
            } finally {
                preloadDone = true
            }
        }.start()
    }

    /** 模型版本检查(需在后台线程调用,弹窗回主线程):
     * App 版本门槛 → 最低模型版本门槛 → 在线更新检测。 */
    private fun checkModelVersion(catalog: Catalog.CatalogData, soc: String, fromUser: Boolean) {
        val shared = Catalog.compatibleShared(catalog, soc) ?: return
        val localVer = RoleManager.installedSharedVersion(filesDir)
        val curApp = core.appVersionName()
        if (core.compareVersion(curApp, catalog.appMinVersion) < 0) {
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("App 版本过低")
                    .setMessage("当前 $curApp < 需要的 ${catalog.appMinVersion}\n请先升级 App 再使用在线功能。")
                    .setPositiveButton("知道了", null)
                    .show()
            }
            return
        }
        if (localVer in 1 until RoleManager.MIN_MODEL_VERSION) {
            runOnUiThread { promptOldModelVersion() }
            return
        }
        // 共享件已就绪但还没版本记录(旧版装的):不自动弹更新,手动检查时提示重新导入一次
        if (localVer == 0 && RoleManager.isSharedReady(filesDir)) {
            if (fromUser) {
                runOnUiThread {
                    Toast.makeText(this, "已检测到共享件但缺少版本记录\n请重新下载/导入一次 shared.zip", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        if (shared.version > localVer) {
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("发现新模型包")
                    .setMessage("当前已装 v$localVer,在线有 v${shared.version}\n是否现在更新?")
                    .setPositiveButton("更新") { _, _ -> downloadSharedFromCatalog(shared) }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        } else if (fromUser) {
            runOnUiThread {
                Toast.makeText(this, "模型包已是最新 v$localVer", Toast.LENGTH_SHORT).show()
            }
        }
        if (!fromUser) {
            prefs.edit().putLong("last_model_check_ts", System.currentTimeMillis()).apply()
        }
    }

    /** 已装模型包低于 App 最低版本要求时的阻断提示。 */
    private fun promptOldModelVersion() {
        AlertDialog.Builder(this)
            .setTitle("模型包版本过旧")
            .setMessage("当前模型包 v${RoleManager.installedSharedVersion(filesDir)} < 需要的 v${RoleManager.MIN_MODEL_VERSION}\n需更新后才能使用。")
            .setPositiveButton("去更新") { _, _ -> downloadShared() }
            .setNegativeButton("知道了", null)
            .show()
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
                core.pyInitF0(m)
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

    /** 设备兼容性检测(测试按钮)。弹窗展示完整判定结果与各检测项。 */
    private fun runDeviceCheck() {
        thread {
            log("① 设备兼容性检测…")
            val guard = DeviceGuard.evaluate()
            log("检测结果: tier=${guard.tier} root=${guard.rootType} " +
                "kernel_official=${guard.checks[1].pass} audio_primary=${guard.checks[2].pass} sdk=${guard.checks[3].detail}")
            log("内核: ${guard.kernelVersion.take(120)}")
            val detail = buildString {
                append("判定档位: ")
                append(when (guard.tier) {
                    DeviceGuard.Tier.GREEN -> "🟢 GREEN(直接放行)"
                    DeviceGuard.Tier.YELLOW -> "🟡 YELLOW(勾选确认后放行)"
                    DeviceGuard.Tier.RED -> "🔴 RED(拦截安装)"
                })
                append("\n\n检测项:\n")
                for (c in guard.checks) {
                    append(if (c.pass) "✓ " else "✗ ")
                    append(c.name).append(": ").append(c.detail).append("\n")
                }
                append("\n音频 HAL 判定: ").append(guard.halScheme).append("\n")
                if (guard.redReasons.isNotEmpty()) {
                    append("\n拦截原因:\n• ").append(guard.redReasons.joinToString("\n• ")).append("\n")
                }
                if (guard.yellowReasons.isNotEmpty()) {
                    append("\n风险提示:\n• ").append(guard.yellowReasons.joinToString("\n• ")).append("\n")
                }
                append("\n内核版本: ").append(guard.kernelVersion.take(150))
            }
            handler.post {
                AlertDialog.Builder(this)
                    .setTitle("设备检测结果")
                    .setMessage(detail)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        }
    }

    /** 长按「设备检测」→ 模拟档位预览(测试用,只验证弹窗 UI,不真装)。 */
    private fun showSimulateTierDialog() {
        val items = arrayOf("模拟 GREEN(直通)", "模拟 YELLOW(勾选确认)", "模拟 RED(拦截)")
        AlertDialog.Builder(this)
            .setTitle("模拟档位预览(测试)")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this, "🟢 GREEN:直通(无弹窗)", Toast.LENGTH_SHORT).show()
                    1 -> showYellowDialog(fakeResult(DeviceGuard.Tier.YELLOW), simulate = true)
                    2 -> showRedDialog(fakeResult(DeviceGuard.Tier.RED))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 构造模拟判定结果(检测项用真实设备值,档位/原因用假数据)。 */
    private fun fakeResult(tier: DeviceGuard.Tier): DeviceGuard.Result {
        val real = DeviceGuard.evaluate()
        return when (tier) {
            DeviceGuard.Tier.YELLOW -> DeviceGuard.Result(
                tier = DeviceGuard.Tier.YELLOW,
                checks = real.checks,
                redReasons = emptyList(),
                yellowReasons = listOf(
                    "KernelSU 环境:与 Magisk 行为差异较大,未经广泛测试(模拟)",
                    "Android ${Build.VERSION.RELEASE}(SDK=${DeviceGuard.sdkInt()}) 未经实测,仅在 Android 12 验证(模拟)"
                ),
                kernelVersion = real.kernelVersion,
                halScheme = real.halScheme,
                rootType = "ksu",
                apState = real.apState
            )
            DeviceGuard.Tier.RED -> DeviceGuard.Result(
                tier = DeviceGuard.Tier.RED,
                checks = real.checks,
                redReasons = listOf(
                    "检测到非官方内核:6.6.77-Jianke-Jiangnan(模拟)",
                    "音频 HAL 为 AIDL core(无 audio.primary.*.so),wrapper 无法生效(模拟)"
                ),
                yellowReasons = emptyList(),
                kernelVersion = real.kernelVersion,
                halScheme = real.halScheme,
                rootType = "ksu",
                apState = real.apState
            )
            else -> real
        }
    }

    private fun runAll() {
        val profile = profSwitch.isChecked
        log("3 模型全检 (profile=" + profile + ")…")
        Thread {
            try {
                log("RESULT_ALL: " + core.pySelfTestAll(profile))
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
                log("RESULT_FULL: " + core.pySelfTestFull(profile))
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
                log("RESULT_ROUTE2: " + core.pySelfTestRoute2(profile))
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
                log("RESULT_ROUTE2_ISTFT: " + core.pySelfTestRoute2Istft(profile, f0m, br))
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
                val outBytes = core.pyProcessStreamV2Ref(profile, key, rms, idx, prot, f0m)
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
                log("RESULT_LIVE: " + core.pySelfTestLiveIo(profile))
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
                val outBytes = core.pyProcessAudio(profile, inBytes, key, rms, idx, prot, f0m)
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
                core.pyStreamCreate(profile, key, rms, 0.0f, prot, 64, 12)
                val ms = core.pyStreamMeasureLatency()
                val s = "%.0f".format(ms)
                val thermal = core.readThermalSummary()
                prefs.edit().putString("latency_measured_ms", s).apply()
                runOnUiThread { latencyView.text = "实测延迟: $s ms\n$thermal" }
                log("实测延迟 = $s ms (块时长预算 370ms, 实时需 <=370) | $thermal")
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

    private fun installHalModule() {
        thread {
            log("① 设备兼容性检测…")
            val guard = DeviceGuard.evaluate()
            log("检测结果: tier=${guard.tier} root=${guard.rootType} " +
                "kernel_official=${guard.checks[1].pass} audio_primary=${guard.checks[2].pass} sdk=${guard.checks[3].detail}")
            log("内核: ${guard.kernelVersion.take(120)}")
            when (guard.tier) {
                DeviceGuard.Tier.RED -> {
                    log("✗ 设备不满足机架运行条件,已拦截安装")
                    handler.post { showRedDialog(guard) }
                    return@thread
                }
                DeviceGuard.Tier.YELLOW -> {
                    log("⚠ 设备条件有风险,需用户确认后放行")
                    handler.post { showYellowDialog(guard) }
                    return@thread
                }
                DeviceGuard.Tier.GREEN -> {
                    log("✓ 条件符合,直接安装")
                    doInstallHalModule()
                }
            }
        }
    }

    private fun showRebootDialog() {
        AlertDialog.Builder(this)
            .setTitle("HAL 模块已更新")
            .setMessage("重启后新的 HAL 模块才会生效。现在重启吗?")
            .setPositiveButton("立即重启") { _, _ ->
                thread { core.runSu("reboot") }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    /* ============ 机架设备拦截弹窗(保守策略,2026-09-26) ============
     * RED   → 纯拦截:无继续按钮,只有退出。
     * YELLOW→ 风险确认:3 秒后出现勾选框「我已阅读并理解上述风险」,
     *          勾选后「继续安装」按钮才可点击。
     * 已验证基线:K50U + legacy/HIDL + 官方内核 + Android 12 + Magisk。
     */

    private fun showRedDialog(guard: DeviceGuard.Result) {
        val reasons = guard.redReasons.joinToString("\n• ", "• ") { it }
        AlertDialog.Builder(this)
            .setTitle("⚠ 设备不满足机架运行条件")
            .setMessage(
                "本机架目前仅验证于:\n" +
                "K50U + Legacy/HIDL 音频 + 官方内核 + Android 12(MIUI13)+ Magisk\n\n" +
                "检测到以下不兼容项:\n$reasons\n\n" +
                "为避免无声/变声失败或系统异常,已禁止安装。"
            )
            .setPositiveButton("退出", null)
            .setCancelable(false)
            .show()
    }

    private fun showYellowDialog(guard: DeviceGuard.Result, simulate: Boolean = false) {
        val risks = guard.yellowReasons.joinToString("\n• ", "• ") { it }
        // 勾选框立即可勾;「继续安装」按钮 10 秒倒计时(开屏广告样式)后才可点
        val cb = android.widget.CheckBox(this).apply {
            text = "我已阅读并理解上述风险"
        }
        val tv = TextView(this).apply {
            text = "检测到以下条件未完全符合已验证基线:\n$risks\n\n" +
                "安装后可能出现:无声、变声失败、系统音频异常。\n" +
                "请阅读并勾选确认;「继续安装」将在 10 秒倒计时后可用。"
            setPadding(24, 8, 24, 8)
        }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(cb)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("⚠ 设备条件与已验证基线不完全一致")
            .setView(ll)
            .setPositiveButton("继续安装", null) // 用 null,下面自己控点击
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()
        dlg.show()
        val continueBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        continueBtn.isEnabled = false
        continueBtn.text = "继续安装 (10)"
        continueBtn.setOnClickListener {
            dlg.dismiss()
            if (simulate) {
                log("✓ [模拟] 用户已确认风险(仅预览,未实际安装)")
            } else {
                doInstallHalModule()
            }
        }
        // 10 秒倒计时:每秒更新按钮文字,到 0 后受勾选框控制
        val ticker = object : Runnable {
            var remaining = 10
            override fun run() {
                if (!dlg.isShowing) return
                remaining--
                if (remaining <= 0) {
                    continueBtn.text = "继续安装"
                    continueBtn.isEnabled = cb.isChecked
                } else {
                    continueBtn.text = "继续安装 ($remaining)"
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(ticker, 1000)
        cb.setOnCheckedChangeListener { _, checked ->
            // 倒计时结束后勾选才生效;倒计时中保持禁用
            if (ticker.remaining <= 0) continueBtn.isEnabled = checked
        }
    }

    /** 拦截弹窗放行后的真正安装流程(后台线程)。 */
    private fun doInstallHalModule() {
        thread {
            log("✓ 用户已确认风险,继续安装")
            log("① 检查 root…")
            if (!core.hasRoot()) {
                log("✗ 未获得 root 授权:请先在 Magisk/KernelSU 中允许本应用")
                return@thread
            }
            log("✓ root 正常")
            log("② 探测音频 HAL 方案…")
            val scheme = core.detectHalScheme()
            log("音频 HAL 方案: $scheme")
            if (scheme != "LEGACY" && scheme != "HIDL") {
                log("✗ 此设备为 $scheme 音频 HAL,不加载 audio.primary.*.so,已拦截安装")
                handler.post {
                    Toast.makeText(this@MainActivity,
                        "此设备($scheme)暂不支持 HAL 模块安装", Toast.LENGTH_LONG).show()
                }
                return@thread
            }
            log("✓ 方案可用,继续")
            val cur = core.moduleInstalledVersion()
            log(if (cur != null) "当前模块版本: $cur" else "未检测到已装模块")
            log("② 解出内置模块包…")
            val zip = core.copyBundledModuleZip()
            if (zip == null) {
                log("✗ 内置模块包读取失败")
                return@thread
            }
            log("✓ 已解出: ${zip.absolutePath}")
            log("③ 执行 magisk --install-module …")
            var (rc, out) = core.runSu("magisk --install-module \"${zip.absolutePath}\"")
            if (rc == 0) {
                log("✓ Magisk 安装成功")
            } else {
                log("magisk 失败(rc=$rc),试 ksud module install …\n$out")
                val (rc2, out2) = core.runSu("/data/adb/ksud module install \"${zip.absolutePath}\"")
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
        if (RoleManager.isSharedReady(filesDir)) {
            val localVer = RoleManager.installedSharedVersion(filesDir)
            if (localVer >= RoleManager.MIN_MODEL_VERSION) {
                setupStatus.text = "共享件已就绪(v$localVer),无需下载"
                log("共享件已就绪(v$localVer),跳过下载")
                return
            }
            if (localVer == 0) {
                setupStatus.text = "共享件已就绪但缺版本记录;如需记录版本请到 在线角色库-模型更新 重新下载"
                log("共享件已就绪但缺版本记录,跳过下载")
                return
            }
        }
        setupProgress.visibility = View.VISIBLE
        setupProgress.progress = 0
        setupStatus.text = "拉取目录并确认 SoC…"
        Thread {
            val catalog = Catalog.fetch()
            if (catalog == null) {
                runOnUiThread {
                    setupStatus.text = "在线目录不可达 → 请用 SAF 导入 shared.zip"
                    setupProgress.visibility = View.GONE
                }
                log("在线目录不可达,改用 SAF 导入")
                return@Thread
            }
            resolveSoc(catalog) { soc ->
                if (soc == null) {
                    setupProgress.visibility = View.GONE
                    setupStatus.text = "未选择 SoC → 请用 SAF 导入 shared.zip"
                    return@resolveSoc
                }
                val shared = Catalog.compatibleShared(catalog, soc)
                if (shared == null) {
                    setupProgress.visibility = View.GONE
                    setupStatus.text = "软件暂不支持 $soc（目录里没有对应共享件）"
                    log("目录没有 $soc 的共享件")
                    return@resolveSoc
                }
                downloadSharedFromCatalog(shared)
            }
        }.start()
    }

    /**
     * 确认本机 SoC:
     * - 已手动选择/已探测到 → 直接回调;
     * - 探测不到 → 弹窗让用户手动选择(列表来自 catalog devices;catalog 不可达时用内置支持列表)。
     * 回调统一在主线程执行。
     */
    private fun resolveSoc(catalog: Catalog.CatalogData?, onResult: (String?) -> Unit) {
        val known = RoleManager.effectiveSoc()
        if (known.isNotEmpty()) {
            runOnUiThread { onResult(known) }
            return
        }
        val socList = if (catalog != null && catalog.devices.isNotEmpty())
            catalog.devices.keys.sorted()
        else
            listOf("sm8475", "sm8450", "sm8550", "sm8650", "sm8750", "sm8850")
        val items = socList.toTypedArray()
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("无法识别到您的 SoC")
                .setMessage("请手动选择您的手机 SoC；如果列表里没有，说明软件暂不支持您的设备。")
                .setItems(items) { _, which ->
                    val soc = items[which]
                    prefs.edit().putString("soc_override", soc).apply()
                    RoleManager.setManualSoc(soc)
                    onResult(soc)
                }
                .setNegativeButton("取消", { _, _ -> onResult(null) })
                .show()
        }
    }



    private fun openOnlineLibrary() {
        setupProgress.visibility = View.VISIBLE
        setupProgress.progress = 0
        setupStatus.text = "拉取在线目录…"
        Thread {
            val catalog = Catalog.fetch()
            if (catalog == null) {
                runOnUiThread {
                    setupStatus.text = "在线目录不可达 → 请检查网络"
                    setupProgress.visibility = View.GONE
                }
                log("在线目录不可达")
                return@Thread
            }
            val soc = RoleManager.effectiveSoc()
            if (soc.isNotEmpty()) {
                showOnlineLibrary(catalog, soc)
            } else {
                resolveSoc(catalog) { s ->
                    if (s == null) {
                        setupProgress.visibility = View.GONE
                        setupStatus.text = "未选择 SoC"
                    } else {
                        showOnlineLibrary(catalog, s)
                    }
                }
            }
        }.start()
    }

    private fun showOnlineLibrary(catalog: Catalog.CatalogData, soc: String) {
        val shared = Catalog.compatibleShared(catalog, soc)
        val roles = Catalog.compatibleRoles(catalog, soc)
        runOnUiThread {
            setupProgress.visibility = View.GONE
            val items = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            if (shared == null) {
                items.add("共享件:未找到本机($soc)可用包")
                actions.add {}
            } else {
                val ready = RoleManager.isSharedReady(filesDir)
                items.add(if (ready) "共享件:${shared.name} [已就绪]" else "共享件:${shared.name} [可下载]")
                actions.add { if (!ready) downloadSharedFromCatalog(shared) }
                val localVer = RoleManager.installedSharedVersion(filesDir)
                val localLabel = if (localVer == 0 && RoleManager.isSharedReady(filesDir)) "?" else "v$localVer"
                items.add("模型更新: 已装 $localLabel / 在线 v${shared.version}")
                actions.add { checkModelVersion(catalog, soc, true) }
            }
            if (roles.isEmpty()) {
                items.add("角色:暂无授权角色")
                actions.add {}
            } else {
                for ((role, file) in roles) {
                    items.add("角色:${role.name} (${file.size / 1048576}MB)")
                    actions.add { downloadRoleFromCatalog(role, file) }
                }
            }
            AlertDialog.Builder(this)
                .setTitle("在线角色库")
                .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun downloadSharedFromCatalog(entry: Catalog.SharedEntry) {
        if (entry.version < RoleManager.MIN_MODEL_VERSION) {
            runOnUiThread {
                setupStatus.text = "该共享件版本过低(v${entry.version} < v${RoleManager.MIN_MODEL_VERSION}),请升级 App"
            }
            log("共享件版本过低: v${entry.version} < v${RoleManager.MIN_MODEL_VERSION}")
            return
        }
        if (RoleManager.isSharedReady(filesDir) &&
            RoleManager.installedSharedVersion(filesDir) >= entry.version) {
            setupStatus.text = "已是最新(v${entry.version}),无需下载"
            log("共享件已是最新(v${entry.version}),跳过下载")
            return
        }
        setupProgress.visibility = View.VISIBLE
        setupProgress.progress = 0
        setupStatus.text = "探测共享件源…"
        Thread {
            val src = SharedDownloader.probeAndPick(entry.mirrors.map { SharedDownloader.Source(it.key, it.value) })
            if (src == null) {
                runOnUiThread {
                    setupStatus.text = "共享件源不可达 → 请用 SAF 导入"
                    setupProgress.visibility = View.GONE
                }
                return@Thread
            }
            val dest = File(filesDir, "shared.zip")
            runOnUiThread { setupStatus.text = "从 ${src.name} 下载共享件…" }
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
                    setupStatus.text = "共享件下载失败 → 请用 SAF 导入"
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
                    setupStatus.text = "共享件解压/校验失败: $e"
                    setupProgress.visibility = View.GONE
                }
                log("shared 解压失败: $e")
            }
        }.start()
    }

    private fun downloadRoleFromCatalog(role: Catalog.RoleEntry, file: Catalog.RoleFile) {
        if (file.mirrors.isEmpty()) {
            setupStatus.text = "角色 ${role.name} 没有可用镜像"
            return
        }
        setupProgress.visibility = View.VISIBLE
        setupProgress.progress = 0
        setupStatus.text = "探测角色源…"
        Thread {
            val src = SharedDownloader.probeAndPick(file.mirrors.map { SharedDownloader.Source(it.key, it.value) })
            if (src == null) {
                runOnUiThread {
                    setupStatus.text = "角色源不可达"
                    setupProgress.visibility = View.GONE
                }
                return@Thread
            }
            val dest = File(filesDir, "role_${role.id}.zip")
            runOnUiThread { setupStatus.text = "从 ${src.name} 下载角色 ${role.name}…" }
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
                    setupStatus.text = "角色下载失败"
                    setupProgress.visibility = View.GONE
                }
                return@Thread
            }
            try {
                RoleManager.importZipFile(dest, filesDir)
                dest.delete()
                runOnUiThread {
                    refreshRoles()
                    setupStatus.text = "角色 ${role.name} 安装完成 ✔"
                    setupProgress.visibility = View.GONE
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    setupStatus.text = "角色解压/校验失败: $e"
                    setupProgress.visibility = View.GONE
                }
                log("角色安装失败: $e")
            }
        }.start()
    }

    private fun log(s: String) {
        runOnUiThread { logView.append(s + "\n") }
        android.util.Log.i("RVC", s)
    }
}
