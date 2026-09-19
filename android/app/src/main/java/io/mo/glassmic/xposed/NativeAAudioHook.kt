package io.mo.glassmic.xposed

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.bytedance.shadowhook.ShadowHook
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SourceType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AAudio 原生 hook 入口。
 *
 * 调用顺序：
 *   1. install(ctx, pkg)  ——
 *      a) ShadowHook.init（加载 libshadowhook.so）
 *      b) System.loadLibrary("glassmic_native")
 *      c) nativeInstall 安装 AAudioStream_read hook
 *      d) 启动一个低频轮询线程，250ms 一次：
 *         - 通过 RuntimeProvider 拿当前 Decision（REAL_MIC / FILE / SILENCE）
 *         - Decision=FILE 时确保已为该 Stream 打开 PCM pipe fd，并下传给 native
 *         - 把 native 累积的劫持次数/字节通过 RuntimeProvider 上报
 *
 * 设计要点：
 * - audio thread 完全不调 Java，只读 native 端原子；避免 RT 线程跨 JNI/Binder 引发的卡顿
 * - 上报统计也走轮询线程，audio thread 只 atomic.add
 */
object NativeAAudioHook {

    private const val TAG = "GlassMic-NativeAAudio"
    private val installed = AtomicBoolean(false)
    @Volatile private var pollerStarted = false
    // 我们 detach fd 后 PFD 句柄就没了，只能用这两个值标记"native 端已经持有过哪种配置的 fd"
    @Volatile private var pushedFdSr: Int = 0
    @Volatile private var pushedFdCh: Int = 0
    @Volatile private var hasPushedFd: Boolean = false

    fun install(ctx: Context, callerPackage: String, moduleNativeLibDir: String? = null, moduleApkPath: String? = null): Boolean {
        if (!installed.compareAndSet(false, true)) return true

        // 1. shadowhook 初始化（含 dlopen 自身 .so）
        // 必须和 glassmic_native 用"同一份" shadowhook:
        // 我们只能用绝对路径从解压根目录加载 glassmic_native,如果 shadowhook 用默认的
        // System.loadLibrary(APK zip 里的另一份)加载,glassmic_native 链接到的就是没初始化
        // 的副本(err=2 Not initialized)。所以这里也用自定义 ILibLoader 从同一目录加载。
        val nativeLibDir = resolveModuleNativeLibDir(moduleNativeLibDir, moduleApkPath)
        val initOk = runCatching {
            val builder = ShadowHook.ConfigBuilder()
                .setMode(ShadowHook.Mode.UNIQUE)
                .setDebuggable(false)
            if (nativeLibDir != null && java.io.File("$nativeLibDir/libshadowhook.so").exists()) {
                val dir = nativeLibDir
                builder.setLibLoader { libName -> System.load("$dir/lib$libName.so") }
            }
            ShadowHook.init(builder.build())
            true
        }.onFailure { android.util.Log.e(TAG, "ShadowHook.init failed: ${it.message}", it) }
            .getOrDefault(false)

        if (!initOk) {
            installed.set(false)
            return false
        }

        // 2. 加载我们自己的 native lib
        val libOk = loadNativeLibrary(ctx, moduleNativeLibDir, moduleApkPath)
        if (!libOk) {
            installed.set(false)
            return false
        }

        // 3. 装 hook
        val rc = runCatching { nativeInstall() }
            .onFailure { android.util.Log.e(TAG, "nativeInstall throw: ${it.message}", it) }
            .getOrDefault(-1)
        if (rc != 0) {
            installed.set(false)
            return false
        }
        android.util.Log.i(TAG, "AAudio native hook installed in $callerPackage")

        // 4. 启动轮询线程
        startPoller(ctx, callerPackage)
        return true
    }

