package io.mo.glassmic.provider

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 实时变声注入的"段队列"音源。
 *
 * 思路(用户提出):RVC 每算好一块(370ms)就追加进队列,消费端按哔哔那套
 * PcmTestSource 逻辑顺序读、播完即丢。这样走的是已验证的 PcmStreamProvider 传输路,
 * 不再用 RvcPcmSource 的"墙钟广播"模型(那套欠载时疯狂填舒适噪声=沙沙声)。
 *
 * 源固定 40k 单声道 PCM16(与 RVC 输出一致);fillResampled 按目标 sr/ch 线性插值,
 * 队列为空就返回已产出的部分(由 Provider 补舒适噪声/静音)。
 */
object RvcQueueSource {

    const val SRC_SAMPLE_RATE = 40000
    const val SRC_CHANNELS = 1

    @Volatile private var active = false
    private val queue = ArrayDeque<ShortArray>()
    private var current: ShortArray? = null
    private var framePos = 0.0

    fun isActive(): Boolean = active

    @Synchronized
    fun start() {
        active = true
        queue.clear()
        current = null
        framePos = 0.0
    }

    @Synchronized
    fun stop() {
        active = false
        queue.clear()
        current = null
        framePos = 0.0
    }

    /** 追加一块 RVC 输出(PCM16 40k mono)。 */
    @Synchronized
    fun push(samples: ShortArray) {
        if (!active) return
        if (samples.isEmpty()) return
        queue.addLast(samples)
    }

    /** 兼容字节数组入队:把 float32 LE 40k 输出转成 PCM16 再追加。 */
    @Synchronized
    fun pushFloatBytes(bytes: ByteArray) {
        if (!active || bytes.size < 4) return
        val f = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f)
        val s = ShortArray(f.size)
        for (i in f.indices) {
            val v = f[i]
            s[i] = when {
                v >= 1f -> 32767
                v <= -1f -> -32768
                else -> (v * 32767f).toInt()
            }.toShort()
        }
        queue.addLast(s)
    }

    /** 当前队列里还有多少毫秒可播(40k 源帧),用于诊断预填/欠载。 */
    @Synchronized
    fun bufferedMs(): Int {
        if (!active) return 0
        var frames = 0
        for (b in queue) frames += b.size
        val cur = current
        if (cur != null) frames += cur.size - framePos.toInt().coerceIn(0, cur.size)
        return frames * 1000 / SRC_SAMPLE_RATE
    }

    @Synchronized
    fun bufferedBlocks(): Int = queue.size

    /** 顺序消费;返回写入的字节数。队列空时提前返回,由 Provider 补满。 */
    fun fillResampled(out: ByteArray, offset: Int, size: Int, dstSampleRate: Int, dstChannels: Int): Int {
        synchronized(this) {
            if (!active) return 0
            val dstFrameSize = dstChannels.coerceAtLeast(1) * 2
            val dstFrames = size / dstFrameSize
            if (dstFrames <= 0 || dstSampleRate <= 0) return 0
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            var outPos = offset
            var produced = 0
            val step = SRC_SAMPLE_RATE.toDouble() / dstSampleRate.toDouble()
            for (i in 0 until dstFrames) {
                var cur = current
                if (cur == null || framePos >= cur.size) {
                    if (queue.isEmpty()) break
                    cur = queue.removeFirst()
                    if (framePos >= cur.size) framePos = 0.0
                    current = cur
                }
                val i0 = framePos.toInt().coerceIn(0, cur.size - 1)
                val i1 = (i0 + 1).coerceAtMost(cur.size - 1)
                val frac = (framePos - i0).toFloat()
                val s0 = cur[i0].toInt()
                val s1 = cur[i1].toInt()
                val sample = (s0 + (s1 - s0) * frac).toInt().toShort()
                repeat(dstChannels.coerceAtLeast(1)) {
                    bb.putShort(outPos, sample)
                    outPos += 2
                }
                produced += dstFrameSize
                framePos += step
            }
            return produced
        }
    }
}
