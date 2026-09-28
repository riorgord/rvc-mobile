package com.rvc.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * P2:Compose 新 UI 的 ViewModel。
 *
 * 持有 RvcCore(纯业务,applicationContext,无泄漏),把 UI 需要的状态
 * 暴露为 StateFlow;动作方法内部开后台线程调 RvcCore/RoleManager/Catalog。
 *
 * 生命周期:AndroidViewModel(持 Application,随 Activity 销毁重建)。
 */
class RvcViewModel(application: Application) : AndroidViewModel(application) {

    val core = RvcCore(application)

    // ---------------- 角色(① 转化脚本角色:本地已导入) ----------------

    private val _roles = MutableStateFlow<List<RoleInfo>>(emptyList())
    val roles: StateFlow<List<RoleInfo>> = _roles.asStateFlow()

    private val _currentRoleId = MutableStateFlow<String?>(null)
    val currentRoleId: StateFlow<String?> = _currentRoleId.asStateFlow()

    /** SAF 导入后的回调结果(成功/失败消息,UI 弹 toast/日志)。 */
    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    // ---------------- 自己的角色库(② 对接 catalog.roles) ----------------

    private val _catalogRoles = MutableStateFlow<List<Catalog.RoleEntry>>(emptyList())
    val catalogRoles: StateFlow<List<Catalog.RoleEntry>> = _catalogRoles.asStateFlow()

    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()

    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    init {
        refreshRoles()
        refreshCatalogRoles()
    }

    // ---------------- 动作 ----------------

    /** 重扫本地已导入角色 + 当前选中。 */
    fun refreshRoles() {
        _roles.value = RoleManager.scanRoles(core.filesDir)
        _currentRoleId.value = RoleManager.currentRoleId(core.prefs)
    }

    /** 切换当前角色。若桥接运行中:自动停止并提示需重新开启变声(旧 UI 同逻辑)。 */
    fun setCurrentRole(id: String) {
        RoleManager.setCurrentRole(core.prefs, id)
        _currentRoleId.value = id
        if (HalRvcBridge.isActive()) {
            HalRvcBridge.stop()
            _halActive.value = false
            _notice.value = "角色已切换,变声已停止,请重新开启变声"
        }
    }

