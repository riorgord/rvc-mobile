package io.mo.glassmic.xposed

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.mo.glassmic.core.Constants

/**
 * libxposed API 101 入口。
 *
 * 关键设计：**zygote 级 hook**。
 *
 * 我们在 [onModuleLoaded] 阶段（zygote 进程）就 hook 住 `Application.attach`。
 * zygote 之后 fork 出来的每一个 App 进程会通过 COW 内存继承这个 hook——
 * 因此即使用户没在 LSPosed 管理器里勾选某个 App，只要勾选了「android」（= zygote），
 * 那个 App 启动时也会跑我们的 hook 回调，从而装上 AudioRecord 拦截。
 *
 * 代价：一个 hook bug 会让所有 App 一起出问题。所以下面有一份"保护进程"列表，
 * 关键系统进程（systemui、launcher、settings、GMS 等）直接跳过，绝不动它们。
 *
 * P0 说明：native AAudio hook 与音量键快捷操作暂未移植，仅保留 Java AudioRecord hook
 * 与 system_server 的包可见性放行。
 */
class GlassMicXposedModule : XposedModule() {

    private companion object {
        const val TAG = "GlassMic-X"
        const val SELF_PKG = Constants.APP_PACKAGE
    }

    @Volatile private var attachHookInstalled = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "module loaded process=${param.processName} system=${param.isSystemServer} api=$apiVersion framework=$frameworkName/$frameworkVersion"
        )
        // zygote 阶段安装 Application.attach hook——所有 fork 出来的 App 自动继承
        // 即使在 non-zygote 进程里调用，hook 也是 method 级的，全局生效
        installApplicationAttachHook()
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log(Log.INFO, TAG, "loaded in system_server")
        // 跨进程包可见性放行：Android 11+（尤其是 targetSdk >= 30 的应用如 Telegram）严格执行包可见性过滤。
        // 若不放行，被注入的第三方 App 无法通过 ContentResolver 访问 RuntimeProvider / PcmStreamProvider，
        // 导致决策永远 fallback 到 REAL_MIC。放行仅针对本包 (com.rvc.app)，不影响任何其他包。
        runCatching {
            val ok = SystemVisibilityHook.install(this, param.classLoader)
            log(Log.INFO, TAG, "visibility compat allowlist hook installed=$ok")
        }.onFailure {
            log(Log.WARN, TAG, "visibility compat init error: ${it.message}", it)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 现在用 zygote 继承路径，per-package 回调只用来记日志
        val pkg = param.packageName ?: return
        if (pkg == SELF_PKG) return
        log(Log.INFO, TAG, "onPackageReady $pkg firstPackage=${param.isFirstPackage}")
    }

    private fun installApplicationAttachHook() {
        if (attachHookInstalled) return
        synchronized(this) {
            if (attachHookInstalled) return
            attachHookInstalled = true
        }

        val attach = runCatching {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        }.onFailure {
            log(Log.WARN, TAG, "find Application.attach failed: ${it.message}", it)
        }.getOrNull() ?: return

        hook(attach)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val appCtx = chain.getArg(0) as? Context ?: return@intercept result

                val pkg = runCatching { appCtx.packageName ?: "" }.getOrDefault("")
                if (XposedHookGate.shouldSkipPackage(pkg)) {
                    return@intercept result
                }

                // 真正的 audio hook 安装在 App 进程上下文里
                val ctx = appCtx.applicationContext ?: appCtx
                runCatching {
                    XBridge.currentPackage = pkg
                    XBridge.apiVersion = 101
                    // 注意：这里**不再**主动 pingModuleLoaded / 发起任何 ContentResolver 调用。
                    // 之前每个 App 启动都 ping 一次 RuntimeProvider，是「被杀又秒复活」的主要来源之一。
                    // 「模块已激活」的诊断状态改由 RuntimeProvider.query 在真正被查询时更新。
                    AudioRecordHook.install(this@GlassMicXposedModule, ctx, pkg)
                    // native AAudio/OpenSL/AudioRecord hook(录音机/微信/QQ 多走 native 路径)
                    val modAi = getModuleApplicationInfo()
                    NativeAAudioHook.install(ctx, pkg, modAi.nativeLibraryDir, modAi.sourceDir)
                    log(Log.INFO, TAG, "hooks installed in $pkg")
                }.onFailure {
                    log(Log.WARN, TAG, "install hooks in $pkg failed: ${it.message}", it)
                }
                result
            }
        log(Log.INFO, TAG, "Application.attach hook installed (zygote-level)")
    }
}
