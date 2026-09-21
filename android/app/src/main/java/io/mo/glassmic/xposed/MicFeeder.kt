package io.mo.glassmic.xposed

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.mo.glassmic.core.Constants

/**
 * 在目标 App(微信/QQ)进程内开一路独立 AudioRecord 读真麦,
 * 通过 RuntimeProvider.call("pushMic") 回传给 App 进程做 RVC。
 *
 * 跨 UID 同时录音被 MIUI 禁,但同一 UID 内多个 AudioRecord 可以并发,
 * 所以放在目标进程内读真麦是可行的。
 */
object MicFeeder {

    private const val TAG = "GlassMic-MicFeed"
    private const val CAPTURE_SR = 16000
    private const val IDLE_MS = 5000L

    private val lock = Any()
    @Volatile private var started = false
    @Volatile private var running = false
    private var lastActive = 0L

    fun ensureStarted(ctx: Context, pkg: String) {
        synchronized(lock) {
            lastActive = System.currentTimeMillis()
            if (started) return

            val minBuf = AudioRecord.getMinBufferSize(
                CAPTURE_SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, CAPTURE_SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord init failed in $pkg")
                return
            }
            rec.startRecording()
            started = true
            running = true
            val uri = Uri.parse("content://${Constants.PROVIDER_RUNTIME}")
            val appCtx = ctx.applicationContext
            val buf = ByteArray(CAPTURE_SR * 2 / 50)  // 20ms @16k = 640B
            val th = Thread {
                while (running) {
                    val r = try {
                        rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    } catch (_: Throwable) { -1 }
                    if (r <= 0) break
                    if (r == buf.size) {
                        try {
                            val b = Bundle().apply {
                                putByteArray("pcm", buf)
                                putInt("sr", CAPTURE_SR)
                                putInt("ch", 1)
                            }
                            appCtx.contentResolver.call(uri, "pushMic", pkg, b)
                        } catch (_: Throwable) {
                            // 机架 App 没起来/被杀时静默,目标 App 录音不受影响
                        }
                    }
                }
                runCatching { rec.stop() }
                runCatching { rec.release() }
                synchronized(lock) {
                    started = false
                    running = false
                }
            }
            th.isDaemon = true
            th.name = "GlassMic-MicFeeder"
            th.start()
            Log.i(TAG, "mic feeder started in $pkg")
        }
    }

    fun onRead(ctx: Context, pkg: String, src: io.mo.glassmic.core.model.SourceType) {
        if (src == io.mo.glassmic.core.model.SourceType.RVC) {
            ensureStarted(ctx, pkg)
        } else {
            checkIdle()
        }
    }

    private fun checkIdle() {
        synchronized(lock) {
            if (started && System.currentTimeMillis() - lastActive > IDLE_MS) {
                running = false
                started = false
                Log.i(TAG, "mic feeder idle stopped")
            }
        }
    }
}
