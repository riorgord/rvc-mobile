package com.rvc.app

import android.content.Context
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 动态作用域:LSPosed 框架 API 102 的 XposedService。
 *
 * App 进程(com.rvc.app)通过 [XposedServiceHelper] 连上框架的服务,
 * 调用 [XposedService.requestScope] 让 LSPosed 弹出授权,批准后目标包
 * 自动进入模块作用域,无需用户进管理器手动勾选。
 *
 * 底层仍是 per-app 作用域(LSPosed 没有真全局),但体验上"点一下就生效"。
 */
sealed interface ScopeRequestResult {
    data class Granted(val packageName: String) : ScopeRequestResult
    data class Failed(val packageName: String, val error: String) : ScopeRequestResult
    data class Unsupported(val packageName: String) : ScopeRequestResult
}

object LsposedScopeManager : XposedServiceHelper.OnServiceListener {

    private const val TAG = "LsposedScope"

    @Volatile private var xposedService: XposedService? = null
    @Volatile var isBound: Boolean = false
        private set
    @Volatile var frameworkScope: List<String>? = null
        private set

    @Volatile private var onBindChange: ((Boolean) -> Unit)? = null

    /** 从 Context 触发的惰性注册(必须用 ApplicationContext 防止泄漏) */
    fun ensureRegistered(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
        }
        runCatching {
            XposedServiceHelper.registerListener(this)
        }.onFailure {
            Log.w(TAG, "registerListener failed: ${it.message}")
        }
    }

    private var registered = false

    fun setOnBindChange(listener: ((Boolean) -> Unit)?) {
        onBindChange = listener
        listener?.invoke(isBound)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        isBound = true
        frameworkScope = runCatching { service.scope }.getOrNull()
        Log.i(TAG, "bound, api=${service.apiVersion}, scope=$frameworkScope")
        onBindChange?.invoke(true)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
            isBound = false
            frameworkScope = null
            Log.i(TAG, "died")
            onBindChange?.invoke(false)
        }
    }

    /** 主动拉取当前作用域 */
    fun syncScope(): List<String>? {
        val s = runCatching { xposedService?.scope }.getOrNull()
        frameworkScope = s
        return s
    }

    /** 向 LSPosed 申请把 [packageName] 加入模块作用域(会弹授权) */
    fun requestScope(packageName: String, onResult: (ScopeRequestResult) -> Unit) {
        val service = xposedService
        if (service == null) {
            onResult(ScopeRequestResult.Unsupported(packageName))
            return
        }
        runCatching {
            service.requestScope(listOf(packageName), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(packages: List<String>) {
                    Log.i(TAG, "approved: $packages")
                    syncScope()
                    onResult(ScopeRequestResult.Granted(packageName))
                }

                override fun onScopeRequestFailed(message: String) {
                    Log.w(TAG, "requestScope failed: $message")
                    onResult(ScopeRequestResult.Failed(packageName, message))
                }
            })
        }.onFailure {
            Log.w(TAG, "requestScope threw: ${it.message}")
            onResult(ScopeRequestResult.Failed(packageName, it.message ?: "Unknown error"))
        }
    }
}
