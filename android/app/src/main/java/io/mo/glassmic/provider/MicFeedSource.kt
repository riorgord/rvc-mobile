package io.mo.glassmic.provider

import android.util.Log

/**
 * 目标 App(微信/QQ)进程回传的真麦 PCM 输入源。
 *
 * 这个设备上 MIUI/HyperOS 不允许两个不同 UID 的 App 同时拿真麦,
 * 所以 App 自己录真麦在微信录音时拿到的是静音。改为:
 * 微信进程内自己开一个 AudioRecord 读真麦 → 通过 RuntimeProvider.call("pushMic")
 * 把 PCM16 回传到这里 → 转成 48k float 环形缓冲 → runStreamLive 的录音线程消费。
 */
object MicFeedSource {

    private const val TAG = "MicFeedSource"
    private const val TARGET_SR = 48000
    private const val CAPACITY = TARGET_SR * 5  // 5 秒环形缓冲

    private val data = FloatArray(CAPACITY)
    private var writePos = 0L
    private var readPos = 0L
    private val lock = Object()

    fun reset() {
        synchronized(lock) {
            writePos = 0L
            readPos = 0L
        }
    }

    fun bufferedSamples(): Int = synchronized(lock) { (writePos - readPos).toInt() }

    fun push(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (pcm.size < 2) return
        val sr = if (sampleRate > 0) sampleRate else 16000
        val ch = if (channels > 0) channels else 1
        val frames = pcm.size / 2 / ch
        if (frames <= 0) return

        // 取第一声道 PCM16 → float
        val src = FloatArray(frames)
        for (i in 0 until frames) {
            val idx = (i * ch) * 2
            val lo = pcm[idx].toInt() and 0xff
            val hi = pcm[idx + 1].toInt()
            val s = ((hi shl 8) or lo).toShort()
            src[i] = s / 32768f
        }

        // 线性重采样到 48k
        val outLen = (src.size.toLong() * TARGET_SR / sr).toInt() + 1
        val out = FloatArray(outLen)
        for (j in out.indices) {
            val p = j.toDouble() * sr / TARGET_SR
            val i0 = p.toInt()
            if (i0 >= src.size - 1) {
                out[j] = src[src.size - 1]
            } else {
                val frac = (p - i0).toFloat()
                out[j] = src[i0] + (src[i0 + 1] - src[i0]) * frac
            }
        }

        synchronized(lock) {
            val avail = (writePos - readPos).toInt()
            val drop = avail + out.size - CAPACITY
            if (drop > 0) readPos += drop

            var idx = (writePos % CAPACITY).toInt()
            for (v in out) {
                if ((writePos - readPos).toInt() >= CAPACITY) break
                data[idx] = v
                idx = if (idx + 1 >= CAPACITY) 0 else idx + 1
                writePos++
            }
            lock.notifyAll()
        }
    }

    /** 阻塞直到凑满 len 个 48k float,超时返回 0。 */
    fun readFloatChunk(out: FloatArray, len: Int, timeoutMs: Long): Int {
        if (out.size < len) return 0
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while ((writePos - readPos) < len) {
                val now = System.currentTimeMillis()
                if (now >= deadline) return 0
                lock.wait(deadline - now)
            }
            var idx = (readPos % CAPACITY).toInt()
            for (i in 0 until len) {
                out[i] = data[idx]
                idx = if (idx + 1 >= CAPACITY) 0 else idx + 1
            }
            readPos += len
            return len
        }
    }
}
