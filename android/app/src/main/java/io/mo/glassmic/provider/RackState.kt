package io.mo.glassmic.provider

import io.mo.glassmic.core.model.SourceType

/**
 * 机架运行态(P0 最小版)。
 *
 * 注入侧(被 hook 的 App 进程)通过 RuntimeProvider 实时查询它;
 * App 进程(我们自己的 UI / 未来的引擎)直接读写。
 *
 * 默认关闭(enabled=false → 全部 REAL_MIC 直通真麦),避免测试期间
 * 把主力机银行/电信/政务 App 的麦克风变成静音。P2/P3 接入 UI 后,
 * 由用户(或产品默认)打开全局接管。
 */
object RackState {
    @Volatile var enabled: Boolean = false

    /** enabled 时注入侧使用的音源;P0 用 SILENCE(舒适噪声)验证链路,P1 换成 RVC。 */
    @Volatile var source: SourceType = SourceType.SILENCE

    fun resolve(pkg: String?): SourceType {
        if (!enabled) return SourceType.REAL_MIC
        return source
    }
}
