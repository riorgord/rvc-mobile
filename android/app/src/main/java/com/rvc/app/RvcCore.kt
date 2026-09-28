package com.rvc.app

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * P1 抽取:RVC 业务核心(纯逻辑,不依赖 Activity/View)。
 *
 * 所有 Python/QNN 桥调用 + root/系统工具集中在此,
 * MainActivity(手写 View UI)与未来 Compose ViewModel 共用。
 *
 * 设计原则:
 * - 每个方法返回数据,不碰 UI(播放/进度/弹窗由调用方负责);
 * - Context 只取 applicationContext(防 Activity 泄漏);
 * - 行为与原 MainActivity 内联实现完全等价。
 */
class RvcCore(applicationContext: Context) {

    private val ctx = applicationContext.applicationContext

    val prefs = ctx.getSharedPreferences("rvc_prefs", Context.MODE_PRIVATE)
    val filesDir: File get() = ctx.filesDir

    // ---------------- Python 桥 ----------------

    fun ensurePy() {
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
    }

    private fun py() = Python.getInstance().getModule("rvc_api")

    private fun uid() = android.os.Process.myUid()

    fun nativeLibDir(): String =
        try { ctx.applicationInfo.nativeLibraryDir } catch (e: Exception) { "?" }

    /** 预载默认 fcpe 管线(hubert+fcpe+gen 常驻)。 */
    fun pyPreloadDefault() {
        ensurePy()
        py().callAttr("preload_default", nativeLibDir(), filesDir.absolutePath,
            uid(), false, RoleManager.effectiveSoc())
    }

    /** 现场加载 F0 提取器(rmvpe 等)。 */
    fun pyInitF0(m: String) {
        ensurePy()
        py().callAttr("init_f0", nativeLibDir(), filesDir.absolutePath,
            uid(), m, false, RoleManager.effectiveSoc())
    }

    /** 3 模型全检。返回结果文本。 */
    fun pySelfTestAll(profile: Boolean): String {
        ensurePy()
        return py().callAttr("self_test_all", nativeLibDir(), filesDir.absolutePath,
            uid(), profile, "/sdcard/rvc_exp").toString()
    }

    /** 全链路自检。返回结果文本。 */
    fun pySelfTestFull(profile: Boolean): String {
        ensurePy()
        return py().callAttr("self_test_full", nativeLibDir(), filesDir.absolutePath,
            uid(), profile, "/sdcard/rvc_exp").toString()
    }

    /** ② 拆分测速(z_producer + dec_short 滑窗)。 */
    fun pySelfTestRoute2(profile: Boolean): String {
        ensurePy()
        return py().callAttr("self_test_route2", nativeLibDir(), filesDir.absolutePath,
            uid(), profile, "/sdcard/rvc_exp").toString()
    }

    /** iSTFT dec 块级流式自测。 */
    fun pySelfTestRoute2Istft(profile: Boolean, f0m: String, br: Float): String {
        ensurePy()
        return py().callAttr("self_test_route2_istft", nativeLibDir(), filesDir.absolutePath,
            uid(), profile, "/sdcard/rvc_exp", f0m, br).toString()
    }

    /** 模拟实时:参考音频 → 实时链路,返回 40k PCM float 字节。 */
    fun pyProcessStreamV2Ref(
        profile: Boolean, key: Int, rms: Float, idx: Float, prot: Float, f0m: String,
    ): ByteArray {
        ensurePy()
        return py().callAttr("process_stream_v2_ref", nativeLibDir(), filesDir.absolutePath,
            uid(), profile, key, rms, idx, prot, f0m).toJava(ByteArray::class.java)
    }

    /** M2 内存链路验证。 */
    fun pySelfTestLiveIo(profile: Boolean): String {
        ensurePy()
        return py().callAttr("self_test_live_io", nativeLibDir(), filesDir.absolutePath,
            uid(), profile).toString()
    }

    /** M2 实时:16k 录音字节 → 变声,返回 40k PCM float 字节。 */
    fun pyProcessAudio(
        profile: Boolean, inBytes: ByteArray,
        key: Int, rms: Float, idx: Float, prot: Float, f0m: String,
    ): ByteArray {
        ensurePy()
        return py().callAttr("process_audio", nativeLibDir(), filesDir.absolutePath,
            uid(), inBytes, profile, key, rms, idx, prot, f0m).toJava(ByteArray::class.java)
    }

    /** 创建流式会话(测延迟/实时链路)。 */
    fun pyStreamCreate(
        profile: Boolean, key: Int, rms: Float, idx: Float, prot: Float,
        block: Int, nb: Int,
    ) {
        ensurePy()
        py().callAttr("stream_create", nativeLibDir(), filesDir.absolutePath,
            uid(), profile, key, rms, idx, prot, block, nb,
            RoleManager.currentRoleDir(filesDir, prefs), RoleManager.effectiveSoc())
    }

    /** 测稳态 T_proc,返回毫秒。 */
    fun pyStreamMeasureLatency(): Double {
        ensurePy()
        return py().callAttr("stream_measure_latency", 4).toDouble()
    }

    // ---------------- root / 系统工具 ----------------

    /* KernelSU 的 su 在 /data/adb/ksu/bin/su(不在 PATH);Magisk 的 su 在 PATH。
     * 依次尝试,谁能启动就用谁。 */
    fun runSu(cmd: String): Pair<Int, String> {
        val candidates = listOf(
            "su",
            "/data/adb/ksu/bin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su"
        )
        for (su in candidates) {
            try {
                val p = ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                val rc = p.waitFor()
                return rc to out
            } catch (t: Throwable) {
                // 这个 su 不存在/不可执行,试下一个
            }
        }
        return -1 to ""
    }

