package com.rvc.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** 切换当前角色(HAL 运行中时由 UI 层提示需重启 HAL)。 */
    fun setCurrentRole(id: String) {
        RoleManager.setCurrentRole(core.prefs, id)
        _currentRoleId.value = id
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
}
