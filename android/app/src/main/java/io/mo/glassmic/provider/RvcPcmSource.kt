package io.mo.glassmic.provider

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * 实时变声音源:承载 RVC 引擎出流(40k fp32 mono),供给 PCM 管道。
 *
 * 广播模型:所有消费端(微信/QQ/系统进程)都读同一个**实时位置**,
 * 基于 [startNanos] 的墙钟推进,而不是各自消费游标——这样不会出现
 * 多管道互抢/把缓冲快速抽干的问题,行为接近真麦克风广播。
 *
 * - push: RVC 输出块转 PCM16 入有界环形缓冲;第一个块到达时锚定时钟。
 * - fillResampled: 按墙钟位置线性插值重采样,落后被丢弃则跳到 live 边缘。
 */
object RvcPcmSource {

    @Volatile private var active = false
    @Volatile var sourceSampleRate: Int = 40000
        private set
    @Volatile var sourceChannels: Int = 1
        private set

    private val chunks = ArrayDeque<ShortArray>()
    private var baseFrame = 0L       // 队首 chunk[0] 的绝对帧号
    private var bufferedFrames = 0L  // 当前缓冲总帧数
    private var startNanos = 0L      // 首个输出块到达时刻(广播时钟锚点)
    private var shortLogCount = 0    // 诊断:短输出计数
    private val maxFrames = 240000   // 6s @40k 兜底上限

    fun isActive(): Boolean = active

    fun start(sampleRate: Int = 40000) {
        synchronized(this) {
            active = true
            sourceSampleRate = sampleRate
            sourceChannels = 1
            chunks.clear()
            baseFrame = 0L
            bufferedFrames = 0L
            startNanos = 0L
            shortLogCount = 0
        }
    }

    fun stop() {
        synchronized(this) {
            active = false
            chunks.clear()
            baseFrame = 0L
            bufferedFrames = 0L
            startNanos = 0L
        }
    }

    /** 推入 RVC 输出块(fp32 LE bytes,通常 40k mono)。转 PCM16 入缓冲,超上限丢最旧。 */
    fun push(floatBytes: ByteArray) {
        if (floatBytes.isEmpty()) return
        val nFloats = floatBytes.size / 4
        if (nFloats <= 0) return
        val fb = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val s = ShortArray(nFloats)
        for (i in 0 until nFloats) {
            val f = fb.get(i)
            val clamped = if (f > 1f) 1f else if (f < -1f) -1f else f
            s[i] = (clamped * 32767f).toInt().toShort()
        }
        synchronized(this) {
            if (!active) return
            if (chunks.isEmpty()) startNanos = System.nanoTime()
            chunks.addLast(s)
            bufferedFrames += s.size
            while (bufferedFrames > maxFrames && chunks.isNotEmpty()) {
                val removed = chunks.removeFirst().size
                baseFrame += removed
                bufferedFrames -= removed
            }
        }
    }

    /**
     * 按目标采样率/声道重采样输出 PCM16 LE。
     * 所有消费端共享同一墙钟位置;数据不足只输出已有部分,由调用方补舒适噪声。
     */
    fun fillResampled(out: ByteArray, offset: Int, size: Int, dstSampleRate: Int, dstChannels: Int): Int {
        synchronized(this) {
            if (!active || dstSampleRate <= 0 || dstChannels <= 0) return 0
            val srcSr = sourceSampleRate
            val dstFrameBytes = dstChannels * 2
            val dstFrames = size / dstFrameBytes
            if (dstFrames <= 0) return 0
            // 广播读头:从首个输出块到达起按墙钟推进
            var pos = if (startNanos == 0L) 0.0
            else (System.nanoTime() - startNanos) / 1_000_000_000.0 * srcSr
            // 缓冲已被 push 侧丢弃(落后太多)→ 跳到 live 边缘
            if (pos < baseFrame) pos = baseFrame.toDouble()

            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            var outPos = offset
            var produced = 0
            for (i in 0 until dstFrames) {
                val i0 = pos.toLong()
                val s0 = getSample(i0) ?: break
                val s1 = getSample(i0 + 1) ?: s0
                val frac = (pos - i0).toFloat()
                val sample = (s0 + ((s1 - s0) * frac)).toInt().toShort()
                repeat(dstChannels) {
                    bb.putShort(outPos, sample)
                    outPos += 2
                }
                produced += dstFrameBytes
                pos += srcSr.toDouble() / dstSampleRate.toDouble()
            }
            if (produced < size) {
                shortLogCount++
                if (shortLogCount % 50 == 0) {
                    Log.i(
                        "RvcPcmSource",
                        "short produced=$produced/$size pos=${pos.toLong()} base=$baseFrame " +
                            "buffered=$bufferedFrames chunks=${chunks.size} startNanos=$startNanos"
                    )
                }
            }
            return produced
        }
    }

    private fun getSample(abs: Long): Short? {
        if (abs < baseFrame) return null
        if (abs >= baseFrame + bufferedFrames) return null
        var local = (abs - baseFrame).toInt()
        for (c in chunks) {
            if (local < c.size) return c[local]
            local -= c.size
        }
        return null
    }
}