    /**
     * 解析模块 APK 的解压根目录(nativeLibraryDir 为空时用 sourceDir 推导)。
     */
    private fun resolveModuleNativeLibDir(moduleNativeLibDir: String?, moduleApkPath: String?): String? {
        if (!moduleNativeLibDir.isNullOrEmpty()) return moduleNativeLibDir
        if (moduleApkPath != null) {
            return java.io.File(moduleApkPath).parentFile?.let { it.absolutePath + "/lib/arm64" }
        }
        return null
    }

    /**
     * 加载 glassmic_native。
     *
     * useLegacyPackaging=true(QNN 需要 so 落盘)时,LSPosed 模块加载器只能从 APK zip
     * 直接加载"未压缩"的 so;而 CMake 编出的 so 在 zip 里是压缩的,System.loadLibrary 会找不到。
     * 兜底方案:
     *  1. LSPosed 直接提供的模块 ApplicationInfo.nativeLibraryDir(useLegacyPackaging 的解压根目录)
     *  2. packageManager.getApplicationInfo(受包可见性限制,不一定可用)
     */
    private fun loadNativeLibrary(ctx: Context, moduleNativeLibDir: String?, moduleApkPath: String?): Boolean {
        val stdOk = runCatching {
            System.loadLibrary("glassmic_native")
            true
        }.getOrDefault(false)
        if (stdOk) return true

        // 1) 模块自己的 nativeLibraryDir / 由 sourceDir 推导的解压根目录(无包可见性问题)
        val dir = resolveModuleNativeLibDir(moduleNativeLibDir, moduleApkPath)
        if (!dir.isNullOrEmpty()) {
            // glassmic_native 链接了 shadowhook,这里再 load 一次同一路径是无害的
            // (ShadowHook.init 已用自定义 ILibLoader 从同一路径加载并初始化)
            val shadow1 = "$dir/libshadowhook.so"
            if (java.io.File(shadow1).exists()) {
                runCatching { System.load(shadow1) }
            }
            val p1 = "$dir/libglassmic_native.so"
            val loaded1 = runCatching {
                if (!java.io.File(p1).exists()) {
                    android.util.Log.e(TAG, "native lib not found at $p1")
                    false
                } else {
                    System.load(p1)
                    android.util.Log.i(TAG, "loaded glassmic_native from $p1")
                    true
                }
            }.onFailure {
                android.util.Log.e(TAG, "load from moduleNativeLibDir failed: ${it.message}", it)
            }.getOrDefault(false)
            if (loaded1) return true
        }

        // 2) packageManager(可能有包可见性限制)
        return runCatching {
            val ai = ctx.packageManager.getApplicationInfo(Constants.APP_PACKAGE, 0)
            val dir = ai.nativeLibraryDir
            val shadow2 = "$dir/libshadowhook.so"
            if (java.io.File(shadow2).exists()) {
                runCatching { System.load(shadow2) }
            }
            val path = "$dir/libglassmic_native.so"
            if (!java.io.File(path).exists()) {
                android.util.Log.e(TAG, "native lib not found at $path (nativeLibraryDir=$dir)")
                false
            } else {
                System.load(path)
                android.util.Log.i(TAG, "loaded glassmic_native from $path")
                true
            }
        }.onFailure {
            android.util.Log.e(TAG, "fallback load glassmic_native failed: ${it.message}", it)
        }.getOrDefault(false)
    }

