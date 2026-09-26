package com.rvc.app

import android.os.Build
import java.io.File

/**
 * 机架设备兼容性拦截(保守策略)。
 *
 * 判定依据(2026-09-26 定稿,用户确认):
 *   红(纯拦截)   = 无 root / 非官方内核 / AIDL core(无 audio.primary.*.so)
 *   黄(勾选放行)  = KSU 环境 / 非 Android 12(legacy/HIDL 未实测)
 *   绿(直接放行)  = A12 + 官方内核 + Magisk + 存在 audio.primary.so(不限机型)
 *
 * 已验证基线:K50U(22081212C) 官方内核 5.10.81-android12-9-00001-g6cd504626bae,
 *   A12(SDK31) / MIUI13(V130) / Magisk。
 *
 * 经验教训来源:非官方内核(如 4.19.325-Rebirth、6.6.77-Jianke-Jiangnan)
 *   会导致音频 dlkm 加载失败或行为不可控(K40/K80P 翻车);AIDL core 不加载
 *   audio.primary.*.so,wrapper 无效(K80 M1 通但 M2a 枚举未闭环)。
 *
 * 权限设计(App 域 SELinux 限制):
 *   - /proc/version、/product/bin/magisk 等 App 可直接读
 *   - /vendor/lib64/hw 的 listFiles 可能被 SELinux 拒绝 → 三态探测
 *   - 有 root 时优先用 su 探测(可靠);无 root 时文件直读,读不到按 SDK 兜底
 *     (Android 12 及以下不存在 AIDL core 音频,可视为 legacy 存在)
 */
object DeviceGuard {

    enum class Tier { GREEN, YELLOW, RED }

    /** audio.primary 探测三态:存在 / 确认不存在 / 无法读取(权限受限)。 */
    enum class ApState { EXISTS, NOT_FOUND, UNREADABLE }

    data class Check(
        val name: String,
        val detail: String,
        val pass: Boolean,
    )

    data class Result(
        val tier: Tier,
        val checks: List<Check>,
        val redReasons: List<String>,
        val yellowReasons: List<String>,
        val kernelVersion: String,
        val halScheme: String,
        val rootType: String,
        val apState: ApState,
    )

    // ---------------- 原始探测 ----------------

