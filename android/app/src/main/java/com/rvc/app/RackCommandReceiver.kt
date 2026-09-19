package com.rvc.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.provider.PcmTestSource
import io.mo.glassmic.provider.RackState

/**
 * P0 调试控制接收器。
 *
 * 用法(adb):
 *   adb shell am broadcast -a com.rvc.app.action.RACK --ez enabled true --es source SILENCE
 *   adb shell am broadcast -a com.rvc.app.action.RACK --ez enabled false
 *
 * 仅用于 P0/P1 真机联调;P2 接入 UI 后此通道可保留或收紧权限。
 */
class RackCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RACK) return
        if (intent.hasExtra(EXTRA_ENABLED)) {
            val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
            RackState.enabled = enabled
            val srcName = intent.getStringExtra(EXTRA_SOURCE)
            val src = srcName?.let { runCatching { SourceType.valueOf(it) }.getOrNull() } ?: SourceType.REAL_MIC
            RackState.source = src
            if (enabled && src == SourceType.FILE) {
                if (!PcmTestSource.isActive()) {
                    PcmTestSource.start("/sdcard/Download/glassmic_test.wav", loop = true)
                }
            } else {
                PcmTestSource.stop()
                if (enabled) RackState.source = src
                else RackState.source = SourceType.REAL_MIC
            }
        }
    }

    companion object {
        const val ACTION_RACK = "com.rvc.app.action.RACK"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_SOURCE = "source"
    }
}
