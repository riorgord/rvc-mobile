package io.mo.glassmic.provider

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * P0 测试音源:把一段 WAV(PCM16)灌进 PCM 管道,验证注入链路。
 * 支持单次播放或循环播放;可按目标采样率/声道实时重采样输出。
 */
object PcmTestSource {

    @Volatile private var active = false
    @Volatile private var loop = false
    @Volatile private var data: ByteArray? = null
    @Volatile var sourceSampleRate: Int = 48000
        private set
    @Volatile var sourceChannels: Int = 1
        private set

    private var pos = 0
    private var framePos = 0.0

    fun isActive(): Boolean = active

    /** 加载 WAV 并开始播放(解析 RIFF 找 fmt/data;仅支持 PCM16)。loop=true 时循环。 */
    fun start(path: String, loop: Boolean = false): Boolean {
        return try {
            val raf = RandomAccessFile(path, "r")
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (riff[8] != 'W'.code.toByte() || riff[9] != 'A'.code.toByte() ||
                riff[10] != 'V'.code.toByte() || riff[11] != 'E'.code.toByte()
            ) {
                raf.close(); return false
            }
            var offset = 12L
            var found: ByteArray? = null
            var fmtSr = 48000
            var fmtCh = 1
            var fmtOk = false
            while (true) {
                raf.seek(offset)
                val hdr = ByteArray(8)
                val n = raf.read(hdr)
                if (n < 8) break
                val id = String(hdr, 0, 4, Charsets.US_ASCII)
                val size = (hdr[4].toLong() and 0xFF) or ((hdr[5].toLong() and 0xFF) shl 8) or
                    ((hdr[6].toLong() and 0xFF) shl 16) or ((hdr[7].toLong() and 0xFF) shl 24)
                if (id == "fmt ") {
                    val fmt = ByteArray(size.toInt().coerceIn(16, 64))
                    raf.readFully(fmt)
                    val audioFormat = leShort(fmt, 0)
                    val channels = leShort(fmt, 2)
                    val sampleRate = leInt(fmt, 4)
                    val bits = leShort(fmt, 14)
                    if (audioFormat.toInt() == 1 && bits.toInt() == 16) {
                        fmtSr = sampleRate
                        fmtCh = channels.toInt()
                        fmtOk = true
                    }
                } else if (id == "data") {
                    val d = ByteArray(size.toInt())
                    raf.readFully(d)
                    found = d
                    break
                }
                offset += 8 + size
                if (offset >= raf.length()) break
            }
            raf.close()
            val d = found ?: return false
            if (!fmtOk) return false
            synchronized(this) {
                data = d
                pos = 0
                framePos = 0.0
                sourceSampleRate = fmtSr
                sourceChannels = fmtCh
                this.loop = loop
                active = true
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun stop() {
        synchronized(this) {
            active = false
            loop = false
            data = null
            pos = 0
            framePos = 0.0
        }
    }

    /** 兼容旧调用:直接按源格式复制(不重采样)。 */
    fun fill(out: ByteArray, offset: Int, size: Int): Int {
        synchronized(this) {
            val d = data ?: return 0
            if (pos >= d.size) {
                if (loop) pos = 0
                else {
                    active = false
                    data = null
                    pos = 0
                    return 0
                }
            }
            val copied = minOf(size, d.size - pos)
            System.arraycopy(d, pos, out, offset, copied)
            pos += copied
            if (pos >= d.size && !loop) {
                active = false
                data = null
                pos = 0
            }
            return copied
        }
    }

    /**
     * 按目标采样率/声道重采样输出 PCM16 LE。
     * 源数据来自 WAV 的 fmt(仅 PCM16);线性插值,目标声道不足时复制/平均。
     */
    fun fillResampled(out: ByteArray, offset: Int, size: Int, dstSampleRate: Int, dstChannels: Int): Int {
        synchronized(this) {
            val d = data ?: return 0
            val srcCh = sourceChannels
            val srcSr = sourceSampleRate
            if (srcCh <= 0 || srcSr <= 0 || dstSampleRate <= 0 || dstChannels <= 0) return 0
            val srcFrames = d.size / (2 * srcCh)
            if (srcFrames <= 0) return 0
            val dstFrameSize = dstChannels * 2
            val dstFrames = size / dstFrameSize
            if (dstFrames <= 0) return 0
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            var outPos = offset
            var produced = 0
            for (i in 0 until dstFrames) {
                if (framePos >= srcFrames) {
                    if (!loop) break
                    framePos = 0.0
                }
                val i0 = framePos.toInt().coerceIn(0, srcFrames - 1)
                val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
                val frac = (framePos - i0).toFloat()
                val sample: Short
                if (dstChannels == 1 && srcCh > 1) {
                    var sum0 = 0
                    var sum1 = 0
                    for (c in 0 until srcCh) {
                        sum0 += readSample(d, i0, srcCh, c).toInt()
                        sum1 += readSample(d, i1, srcCh, c).toInt()
                    }
                    val s0 = (sum0 / srcCh).toShort()
                    val s1 = (sum1 / srcCh).toShort()
                    sample = (s0 + (s1 - s0) * frac).toInt().toShort()
                } else {
                    val s0 = readSample(d, i0, srcCh, 0).toInt()
                    val s1 = readSample(d, i1, srcCh, 0).toInt()
                    sample = (s0 + (s1 - s0) * frac).toInt().toShort()
                }
                repeat(dstChannels) {
                    bb.putShort(outPos, sample)
                    outPos += 2
                }
                produced += dstFrameSize
                framePos += srcSr.toDouble() / dstSampleRate.toDouble()
            }
            return produced
        }
    }

    private fun readSample(d: ByteArray, frame: Int, srcCh: Int, channel: Int): Short {
        val srcChannel = if (srcCh == 1) 0 else channel.coerceIn(0, srcCh - 1)
        val idx = frame * srcCh * 2 + srcChannel * 2
        return leShort(d, idx)
    }

    private fun leShort(b: ByteArray, off: Int): Short =
        ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)).toShort()

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
