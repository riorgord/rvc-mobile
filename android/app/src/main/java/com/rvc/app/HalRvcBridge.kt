package com.rvc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * HAL 桥接:连接 root 中继 rvc_relay 的 TCP loopback(127.0.0.1:24681)
 *
 * 链路:
 *   HAL(audioserver,AF_UNIX 于 /data/vendor/audio/rvc.sock)
 *   <- root 中继 rvc_relay(Magisk/KSU 模块 service.sh 开机自启,双向转发)
 *   <- 本对象(TCP 127.0.0.1:24681)
 *
 * 为什么绕一道:
 *   - HAL 建不了 AF_INET socket(系统 EPERM,无 avc),只能用 AF_UNIX;
 *   - App 因 MCS 连不上系统 s0 目录的 Unix socket,但可以连 TCP loopback。
 *   - 中继由模块开机自启,App 不需要 root/su(兼容用户隐藏 su)。
 *
 * 数据流:
 *   HAL read() 拿真麦 -> 本对象读到 PCM -> 重采样 48k float -> RVCStream
 *   -> RVC 输出 40k float -> 重采样回 HAL 流的采样率/声道/格式 -> 写回 socket
 *
 * 不在线/没跟上时 HAL 自动透传,这里只需要保证"连上就干活、断开就重连"。
 */
object HalRvcBridge {

    private const val TAG = "RvcHalBridge"
    private const val HOST = "127.0.0.1"
    private const val PORT = 24681
    // root 中继由 Magisk/KSU 模块 service.sh 开机自启,App 不需要 root/su
    // 动态静音填充:新录音开始后持续往环里填静音,直到第一块真实变声写出去才停。
    // 每块填 FILL_CHUNK_SECONDS 秒(对齐实时消耗),避免开头漏原声也不固定白等。
    private const val FILL_CHUNK_SECONDS = 0.2
    private const val FILL_CHUNK_MS = 200L
    private const val MAGIC = 0x52564331
    private const val RING_LOW_CMD = -2      // 0xFFFFFFFE:SO 通知环快空,请预填静音
    private const val HEADER_SIZE = 20
    private const val BLOCK_48K = 17760      // 370ms @48k
    private const val RVC_OUT_RATE = 40000
    private const val PCM16 = 1              // AUDIO_FORMAT_PCM_16_BIT
    private const val PCM_FLOAT = 4          // AUDIO_FORMAT_PCM_FLOAT (Android 12)

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null

    private var readerThread: Thread? = null
    private var procThread: Thread? = null
    private var fillerThread: Thread? = null

    // 所有 App->HAL 的写(静音填充 + 真实变声)共用一个锁,避免交错
    private val writeLock = Object()
    // Python RVC 状态机锁:stream_push(处理器)与 stream_clear(新段重置)互斥,避免边推边清
    private val pyLock = Object()
    // 第一块真实变声是否已经写出去;写出去后静音填充立即停
    @Volatile private var realOutputReady = false
    @Volatile private var fillerRunning = false

    // 代际计数:每次重置 +1;处理器拿块时记下代际,推理完发现代际变了就丢弃该块输出,
    // 防止"上一段在飞的那一块"在重置后还写进环
    @Volatile private var generation = 0
    // 诊断:stream_push 返回空输出的次数(初始化/积压不足时常见)
    private var emptyOutCount = 0

    private val inBlocks = LinkedBlockingQueue<ByteArray>()

    // 最近一次 HAL 头里的流参数
    @Volatile private var streamSr = 16000
    @Volatile private var streamCh = 1
    @Volatile private var streamFmt = PCM16

    // 输入累积器(48k float 攒满一块才推 Python)
    private val acc = FloatArray(BLOCK_48K)
    private var accLen = 0
    private val accLock = Object()

    // RVC 参数(从 UI 传入)
    @Volatile private var rvcKey = 0
    @Volatile private var rvcRms = 0.75f
    @Volatile private var rvcIdx = 0.75f
    @Volatile private var rvcProt = 0.33f
    @Volatile private var rvcRoleDir: String? = null

    fun isActive(): Boolean = running

