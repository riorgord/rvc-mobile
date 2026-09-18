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
 * P0:注入侧音源是 SILENCE(舒适噪声在 hook 进程本地填充),本管道暂时不被读;
 * 这里先实现一个"持续写舒适噪声"的兜底实现,保证任何情况下 open 都不会挂起,
 * 也给 P1 的 RVC 出流(写真 PCM)留好位置。
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

        // 兜底写线程:PCM16 LE 舒适噪声,按请求 sr/ch 持续写,直到写端被关闭。
        thread(name = "rvc-pcm-writer", isDaemon = true) {
            val bytesPerFrame = channels.coerceAtLeast(1) * 2
            val buf = ByteArray(1920 * bytesPerFrame) // 20ms@48k mono
            try {
                val out = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
                while (true) {
                    ComfortNoise.fillBytes(buf, 0, buf.size)
                    out.write(buf)
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
