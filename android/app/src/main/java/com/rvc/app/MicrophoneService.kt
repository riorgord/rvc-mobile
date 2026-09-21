package com.rvc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 麦克风前台服务。
 *
 * MIUI/HyperOS 对后台 App 的麦克风会静音(隐私策略),哪怕开了 VOICE_RECOGNITION。
 * 变声注入时微信在前台、本 App 在后台,不加这个服务 App 录到的是静音。
 * 以 foregroundServiceType=microphone 常驻,系统就知道本 App 正在后台用麦,不再静音。
 */
class MicrophoneService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                "mic", "麦克风变声", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val n = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "mic")
                .setContentTitle("RVC 变声注入运行中")
                .setContentText("正在为语音/通话提供实时变声")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("RVC 变声注入运行中")
                .setContentText("正在为语音/通话提供实时变声")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        return START_STICKY
    }
}