    @Synchronized
    fun start(context: Context, key: Int, rms: Float, idx: Float, prot: Float, roleDir: String? = null) {
        if (running) return
        running = true
        rvcKey = key; rvcRms = rms; rvcIdx = idx; rvcProt = prot; rvcRoleDir = roleDir
        inBlocks.clear()
        accLen = 0

        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        } catch (t: Throwable) {
            Log.e(TAG, "Python start failed", t)
        }

        val appCtx = context.applicationContext
        readerThread = Thread({ runReader() }, "rvc-hal-reader")
        procThread = Thread({ runProcessor(appCtx) }, "rvc-hal-proc")
        readerThread!!.start()
        procThread!!.start()
        Log.i(TAG, "started (key=$key rms=$rms idx=$idx prot=$prot)")
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        inBlocks.clear()
        stopSilenceFiller()
        readerThread?.interrupt()
        procThread?.interrupt()
        readerThread = null
        procThread = null
        // 注意:这里不调 stream_cleanup —— gsv 是常驻单例,清理 graph 后下次初始化会坏,
        // 导致 RVC 不出变声、原声漏入。graph 上限问题改用"复用已有流"解决。
        Log.i(TAG, "stopped")
    }

    /* ---------------- 读 socket + 攒块 ---------------- */

    private fun runReader() {
        while (running) {
            var sock: Socket? = null
            try {
                sock = Socket()
                sock.connect(InetSocketAddress(HOST, PORT), 2000)
                socket = sock
                Log.i(TAG, "connected")
                resetStreamState()  // 重连也清一次,防止断线残留
                val input: InputStream = sock.inputStream
                val hdr = ByteArray(HEADER_SIZE)
                while (running) {
                    if (!readFully(input, hdr)) break
                    val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                    val magic = bb.int
                    if (magic != MAGIC) {
                        Log.w(TAG, "bad magic 0x%08x".format(magic))
                        break
                    }
                    val sr = bb.int
                    val ch = bb.int
                    val fmt = bb.int
                    val n = bb.int
                    if (n == RING_LOW_CMD) {
                        // SO 环快空:重启静音预填充(不清 inBlocks,只把 realOutputReady 拉回 false)
                        streamSr = sr; streamCh = ch; streamFmt = fmt
                        realOutputReady = false
                        startSilenceFiller()
                        Log.i(TAG, "ring low -> refill silence")
                        continue
                    }
                    if (n < 0 || n > (1 shl 20)) break
                    if (n == 0) {
                        // HAL 新一段录音开始的重置标记:清掉上一段积压,启动动态静音填充
                        // 注意:这里不再调 stream_clear——清空 RVC 状态会导致冷启动漏原声
                        streamSr = sr; streamCh = ch; streamFmt = fmt
                        Log.i(TAG, "reset marker sr=$sr ch=$ch fmt=$fmt -> reset+filler")
                        resetStreamState()
                        startSilenceFiller()
                        continue
                    }
                    val payload = ByteArray(n)
                    if (!readFully(input, payload)) break
                    if (!running) break
                    streamSr = sr; streamCh = ch; streamFmt = fmt
                    feedPcm(payload, sr, ch, fmt)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "reader: ${e.message}")
            } finally {
                runCatching { sock?.close() }
                socket = null
                if (running) {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    /* 新一段录音/重连时清掉上一段积压:inBlocks 队列 + 48k 采样累加器 */
    private fun resetStreamState() {
        inBlocks.clear()
        synchronized(accLock) {
            accLen = 0
        }
        generation++
        realOutputReady = false
        stopSilenceFiller()
        Log.i(TAG, "stream state reset gen=$generation")
    }

    /* 动态静音填充:持续往环里填静音,直到第一块真实变声写出去才停 */
    private fun startSilenceFiller() {
        if (fillerRunning) return
        fillerRunning = true
        Log.i(TAG, "silence filler start (stream $streamSr/$streamCh/$streamFmt)")
        fillerThread = Thread({
            try {
                while (running && fillerRunning && !realOutputReady) {
                    writeSilenceChunk()
                    if (running && fillerRunning && !realOutputReady) {
                        Thread.sleep(FILL_CHUNK_MS)
                    }
                }
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "silence filler: ${t.message}")
            } finally {
                fillerRunning = false
            }
        }, "rvc-silence-filler")
        fillerThread!!.start()
    }

    private fun stopSilenceFiller() {
        fillerRunning = false
        fillerThread?.interrupt()
        fillerThread = null
    }

    /* 写一小块静音(全 0 对 PCM16/float 都是静音),和真实变声共用写锁 */
    private fun writeSilenceChunk() {
        val s = socket ?: return
        try {
            val bps = if (streamFmt == 4) 4 else 2  // PCM16=2, PCM_FLOAT=4
            val total = (streamSr * streamCh * bps * FILL_CHUNK_SECONDS).toInt().coerceAtLeast(320)
            val zeros = ByteArray(total)
            synchronized(writeLock) {
                if (running && !realOutputReady && s == socket && s.isConnected) {
                    s.outputStream.write(zeros)
                    s.outputStream.flush()
                    Log.i(TAG, "silence chunk ${total}B (${streamSr}/${streamCh}/${streamFmt})")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "silence chunk fail: ${e.message}")
        }
    }

    private fun feedPcm(payload: ByteArray, sr: Int, ch: Int, fmt: Int) {
        val monoF = toMonoFloat(payload, ch, fmt) ?: return
        val up = resampleMono(monoF, sr, 48000)
        if (up.isEmpty()) return
        synchronized(accLock) {
            var i = 0
            while (i < up.size) {
                val n = minOf(up.size - i, BLOCK_48K - accLen)
                System.arraycopy(up, i, acc, accLen, n)
                accLen += n
                i += n
                if (accLen == BLOCK_48K) {
                    val block = ByteArray(BLOCK_48K * 4)
                    ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer().put(acc, 0, BLOCK_48K)
                    inBlocks.put(block)
                    accLen = 0
                }
            }
        }
    }

    /* ---------------- 处理线程:唯一调 Python RVC ---------------- */

    private fun runProcessor(ctx: Context) {
        try {
            ensurePyStream(ctx)
            while (running) {
                val block = inBlocks.poll(1, TimeUnit.MILLISECONDS) ?: continue
                val gen = generation  // 记下这块属于哪一代
                val outBytes: ByteArray = try {
                    synchronized(pyLock) {
                        Python.getInstance().getModule("rvc_api")
                            .callAttr("stream_push", block)
                            .toJava(ByteArray::class.java)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "stream_push fail: ${t.message}", t)
                    continue
                }
                if (outBytes.isEmpty()) {
                    emptyOutCount++
                    if (emptyOutCount <= 50 || emptyOutCount % 50 == 0) {
                        Log.i(TAG, "stream_push empty #$emptyOutCount (gen=$gen inQueue=${inBlocks.size})")
                    }
                    continue
                }
                // 推理期间发生了重置(新一段开始):这块是上一段的,丢弃,不写环、不触发停静音
                if (generation != gen) {
                    Log.i(TAG, "discard stale block (gen $gen -> ${generation})")
                    continue
                }
                val s = socket
                if (s == null || !s.isConnected) continue
                val out = outBytesToHalPcm(outBytes, streamSr, streamCh, streamFmt)
                if (out.isNotEmpty()) {
                    try {
                        synchronized(writeLock) {
                            if (!realOutputReady) {
                                realOutputReady = true
                                Log.i(TAG, "first real output written (gen=$gen bytes=${out.size})")
                            } else {
                                realOutputReady = true
                            }
                            s.outputStream.write(out)
                            s.outputStream.flush()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "send fail: ${e.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "proc: ${t.message}", t)
            notifyCrash(ctx, "变声处理已崩溃(${t.message ?: "未知错误"}),请重新打开 HAL 桥接")
        }
    }

    /* 处理线程/桥接崩溃时,Toast + 通知提醒用户重新打开桥接 */
    private fun notifyCrash(ctx: Context, msg: String) {
        try {
            val appCtx = ctx.applicationContext
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show() }
            }
        } catch (_: Throwable) {}
        try {
            val appCtx = ctx.applicationContext
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "rvc_hal_bridge"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "RVC HAL 桥接", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val notif = Notification.Builder(appCtx, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("RVC HAL 桥接")
                .setContentText(msg)
                .setAutoCancel(true)
                .build()
            nm.notify(1, notif)
        } catch (_: Throwable) {}
    }

    private fun ensurePyStream(ctx: Context) {
        Log.i(TAG, "ensurePyStream: stream_create start (key=$rvcKey role=${rvcRoleDir ?: "files_dir"})")
        val mod = Python.getInstance().getModule("rvc_api")
        mod.callAttr(
            "stream_create",
            ctx.applicationInfo.nativeLibraryDir,
            ctx.filesDir.absolutePath,
            android.os.Process.myUid(),
            false,          // profile
            rvcKey, rvcRms, rvcIdx, rvcProt,
            64, 12,         // f0_win, future
            rvcRoleDir      // role_dir(角色包根目录;null=files_dir 全量)
        )
        // 预热 6 块静音,让 rmvpe/hubert/z/dec 首次 init + 索引加载完成
        val warm = ByteArray(BLOCK_48K * 6 * 4)
        mod.callAttr("stream_push", warm)
        Log.i(TAG, "ensurePyStream: stream_create + warmup done -> RVC stream ready")
    }

    /* ---------------- PCM 转换/重采样 ---------------- */

    private fun toMonoFloat(pcm: ByteArray, ch: Int, fmt: Int): FloatArray? {
        return when (fmt) {
            PCM16 -> {
                val n = pcm.size / 2
                if (n == 0) return null
                val f = FloatArray(n)
                val sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until n) f[i] = sb.get().toInt() / 32768f
                if (ch > 1) averageChannels(f, ch) else f
            }
            PCM_FLOAT -> {
                val n = pcm.size / 4
                if (n == 0) return null
                val f = FloatArray(n)
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f)
                if (ch > 1) averageChannels(f, ch) else f
            }
            else -> null
        }
    }

    private fun averageChannels(f: FloatArray, ch: Int): FloatArray {
        if (ch <= 1) return f
        val n = f.size / ch
        val out = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            for (c in 0 until ch) sum += f[i * ch + c]
            out[i] = sum / ch
        }
        return out
    }

    private fun resampleMono(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return src
        if (src.isEmpty()) return FloatArray(0)
        val dstLen = (src.size.toLong() * dstRate / srcRate).toInt()
        if (dstLen <= 0) return FloatArray(0)
        val out = FloatArray(dstLen)
        val step = srcRate.toDouble() / dstRate.toDouble()
        for (i in 0 until dstLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceIn(0, src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = src[i0] + (src[i1] - src[i0]) * frac
        }
        return out
    }

    private fun resampleShort(src: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate) return src
        if (src.isEmpty()) return ShortArray(0)
        val dstLen = (src.size.toLong() * dstRate / srcRate).toInt()
        if (dstLen <= 0) return ShortArray(0)
        val out = ShortArray(dstLen)
        val step = srcRate.toDouble() / dstRate.toDouble()
        for (i in 0 until dstLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceIn(0, src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = (src[i0] + (src[i1] - src[i0]) * frac).roundToInt().toShort()
        }
        return out
    }

    private fun outBytesToHalPcm(outBytes: ByteArray, dstSr: Int, dstCh: Int, dstFmt: Int): ByteArray {
        val n = outBytes.size / 4
        if (n == 0) return ByteArray(0)
        val f = FloatArray(n)
        ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f)
        val short40 = ShortArray(n) { floatToShort(f[it]) }
        val shortDst = if (dstSr == RVC_OUT_RATE) short40 else resampleShort(short40, RVC_OUT_RATE, dstSr)
        val monoCount = shortDst.size
        val frameSize = if (dstFmt == PCM16) 2 * dstCh else 4 * dstCh
        val out = ByteArray(monoCount * frameSize)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until monoCount) {
            val v = shortDst[i].toInt()
            repeat(dstCh) {
                if (dstFmt == PCM16) bb.putShort(v.toShort())
                else bb.putFloat(v / 32768f)
            }
        }
        return out
    }

    private fun floatToShort(v: Float): Short {
        val c = when {
            v >= 1f -> 32767
            v <= -1f -> -32768
            else -> (v * 32767f).roundToInt()
        }
        return c.toShort()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) return false
            off += r
        }
        return true
    }
}
