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
}