    private fun startPoller(ctx: Context, callerPackage: String) {
        if (pollerStarted) return
        pollerStarted = true
        thread(name = "GlassMic-AAudioPoller", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            val pollIntervalMs = 80L
            while (true) {
                try {
                    // 4.1 决策
                    val src = XBridge.resolveSource(ctx, callerPackage)
                    val decisionCode = when (src) {
                        SourceType.REAL_MIC -> 0
                        // FILE / TTS 都是"注入 PCM"（app 侧已把 TTS 归一为 FILE，这里仅为穷尽分支兜底）
                        SourceType.FILE, SourceType.TTS -> 1
                        SourceType.SILENCE  -> 2
                    }
                    nativeSetDecision(decisionCode)

                    // 4.2 PCM fd
                    if (src == SourceType.FILE || src == SourceType.TTS) {
                        ensurePcmFd(ctx)
                    } else if (hasPushedFd) {
                        // native 端已经有 fd 了，但当前不需要——通知 native 关掉
                        nativeSetPcmFd(-1, 0, 0)
                        hasPushedFd = false
                        pushedFdSr = 0
                        pushedFdCh = 0
                    }

                    // 4.3 上报统计——native 已经聚合好了，走 batch 接口
                    val stats = nativeDrainStats()  // [reads, bytes, sr, ch, underruns, missing, requested, path]
                    if (stats != null && stats.size >= 4) {
                        val reads = stats[0].toInt()
                        val bytes = stats[1]
                        val sr = stats[2].toInt()
                        val ch = stats[3].toInt()
                        // 全欠载时 bytes=0 也必须上报，否则诊断会一直显示旧应用的数据。
                        if (reads > 0) {
                            val diagnostics = if (stats.size >= 8) Bundle().apply {
                                putLong("underrun_reads", stats[4])
                                putLong("missing_frames", stats[5])
                                putLong("requested_frames", stats[6])
                                putString("path", when (stats[7].toInt()) {
                                    1 -> "AAudio.read"
                                    2 -> "AAudio.callback"
                                    3 -> "AudioRecord.native"
                                    4 -> "OpenSL.callback"
                                    else -> "unknown"
                                })
                            } else null
                            XBridge.reportInterceptBatch(
                                ctx, callerPackage,
                                deltaReads = reads,
                                deltaBytes = bytes,
                                sampleRate = sr, channels = ch,
                                nativeDiagnostics = diagnostics
                            )
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "poller iter error: ${t.message}")
                }

                try {
                    Thread.sleep(pollIntervalMs)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun ensurePcmFd(ctx: Context) {
        // 默认请求 48000Hz mono——publisher 端按 consumer 采样率从 currentSource 读取
        val wantSr = 48000
        val wantCh = 1
        if (hasPushedFd && pushedFdSr == wantSr && pushedFdCh == wantCh) return

        val uri = Uri.parse("content://${Constants.PROVIDER_PCM}/stream?sr=$wantSr&ch=$wantCh")
        val pfd = runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")
        }.onFailure {
            android.util.Log.w(TAG, "open pcm pipe failed: ${it.message}")
        }.getOrNull() ?: run {
            nativeSetPcmFd(-1, 0, 0)
            hasPushedFd = false
            return
        }

        // detachFd 后 PFD 不再持有 fd 所有权，由 native 负责 close
        val fd = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { pfd.detachFd() }.getOrDefault(-1)
        } else {
            // API < 33: detachFd() 不存在，用 native dup() 复制 fd
            runCatching {
                val rawFd = pfd.fileDescriptor
                nativeDupFd(rawFd)
            }.getOrDefault(-1)
        }
        runCatching { pfd.close() }
        if (fd < 0) {
            nativeSetPcmFd(-1, 0, 0)
            hasPushedFd = false
            return
        }
        nativeSetPcmFd(fd, wantSr, wantCh)
        hasPushedFd = true
        pushedFdSr = wantSr
        pushedFdCh = wantCh
        android.util.Log.i(TAG, "pcm fd opened: fd=$fd sr=$wantSr ch=$wantCh")
    }

    // =================== JNI ===================
    @JvmStatic private external fun nativeInstall(): Int
    @JvmStatic private external fun nativeSetDecision(decision: Int)
    @JvmStatic private external fun nativeSetPcmFd(fd: Int, sampleRate: Int, channels: Int)
    @JvmStatic private external fun nativeDrainStats(): LongArray?
    @JvmStatic private external fun nativeDupFd(fd: java.io.FileDescriptor): Int
}
