package com.rvc.app

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 共享件(shared.zip)下载器。
 *
 * 策略:并行探测抱脸/魔塔两个源的连通性与延迟,选可达且延迟低的下载;
 * 都不可达/未配置时返回 null,由 UI 引导 SAF 手动导入。
 *
 * TODO: 上传 shared.zip 后,把真实直链填进 SHARED_URLS 并把 enabled 置 true。
 *   抱脸: https://huggingface.co/<你的组织>/<repo>/resolve/main/shared.zip
 *   魔塔: https://www.modelscope.cn/models/<你的组织>/<repo>/resolve/master/shared.zip
 */
object SharedDownloader {
    private const val TAG = "SharedDownloader"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 30000

    private val SHARED_URLS = listOf(
        Triple("huggingface",
            "https://huggingface.co/<org>/rvc-shared/resolve/main/shared.zip", false),
        Triple("modelscope",
            "https://www.modelscope.cn/models/<org>/rvc-shared/resolve/master/shared.zip", false)
    )

    data class Source(val name: String, val url: String)

    fun availableSources(): List<Source> =
        SHARED_URLS.filter { it.third && !it.second.contains("<") }
            .map { Source(it.first, it.second) }

    /** 探测两个源,返回可达且延迟最低的;都不可达返回 null。 */
    fun probeAndPick(): Source? {
        var best: Source? = null
        var bestMs = Long.MAX_VALUE
        for (s in availableSources()) {
            try {
                val t0 = System.nanoTime()
                val conn = URL(s.url).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = CONNECT_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                val ms = (System.nanoTime() - t0) / 1_000_000
                conn.disconnect()
                Log.i(TAG, "probe ${s.name} code=$code ms=$ms")
                if (code in 200..399 && ms < bestMs) {
                    bestMs = ms
                    best = s
                }
            } catch (e: Exception) {
                Log.w(TAG, "probe ${s.name} failed: $e")
            }
        }
        return best
    }

    /**
     * 断点续传下载到 dest(临时文件 dest.part)。成功后 rename 为 dest。
     * onProgress 回调(已下载字节,总字节;总字节未知时 null)。
     */
    fun download(source: Source, dest: File, onProgress: (Long, Long?) -> Unit): Boolean {
        val part = File(dest.parentFile, dest.name + ".part")
        val resume = part.length()
        var downloaded = resume
        var conn: HttpURLConnection? = null
        try {
            conn = URL(source.url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            if (resume > 0) conn.setRequestProperty("Range", "bytes=$resume-")
            val code = conn.responseCode
            if (code == 200) {
                // 服务器不支持断点:重头下
                part.delete()
                downloaded = 0
            } else if (code != 206) {
                Log.e(TAG, "download failed code=$code")
                return false
            }
            val total = conn.contentLength.takeIf { it > 0 }?.plus(if (code == 206) downloaded else 0L)
            val fos = FileOutputStream(part, downloaded > 0 && code == 206)
            conn.inputStream.use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    downloaded += n
                    onProgress(downloaded, total)
                }
            }
            fos.close()
            dest.delete()
            if (part.exists() && !part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "download failed: $e")
            return false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
