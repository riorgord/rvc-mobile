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
}