    fun hasRoot(): Boolean {
        val (rc, out) = runSu("id")
        return rc == 0 && out.contains("uid=0")
    }

    /* 音频 HAL 方案探测:
     * AIDL   = 第3代,不加载 audio.primary.*.so,老 wrapper 无效(K80 这种)
     * HIDL   = 第2代,仍会加载 audio.primary.*.so,wrapper 有效
     * LEGACY = 第1代,直接加载 audio.primary.*.so,wrapper 有效
     * UNKNOWN = 探测不到,保守按不支持处理
     */
    fun detectHalScheme(): String {
        val script = """
            if service list 2>/dev/null | grep -q "android.hardware.audio.core.IModule"; then echo AIDL; exit 0; fi
            if ls /vendor/lib64/android.hardware.audio.core-*-ndk.so /vendor/lib/android.hardware.audio.core-*-ndk.so 2>/dev/null | head -1 | grep -q .; then echo AIDL; exit 0; fi
            if ls /vendor/bin/hw/audiohalservice* /vendor/bin/hw/android.hardware.audio.service 2>/dev/null | head -1 | grep -q .; then echo AIDL; exit 0; fi
            if service list 2>/dev/null | grep -q "android.hardware.audio@"; then echo HIDL; exit 0; fi
            if grep -l "audio.primary" /proc/[0-9]*/maps 2>/dev/null | head -1 | grep -q .; then echo LEGACY; exit 0; fi
            if ls /vendor/lib64/hw/audio.primary.*.so /vendor/lib/hw/audio.primary.*.so 2>/dev/null | grep -v 'audio.primary.default.so' | head -1 | grep -q .; then echo LEGACY; exit 0; fi
            echo UNKNOWN
        """.trimIndent()
        val (rc, out) = runSu(script)
        if (rc != 0) return "UNKNOWN"
        val line = out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "UNKNOWN"
        return if (line in setOf("AIDL", "HIDL", "LEGACY", "UNKNOWN")) line else "UNKNOWN"
    }

    /** 读取 CPU/DSP/GPU/电池温度(测延迟时判断热降频)。温度默认毫摄氏度,统一 /1000。 */
    fun readThermalSummary(): String {
        val cmd = "for z in /sys/class/thermal/thermal_zone*; do " +
            "t=\$(cat \$z/temp 2>/dev/null); n=\$(cat \$z/type 2>/dev/null); " +
            "echo \"\$n:\$t\"; done"
        val (rc, out) = runSu(cmd)
        if (rc != 0 || out.isBlank()) return "温度读取失败"
        var cpu = -1.0
        var nsp = -1.0
        var gpu = -1.0
        var bat = -1.0
        for (line in out.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim().toDoubleOrNull() ?: continue
            val c = v / 1000.0
            when {
                name.startsWith("cpu") -> if (c > cpu) cpu = c
                name.startsWith("nsp") -> if (c > nsp) nsp = c
                name.startsWith("gpu") -> if (c > gpu) gpu = c
                name == "battery" -> bat = c
            }
        }
        val sb = StringBuilder()
        if (cpu >= 0) sb.append("CPU=").append("%.1f".format(cpu)).append("°C ")
        if (nsp >= 0) sb.append("DSP=").append("%.1f".format(nsp)).append("°C ")
        if (gpu >= 0) sb.append("GPU=").append("%.1f".format(gpu)).append("°C ")
        if (bat >= 0) sb.append("电池=").append("%.1f".format(bat)).append("°C")
        return sb.toString().trim().ifBlank { "温度读取失败" }
    }

    /** 已装 HAL 模块版本(Magisk 模块 module.prop)。 */
    fun moduleInstalledVersion(): String? {
        val (rc, out) = runSu("grep '^version=' /data/adb/modules/rvc_virtual_mic_hal/module.prop 2>/dev/null")
        if (rc != 0) return null
        return out.trim().removePrefix("version=").ifBlank { null }
    }

    /** 解出 APK 内置的 rvc_module.zip 到外部目录。 */
    fun copyBundledModuleZip(): File? {
        return try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = File(dir, "rvc_module.zip")
            ctx.assets.open("rvc_module.zip").use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
            f
        } catch (t: Throwable) {
            null
        }
    }

    /* 轻量检测 HAL 模块是否生效:ro.hardware.audio.primary 应为 rvc。
     * 无需 root,读系统属性即可。 */
    fun isHalModuleActive(): Boolean {
        return try {
            val p = ProcessBuilder("getprop", "ro.hardware.audio.primary").start()
            val s = p.inputStream.bufferedReader().readText().trim().lowercase()
            p.waitFor()
            s.contains("rvc")
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------- 纯工具 ----------------

    fun appVersionName(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
    } catch (e: Exception) {
        "0.0.0"
    }

    /** 版本号 "a.b.c" 数值比较: a>b → 1, a<b → -1, 相等 → 0。 */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /** 递归复制 assets 子目录到 filesDir,已存在跳过、缺失补拷。 */
    fun extract(src: String, dst: File) {
        if (!dst.exists()) dst.mkdirs()
        ctx.assets.list(src)?.forEach { name ->
            val full = "$src/$name"
            val child = File(dst, name)
            var isDir = false
            try { ctx.assets.open(full).use { } } catch (e: Exception) { isDir = true }
            if (isDir) extract(full, child)
            else if (!child.exists())
                ctx.assets.open(full).use { ins -> child.outputStream().use { ins.copyTo(it) } }
        }
    }
}