    // ---------------- 一次性提示(跨页全局,如首页/模型包共用) ----------------

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 发一条一次性提示(UI 展示后调 consumeNotice 清除)。 */
    fun postNotice(msg: String) {
        _notice.value = msg
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /** SAF 导入角色包(content:// uri)。成功/失败经 importResult 回调。 */
    fun importRole(uri: Uri) {
        viewModelScope.launch {
            _importResult.value = null
            val msg = try {
                val id = withContext(Dispatchers.IO) {
                    RoleManager.importUri(getApplication(), uri, core.filesDir)
                }
                refreshRoles()
                "角色导入成功: $id"
            } catch (e: Throwable) {
                "角色导入失败: ${e.message ?: e}"
            }
            _importResult.value = msg
        }
    }

    /** 拉取 catalog.roles(自己的角色库)。 */
    fun refreshCatalogRoles() {
        if (_catalogLoading.value) return
        _catalogLoading.value = true
        _catalogError.value = null
        viewModelScope.launch {
            val (roles, err) = withContext(Dispatchers.IO) {
                try {
                    val c = Catalog.fetch()
                    if (c == null) emptyList<Catalog.RoleEntry>() to "在线目录不可达"
                    else c.roles to null
                } catch (e: Throwable) {
                    emptyList<Catalog.RoleEntry>() to (e.message ?: "拉取失败")
                }
            }
            _catalogRoles.value = roles
            _catalogError.value = err
            _catalogLoading.value = false
        }
    }

    /** 清除 importResult(UI 消费后调用,防止重复 toast)。 */
    fun consumeImportResult() {
        _importResult.value = null
    }

    // ---------------- 在线库(共享件 + 在线角色) ----------------

    /** 共享件状态:已就绪/缺失 + 已装版本。 */
    data class SharedStatus(val ready: Boolean, val version: Int)

    private val _sharedStatus = MutableStateFlow(SharedStatus(false, 0))
    val sharedStatus: StateFlow<SharedStatus> = _sharedStatus.asStateFlow()

    private val _downloadState = MutableStateFlow<String?>(null)   // 状态文案
    private val _downloadProgress = MutableStateFlow(0)            // 0..100
    private val _downloading = MutableStateFlow(false)
    val downloadState: StateFlow<String?> = _downloadState.asStateFlow()
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    /** SAF 导入 shared.zip 后的结果消息。 */
    private val _sharedImportResult = MutableStateFlow<String?>(null)
    val sharedImportResult: StateFlow<String?> = _sharedImportResult.asStateFlow()

    /** 在线授权角色(compatibleRoles:catalog 里 permission=authorized 且匹配本机 SoC)。 */
    private val _onlineRoles = MutableStateFlow<List<Pair<Catalog.RoleEntry, Catalog.RoleFile>>>(emptyList())
    val onlineRoles: StateFlow<List<Pair<Catalog.RoleEntry, Catalog.RoleFile>>> = _onlineRoles.asStateFlow()

    fun refreshSharedStatus() {
        _sharedStatus.value = SharedStatus(
            ready = RoleManager.isSharedReady(core.filesDir),
            version = RoleManager.installedSharedVersion(core.filesDir),
        )
    }

    /** 从 catalog 下载共享件(自动选源,带进度)。 */
    fun downloadSharedFromCatalog() {
        if (_downloading.value) return
        _downloading.value = true
        _downloadProgress.value = 0
        _downloadState.value = "拉取目录并确认 SoC…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val catalog = Catalog.fetch()
                        ?: return@withContext "在线目录不可达 → 请用 SAF 导入 shared.zip"
                    val soc = RoleManager.effectiveSoc()
                    if (soc.isEmpty()) return@withContext "未识别到 SoC → 请用 SAF 导入 shared.zip"
                    val shared = Catalog.compatibleShared(catalog, soc)
                        ?: return@withContext "软件暂不支持 $soc(目录里没有对应共享件)"
                    if (shared.version < RoleManager.MIN_MODEL_VERSION) {
                        return@withContext "该共享件版本过低(v${shared.version} < v${RoleManager.MIN_MODEL_VERSION}),请升级 App"
                    }
                    if (RoleManager.isSharedReady(core.filesDir) &&
                        RoleManager.installedSharedVersion(core.filesDir) >= shared.version
                    ) {
                        return@withContext "共享件已是最新(v${shared.version}),无需下载"
                    }
                    val src = SharedDownloader.probeAndPick(
                        shared.mirrors.map { SharedDownloader.Source(it.key, it.value) }
                    ) ?: return@withContext "共享件源不可达 → 请用 SAF 导入"
                    val dest = File(core.filesDir, "shared.zip")
                    val ok = SharedDownloader.download(src, dest) { done, total ->
                        val pct = if (total != null && total > 0) ((done * 100) / total).toInt() else 0
                        _downloadProgress.value = pct
                        _downloadState.value = "下载 ${done / 1048576}MB / ${(total ?: 0) / 1048576}MB"
                    }
                    if (!ok) return@withContext "共享件下载失败 → 请用 SAF 导入"
                    try {
                        RoleManager.importZipFile(dest, core.filesDir)
                        dest.delete()
                        "共享件下载+解压完成 ✔"
                    } catch (e: Throwable) {
                        "共享件解压/校验失败: ${e.message ?: e}"
                    }
                } catch (e: Throwable) {
                    "下载失败: ${e.message ?: e}"
                }
            }
            _downloadState.value = result
            _downloading.value = false
            refreshSharedStatus()
        }
    }

    /** SAF 导入 shared.zip(content:// uri)。 */
    fun importShared(uri: Uri) {
        viewModelScope.launch {
            _sharedImportResult.value = null
            val msg = try {
                withContext(Dispatchers.IO) {
                    RoleManager.importUri(getApplication(), uri, core.filesDir)
                }
                refreshSharedStatus()
                "共享件导入成功"
            } catch (e: Throwable) {
                "共享件导入失败: ${e.message ?: e}"
            }
            _sharedImportResult.value = msg
        }
    }

    fun consumeSharedImportResult() {
        _sharedImportResult.value = null
    }

    /** 拉取在线授权角色(catalog.roles 中 permission=authorized 且匹配本机 SoC)。 */
    fun refreshOnlineRoles() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val c = Catalog.fetch()
                    if (c != null) {
                        _onlineRoles.value = Catalog.compatibleRoles(c, RoleManager.effectiveSoc())
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** 从 catalog 下载在线角色(自动选源)。 */
    fun downloadOnlineRole(role: Catalog.RoleEntry, file: Catalog.RoleFile) {
        if (_downloading.value) return
        _downloading.value = true
        _downloadProgress.value = 0
        _downloadState.value = "探测角色源…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (file.mirrors.isEmpty()) return@withContext "角色 ${role.name} 没有可用镜像"
                    val src = SharedDownloader.probeAndPick(
                        file.mirrors.map { SharedDownloader.Source(it.key, it.value) }
                    ) ?: return@withContext "角色源不可达"
                    val dest = File(core.filesDir, "role_${role.id}.zip")
                    val ok = SharedDownloader.download(src, dest) { done, total ->
                        val pct = if (total != null && total > 0) ((done * 100) / total).toInt() else 0
                        _downloadProgress.value = pct
                        _downloadState.value = "下载 ${done / 1048576}MB / ${(total ?: 0) / 1048576}MB"
                    }
                    if (!ok) return@withContext "角色下载失败"
                    try {
                        RoleManager.importZipFile(dest, core.filesDir)
                        dest.delete()
                        refreshRoles()
                        "角色 ${role.name} 安装完成 ✔"
                    } catch (e: Throwable) {
                        "角色解压/校验失败: ${e.message ?: e}"
                    }
                } catch (e: Throwable) {
                    "下载失败: ${e.message ?: e}"
                }
            }
            _downloadState.value = result
            _downloading.value = false
        }
    }

    // ---------------- 设置(HAL / SoC) ----------------

    private val _halActive = MutableStateFlow(false)
    val halActive: StateFlow<Boolean> = _halActive.asStateFlow()

    /** 设备检测结果(安装 HAL 模块前的档位判定)。null = 未检测。 */
    private val _guard = MutableStateFlow<DeviceGuard.Result?>(null)
    val guard: StateFlow<DeviceGuard.Result?> = _guard.asStateFlow()

    /** 模块安装流程状态文案(操作记录,UI 展示)。 */
    private val _installLog = MutableStateFlow<String?>(null)
    val installLog: StateFlow<String?> = _installLog.asStateFlow()

    /** 是否已提交安装等待重启。 */
    private val _installPendingReboot = MutableStateFlow(false)
    val installPendingReboot: StateFlow<Boolean> = _installPendingReboot.asStateFlow()

    private val _soc = MutableStateFlow<String?>(null)
    val soc: StateFlow<String?> = _soc.asStateFlow()

    fun refreshHalState() {
        // 开关反映"桥接是否正在运行",而非"模块是否已安装"
        _halActive.value = HalRvcBridge.isActive()
        _soc.value = RoleManager.effectiveSoc().ifEmpty { null }
    }

    /** HAL 桥接开关。启动时用当前角色 + 默认参数(P2.5 调试页可改参数后重启桥接)。 */
    fun setHalActive(active: Boolean) {
        android.util.Log.d("RvcVM", "setHalActive($active) bridgeActive=${HalRvcBridge.isActive()}")
        if (active == HalRvcBridge.isActive()) {
            _halActive.value = active
            return
        }
        viewModelScope.launch {
            if (!active) {
                withContext(Dispatchers.IO) { HalRvcBridge.stop() }
                _halActive.value = false
            } else {
                if (!core.isHalModuleActive()) {
                    android.util.Log.w("RvcVM", "HAL module not active")
                    _installLog.value = "需要 root + 安装 RVC HAL 模块(ro.hardware.audio.primary 非 rvc),无法启动变声"
                    _halActive.value = false
                    return@launch
                }
                val roleDir = RoleManager.currentRoleDir(core.filesDir, core.prefs)
                withContext(Dispatchers.IO) {
                    HalRvcBridge.start(getApplication(), 0, 0.75f, 0.75f, 0.33f, roleDir)
                }
                _halActive.value = HalRvcBridge.isActive()
            }
        }
    }

    /** 设备兼容性检测(DeviceGuard.evaluate,后台线程)。 */
    fun runDeviceCheck() {
        viewModelScope.launch {
            _installLog.value = "① 设备兼容性检测…"
            val guard = withContext(Dispatchers.IO) { DeviceGuard.evaluate() }
            _guard.value = guard
            _installLog.value = "检测结果: tier=${guard.tier} root=${guard.rootType} " +
                "kernel_official=${guard.checks[1].pass} audio_primary=${guard.checks[2].pass} " +
                "sdk=${guard.checks[3].detail}"
        }
    }

    /** 安装/更新 HAL 模块(后台线程;档位 RED/YELLOW 由 UI 层弹窗处理)。 */
    fun installHalModule() {
        viewModelScope.launch {
            _installLog.value = "① 设备兼容性检测…"
            val guard = withContext(Dispatchers.IO) { DeviceGuard.evaluate() }
            _guard.value = guard
            when (guard.tier) {
                DeviceGuard.Tier.RED -> _installLog.value = "✗ 设备不满足机架运行条件,已拦截安装"
                DeviceGuard.Tier.YELLOW -> _installLog.value = "⚠ 设备条件有风险,需用户确认后放行"
                DeviceGuard.Tier.GREEN -> doInstallHalModule()
            }
        }
    }

    /** YELLOW 弹窗确认后继续安装。 */
    fun confirmInstallHalModule() {
        doInstallHalModule()
    }

    /** 手动选择本机 SoC(设置页弹窗)。null = 恢复自动检测。 */
    fun setSocOverride(soc: String?) {
        if (soc == null) {
            core.prefs.edit().remove("soc_override").apply()
        } else {
            core.prefs.edit().putString("soc_override", soc).apply()
        }
        RoleManager.setManualSoc(soc)
        _soc.value = RoleManager.effectiveSoc().ifEmpty { null }
    }

    private fun doInstallHalModule() {
        viewModelScope.launch {
            val log = withContext(Dispatchers.IO) {
                val sb = StringBuilder("✓ 用户已确认风险,继续安装\n")
                sb.append("① 检查 root…\n")
                if (!core.hasRoot()) {
                    return@withContext "✗ 未获得 root 授权:请先在 Magisk/KernelSU 中允许本应用"
                }
                sb.append("✓ root 正常\n② 探测音频 HAL 方案…\n")
                val scheme = core.detectHalScheme()
                sb.append("音频 HAL 方案: $scheme\n")
                if (scheme != "LEGACY" && scheme != "HIDL") {
                    return@withContext "✗ 此设备为 $scheme 音频 HAL,不加载 audio.primary.*.so,已拦截安装"
                }
                sb.append("✓ 方案可用,继续\n")
                val cur = core.moduleInstalledVersion()
                sb.append(if (cur != null) "当前模块版本: $cur\n" else "未检测到已装模块\n")
                sb.append("② 解出内置模块包…\n")
                val zip = core.copyBundledModuleZip()
                if (zip == null) return@withContext "✗ 内置模块包读取失败"
                sb.append("✓ 已解出: ${zip.absolutePath}\n③ 执行 magisk --install-module …\n")
                var (rc, out) = core.runSu("magisk --install-module \"${zip.absolutePath}\"")
                if (rc == 0) {
                    sb.append("✓ Magisk 安装成功\n")
                } else {
                    sb.append("magisk 失败(rc=$rc),试 ksud module install …\n$out\n")
                    val (rc2, out2) = core.runSu("/data/adb/ksud module install \"${zip.absolutePath}\"")
                    if (rc2 == 0) {
                        rc = 0
                        sb.append("✓ KernelSU 安装成功\n")
                    } else {
                        sb.append("✗ KernelSU 也失败(rc=$rc2)\n$out2\n")
                    }
                }
                if (rc != 0) return@withContext "✗ 自动安装失败,请用 Magisk 应用手动刷 rvc_module.zip"
                sb.append("④ 安装/更新已提交,重启后生效\n")
                sb.toString()
            }
            _installLog.value = log
            if (log.contains("④ 安装/更新已提交")) {
                _installPendingReboot.value = true
            }
        }
    }

    /** 立即重启(安装 HAL 模块后)。 */
    fun rebootNow() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { core.runSu("reboot") }
        }
    }

    /** 清除安装结果状态(UI 消费后)。 */
    fun consumeInstallState() {
        _installLog.value = null
        _installPendingReboot.value = false
    }

    // ---------------- 调试页(P2.5) ----------------

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    /** 调试日志:追加一行(带时间戳),cap 200 行。线程安全(StateFlow.update)。 */
    fun debugLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _logLines.update { (it + "[$ts] $msg").takeLast(200) }
    }

    /** 清空调试日志。 */
    fun clearLog() {
        _logLines.value = emptyList()
    }

    // 调试参数(会话级,与旧 UI 默认一致;不持久化)
    private val _debugProfile = MutableStateFlow(false)
    val debugProfile: StateFlow<Boolean> = _debugProfile.asStateFlow()
    private val _debugKey = MutableStateFlow("0")
    val debugKey: StateFlow<String> = _debugKey.asStateFlow()
    private val _debugRms = MutableStateFlow("0.75")
    val debugRms: StateFlow<String> = _debugRms.asStateFlow()
    private val _debugIdx = MutableStateFlow("0.5")
    val debugIdx: StateFlow<String> = _debugIdx.asStateFlow()
    private val _debugProt = MutableStateFlow("0.4")
    val debugProt: StateFlow<String> = _debugProt.asStateFlow()
    private val _debugF0 = MutableStateFlow("fcpe")
    val debugF0: StateFlow<String> = _debugF0.asStateFlow()
    private val _debugBrightness = MutableStateFlow(0)
    val debugBrightness: StateFlow<Int> = _debugBrightness.asStateFlow()

    // 实测延迟(prefs 持久化,杀后台不丢)
    private val _latencyMs = MutableStateFlow<String?>(null)
    val latencyMs: StateFlow<String?> = _latencyMs.asStateFlow()

    fun setDebugProfile(v: Boolean) { _debugProfile.value = v }
    fun setDebugKey(v: String) { _debugKey.value = v }
    fun setDebugRms(v: String) { _debugRms.value = v }
    fun setDebugIdx(v: String) { _debugIdx.value = v }
    fun setDebugProt(v: String) { _debugProt.value = v }
    fun setDebugF0(v: String) { _debugF0.value = v }
    fun setDebugBrightness(v: Int) { _debugBrightness.value = v }

    /** 调试页打开时:恢复上次实测延迟。 */
    fun refreshLatency() {
        _latencyMs.value = core.prefs.getString("latency_measured_ms", null)
    }

    // F0 提取器加载去重(fcpe/rmvpe/gf_ref)
    private val f0Loading = mutableMapOf<String, Boolean>()
    private val f0Loaded = mutableMapOf<String, Boolean>()

    fun ensureF0(m: String) {
        if (f0Loaded[m] == true || f0Loading[m] == true) return
        f0Loading[m] = true
        debugLog("加载 F0 提取器 $m …")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.pyInitF0(m) }
                f0Loaded[m] = true
                debugLog("F0 $m 已就绪")
            } catch (e: Throwable) {
                f0Loaded.remove(m)
                debugLog("F0 加载失败: $e")
            } finally {
                f0Loading[m] = false
            }
        }
    }

    /** 解析调试参数面板(非法输入回默认值)。 */
    private data class DebugParams(val key: Int, val rms: Float, val idx: Float, val prot: Float)

    private fun params(): DebugParams = DebugParams(
        key = _debugKey.value.toIntOrNull() ?: 0,
        rms = _debugRms.value.toFloatOrNull() ?: 0.75f,
        idx = _debugIdx.value.toFloatOrNull() ?: 0.5f,
        prot = _debugProt.value.toFloatOrNull() ?: 0.4f,
    )

    /** 通用后台跑 Python 自检:日志前缀 + 结果标签。 */
    private fun runPy(tag: String, resTag: String, block: () -> String) {
        debugLog("$tag (profile=${_debugProfile.value})…")
        viewModelScope.launch {
            try {
                debugLog("$resTag: " + withContext(Dispatchers.IO) { block() })
            } catch (e: Throwable) {
                debugLog("PY FAIL: $e")
            }
        }
    }

    /** 设备兼容性检测(调试页版,结果进日志流)。 */
    fun runDeviceCheckDebug() {
        debugLog("① 设备兼容性检测…")
        viewModelScope.launch {
            val guard = withContext(Dispatchers.IO) { DeviceGuard.evaluate() }
            debugLog("检测结果: tier=${guard.tier} root=${guard.rootType} " +
                "kernel_official=${guard.checks[1].pass} audio_primary=${guard.checks[2].pass} sdk=${guard.checks[3].detail}")
            debugLog("内核: ${guard.kernelVersion.take(120)}")
        }
    }

    /** 3 模型全检。 */
    fun runAllModels() = runPy("3 模型全检", "RESULT_ALL") { core.pySelfTestAll(_debugProfile.value) }

    /** 全链路自检。 */
    fun runFullChain() = runPy("全链路", "RESULT_FULL") { core.pySelfTestFull(_debugProfile.value) }

    /** ② 拆分测速(z_producer + dec_short 滑窗)。 */
    fun runRoute2() = runPy("② 拆分测速", "RESULT_ROUTE2") { core.pySelfTestRoute2(_debugProfile.value) }

    /** iSTFT dec 块级流式自测。 */
    fun runRoute2Istft() {
        val f0m = _debugF0.value
        val br = _debugBrightness.value / 100f
        runPy("iSTFT 拆分测速 (f0=$f0m b=$br)", "RESULT_ROUTE2_ISTFT") {
            core.pySelfTestRoute2Istft(_debugProfile.value, f0m, br)
        }
    }

    /** M2 内存链路验证(process_audio vs PC 参考)。 */
    fun runLiveIo() = runPy("内存链路验证", "RESULT_LIVE") { core.pySelfTestLiveIo(_debugProfile.value) }

    /** 模拟实时:参考音频 → 实时链路 → AudioTrack 播放(40k)。 */
    fun runSim() {
        val p = params()
        val f0m = _debugF0.value
        debugLog("模拟实时:参考音频→实时链路→播放 key=%d rms=%.2f idx=%.2f prot=%.2f f0=%s"
            .format(p.key, p.rms, p.idx, p.prot, f0m))
        viewModelScope.launch {
            try {
                val outBytes = withContext(Dispatchers.IO) {
                    core.pyProcessStreamV2Ref(_debugProfile.value, p.key, p.rms, p.idx, p.prot, f0m)
                }
                val outF = FloatArray(outBytes.size / 4)
                java.nio.ByteBuffer.wrap(outBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer().get(outF)
                playFloatPcm(outF)
                debugLog("模拟播放 %d samples @40k (%.2fs)".format(outF.size, outF.size / 40000.0))
            } catch (e: Throwable) {
                debugLog("SIM FAIL: $e")
            }
        }
    }

    /** M2 实时:录音 2.24s(16k)→ 变声 → AudioTrack 播放(40k)。调用前需已授权麦克风。 */
    fun runLive() {
        val p = params()
        val f0m = _debugF0.value
        debugLog("实时:录音→变声→播放 key=%d rms=%.2f idx=%.2f prot=%.2f f0=%s"
            .format(p.key, p.rms, p.idx, p.prot, f0m))
        viewModelScope.launch {
            try {
                val outBytes = withContext(Dispatchers.IO) {
                    val sr = 16000
                    val n = 35840   // 2.24s
                    val minBuf = android.media.AudioRecord.getMinBufferSize(
                        sr, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_FLOAT)
                    val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, sr,
                        android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_FLOAT,
                        minBuf * 2)
                    if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
                        return@withContext ByteArray(0)
                    }
                    rec.startRecording()
                    val buf = FloatArray(n)
                    var rd = 0
                    while (rd < n) {
                        val r = rec.read(buf, rd, n - rd, android.media.AudioRecord.READ_BLOCKING)
                        if (r > 0) rd += r else break
                    }
                    rec.stop(); rec.release()
                    debugLog("录音完成 $rd samples")
                    if (rd < n) return@withContext ByteArray(0)
                    val inBytes = ByteArray(n * 4)
                    java.nio.ByteBuffer.wrap(inBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer().put(buf)
                    core.pyProcessAudio(_debugProfile.value, inBytes, p.key, p.rms, p.idx, p.prot, f0m)
                }
                if (outBytes.isEmpty()) {
                    debugLog("LIVE FAIL: 录音不足或处理失败")
                    return@launch
                }
                val outF = FloatArray(outBytes.size / 4)
                java.nio.ByteBuffer.wrap(outBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer().get(outF)
                playFloatPcm(outF)
                debugLog("变声播放 %d samples @40k (%.2fs)".format(outF.size, outF.size / 40000.0))
            } catch (e: Throwable) {
                debugLog("LIVE FAIL: $e")
            }
        }
    }

    /** 播放 40k float PCM(AudioTrack MODE_STATIC)。 */
    private fun playFloatPcm(outF: FloatArray) {
        val tr = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(android.media.AudioFormat.Builder()
                .setSampleRate(40000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(outF.size * 4)
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .build()
        tr.write(outF, 0, outF.size, android.media.AudioTrack.WRITE_BLOCKING)
        tr.play()
    }

    /** 延迟自测:合成音频推入测稳态 T_proc,结果持久化。
     * 测延迟固定 idx=0(纯 NPU T_proc,不依赖索引资源)。 */
    fun runLatencyTest() {
        val p = params()
        debugLog("测延迟 (key=${p.key} rms=%.2f idx=0 prot=%.2f)…".format(p.rms, p.prot))
        viewModelScope.launch {
            try {
                val ms = withContext(Dispatchers.IO) {
                    core.pyStreamCreate(_debugProfile.value, p.key, p.rms, 0f, p.prot, 64, 12)
                    core.pyStreamMeasureLatency()
                }
                val s = "%.0f".format(ms)
                core.prefs.edit().putString("latency_measured_ms", s).apply()
                _latencyMs.value = s
                val thermal = core.readThermalSummary()
                debugLog("实测延迟 = $s ms (块时长预算 370ms, 实时需 <=370) | $thermal")
            } catch (e: Throwable) {
                debugLog("测延迟失败: $e")
            }
        }
    }
}