    /** 执行 su 命令(KSU/Magisk su 路径候选),返回 (rc, output)。 */
    fun suExec(cmd: String): Pair<Int, String> {
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
                if (rc == 0 || out.isNotBlank()) return rc to out
            } catch (t: Throwable) {
                // 这个 su 不存在/不可执行,试下一个
            }
        }
        return -1 to ""
    }

    /**
     * 内核版本字符串。
     * 优先 System.getProperty("os.version")(= uname 的 UTS_RELEASE,含
     * `-android12-` / 自定义昵称后缀,走 uname 系统调用,不被 SELinux 拦);
     * 兜底读 /proc/version(MIUI 对 untrusted_app 禁读 proc_version,会失败)。
     */
    fun kernelVersion(): String {
        val fromProp = try {
            System.getProperty("os.version")?.trim().orEmpty()
        } catch (t: Throwable) { "" }
        if (fromProp.isNotBlank()) return fromProp
        return try {
            File("/proc/version").readText().trim()
        } catch (t: Throwable) { "" }
    }

    /** 内核版本是否官方样式。
     * 官方:GKI `-android<数字>-` 或 CAF `-perf-g<hash>`(K50U:
     * `5.10.81-android12-9-00001-g6cd504626bae-ab8485363`)。
     * 魔改:自定义 LOCALVERSION 昵称(`-Jianke-Jiangnan`、`-Rebirth-...`)。 */
    fun isOfficialKernel(ver: String): Boolean {
        if (ver.isBlank()) return false
        // 官方 GKI:androidNN 出现在版本号里
        if (Regex("""-android\d+[-.]""").containsMatchIn(ver)) return true
        // 官方 CAF(Qualcomm):-perf-g<hash>
        if (Regex("""-perf-g[0-9a-f]{7,}""").containsMatchIn(ver)) return true
        // 常见官方后缀也带 -g<hash>
        if (Regex("""\s-g[0-9a-f]{7,}""").containsMatchIn(ver)) return true
        return false
    }

    /**
     * root 类型:magisk / ksu / none。
     * 1) 先查 App 可读的 Magisk 二进制路径(无需 su 授权)。
     * 2) 查不到时试 su:能提权再区分 KSU(存在 /data/adb/ksu)。
     */
    fun rootType(): String {
        val magiskPaths = listOf(
            "/product/bin/magisk", "/system/bin/magisk", "/sbin/magisk",
            "/debug_ramdisk/magisk",
        )
        for (p in magiskPaths) {
            try { if (File(p).exists()) return "magisk" } catch (_: Throwable) {}
        }
        // 可读路径查不到 → su 探测(需要用户已授权本应用)
        val (rc, _) = suExec("id")
        if (rc == 0) {
            val (rc2, _) = suExec("ls -d /data/adb/ksu 2>/dev/null")
            if (rc2 == 0) return "ksu"
            return "magisk"
        }
        return "none"
    }

    /**
     * audio.primary 三态探测。
     * 优先 su(有 root 时可靠);否则文件直读;全读不到 → UNREADABLE。
     */
    fun probeAudioPrimary(root: String): ApState {
        // 1. su 路径(可靠,绕开 SELinux)
        if (root != "none") {
            val (rc, out) = suExec(
                "ls /vendor/lib64/hw/audio.primary.*.so /vendor/lib/hw/audio.primary.*.so " +
                "/system/lib64/hw/audio.primary.*.so /system/lib/hw/audio.primary.*.so 2>/dev/null | " +
                "grep -v 'audio.primary.default.so' | head -1"
            )
            if (rc == 0 && out.isNotBlank()) return ApState.EXISTS
            if (rc == 0) return ApState.NOT_FOUND
            // rc != 0:su 失败,降级文件直读
        }
        // 2. 文件直读(App 域可能被 SELinux 拒绝)
        val dirs = listOf("/vendor/lib64/hw", "/vendor/lib/hw", "/system/lib64/hw", "/system/lib/hw")
        var anyReadable = false
        for (d in dirs) {
            try {
                val f = File(d)
                if (!f.isDirectory) continue
                val kids = f.listFiles() ?: continue  // null = 无读权限
                anyReadable = true
                for (child in kids) {
                    val n = child.name
                    if (n.startsWith("audio.primary.") && n.endsWith(".so")
                        && n != "audio.primary.default.so") return ApState.EXISTS
                }
            } catch (_: Throwable) {}
        }
        return if (anyReadable) ApState.NOT_FOUND else ApState.UNREADABLE
    }

    fun sdkInt(): Int = Build.VERSION.SDK_INT

    // ---------------- 判定 ----------------

    fun evaluate(): Result {
        val kv = kernelVersion()
        val sdk = sdkInt()
        val rt = rootType()
        val ap = probeAudioPrimary(rt)

        // 无法读取时的 SDK 兜底:Android 12 及以下不存在 AIDL core 音频,
        // 保守视为 legacy 存在(不因权限误拦);Android 13+ 无法验证 → 黄。
        val apEffective: ApState = if (ap == ApState.UNREADABLE && sdk <= 31) {
            ApState.EXISTS
        } else ap
        val apNote = if (ap == ApState.UNREADABLE)
            "音频 HAL 无法读取(权限受限),按 SDK=$sdk 保守推断" else ""

        val checks = listOf(
            Check("root", rt, rt != "none"),
            Check("内核", if (kv.isBlank()) "(读取失败)" else "official=${isOfficialKernel(kv)}", isOfficialKernel(kv)),
            Check("audio.primary", when (apEffective) {
                ApState.EXISTS -> "存在(LEGACY/HIDL)"
                ApState.NOT_FOUND -> "不存在(AIDL)"
                ApState.UNREADABLE -> "无法读取"
            }, apEffective == ApState.EXISTS),
            Check("Android", "SDK=$sdk", sdk == 31),
        )

        val red = mutableListOf<String>()
        val yellow = mutableListOf<String>()

        if (rt == "none") {
            red += "未检测到 root(Magisk/KSU 均不存在)"
        } else if (rt == "ksu") {
            yellow += "KernelSU 环境:与 Magisk 行为差异较大,未经广泛测试"
        }
        if (!isOfficialKernel(kv)) {
            red += if (kv.isBlank()) "无法读取内核版本(/proc/version)"
            else "检测到非官方内核:${kv.take(80)}"
        }
        when (apEffective) {
            ApState.NOT_FOUND -> red += "音频 HAL 为 AIDL core(无 audio.primary.*.so),wrapper 无法生效"
            ApState.UNREADABLE -> yellow += apNote + ",无法确认是否为 AIDL,若为 AIDL 设备机架将无法工作"
            ApState.EXISTS -> {}
        }
        if (sdk != 31) {
            yellow += "Android ${Build.VERSION.RELEASE}(SDK=$sdk) 未经实测,仅在 Android 12 验证"
        }

        val tier = when {
            red.isNotEmpty() -> Tier.RED
            yellow.isNotEmpty() -> Tier.YELLOW
            else -> Tier.GREEN
        }
        return Result(tier, checks, red, yellow, kv, halSchemeLabel(apEffective, sdk), rt, ap)
    }

    private fun halSchemeLabel(ap: ApState, sdk: Int): String = when (ap) {
        ApState.EXISTS -> if (sdk <= 30) "HIDL" else "LEGACY/HIDL"
        ApState.NOT_FOUND -> "AIDL"
        ApState.UNREADABLE -> "UNKNOWN"
    }
}
