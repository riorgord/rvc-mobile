package io.mo.glassmic.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mo.glassmic.core.audio.ComfortNoise
import kotlin.concurrent.thread

/**
 * PCM 管道 Provider(P0 最小版)。
 *
 * 协议(与 GlassMic 一致):
 *   content://com.rvc.app.provider.pcm/stream?sr=48000&ch=1
 *   openFileDescriptor(uri, "r") → 返回 pipe 读端
 *
 * P0:注入侧音源是 FILE(PcmTestSource)或 SILENCE(舒适噪声在 hook 进程本地填充)。
 * 这里按请求的 sr/ch 对源做重采样,并以 20ms 实时节流写管道,尽量模拟真麦克风节奏。
 */
class PcmStreamProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "PcmStreamProvider 只支持只读" }
        val sampleRate = uri.getQueryParameter("sr")?.toIntOrNull() ?: 48000
        val channels = uri.getQueryParameter("ch")?.toIntOrNull() ?: 1

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        android.util.Log.i(
            "GlassMic-Runtime",
            "PcmStream.open sr=$sampleRate ch=$channels caller=$callingPackage " +
                "src=${when {
                    RvcQueueSource.isActive() -> "RVC_QUEUE"
                    RvcPcmSource.isActive() -> "RVC"
                    else -> "FILE(PcmTest)"
                }} " +
                "queue=${if (RvcQueueSource.isActive()) RvcQueueSource.bufferedBlocks() else 0} " +
                "rvc=${RvcPcmSource.sourceSampleRate}/${RvcPcmSource.sourceChannels} " +
                "test=${PcmTestSource.sourceSampleRate}/${PcmTestSource.sourceChannels}"
        )

        // 写线程:按目标 sr/ch 重采样 + 20ms 实时节流,模拟真麦克风。
        thread(name = "rvc-pcm-writer", isDaemon = true) {
            val targetFrameBytes = channels.coerceAtLeast(1) * 2
            val chunkFrames = (sampleRate.coerceAtLeast(1) * 20 / 1000).coerceAtLeast(1)
            val buf = ByteArray(chunkFrames * targetFrameBytes)
            var writes = 0
            var shortWrites = 0
            try {
                val out = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
                while (true) {
                    val n = when {
                        RvcQueueSource.isActive() ->
                            RvcQueueSource.fillResampled(buf, 0, buf.size, sampleRate, channels)
                        RvcPcmSource.isActive() ->
                            RvcPcmSource.fillResampled(buf, 0, buf.size, sampleRate, channels)
                        else ->
                            PcmTestSource.fillResampled(buf, 0, buf.size, sampleRate, channels)
                    }
                    // 无论 RVC 给多少,永远写满一个完整 20ms 块(不足部分用舒适噪声补),
                    // 否则微信/QQ 编码器会因节奏断裂报"语音错误"。
                    if (n < buf.size) {
                        shortWrites++
                        ComfortNoise.fillBytes(buf, n.coerceAtLeast(0), buf.size - n.coerceAtLeast(0))
                    }
                    out.write(buf)
                    writes++
                    if (writes % 100 == 0) {
                        android.util.Log.i(
                            "RvcWriter",
                            "n=$n/${buf.size} short=$shortWrites writes=$writes " +
                                "rvcActive=${RvcPcmSource.isActive() || RvcQueueSource.isActive()} " +
                                "queueMs=${if (RvcQueueSource.isActive()) RvcQueueSource.bufferedMs() else 0}"
                        )
                    }
                    // 真麦节奏:20ms 一帧
                    runCatching { Thread.sleep(20) }
                }
            } catch (_: Throwable) {
                // 写端关闭(消费方 EOF)即正常结束
            }
        }
        return readSide
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
