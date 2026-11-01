package com.chudad.game.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 程序化音效引擎：用正弦波 + 指数衰减包络合成 PCM，经 AudioTrack (USAGE_GAME) 播放。
 *
 * 相比直接使用 ToneGenerator 的电信令音，合成音更圆润细腻（柔和起音、泛音层次、
 * 低频"厚度"），且全部在后台线程生成与写入，不阻塞绘制/触摸线程。
 *
 * 播放链路有四条硬约束，都是 2026-11 重写时才补上的（旧实现违反后会导致"永久嘟嘟声"）：
 *
 *  1. **AudioTrack 延迟创建、失败可重试**。旧实现在构造函数里判定一次 `useAudioTrack`，
 *     失败即整个引擎生命周期降级为提示音，且异常被 `catch (_: Exception) {}` 吞掉。
 *     结果是「一次构造失败 = 这一整局都是嘟嘟声」，线上还查不到原因。
 *  2. **失败必须留痕**。所有失败路径都打一行 Log（TAG = ChuDaD-Audio），包含 state /
 *     buffer / 异常类型，便于用 logcat 定位。
 *  3. **释放前必须等在飞的任务结束，且先 pause+flush 再 release**。旧实现 `shutdown()`
 *     之后立刻 `track.release()`，而此时音频线程可能正阻塞在 `write()` 里，两个线程同时
 *     操作同一个 AudioTrack —— 这是明确的数据竞争，会让 AudioFlinger 侧的 track 泄漏，
 *     逐次累积后耗尽每个 uid 的 track 配额，此后**所有**新建 AudioTrack 都返回
 *     STATE_UNINITIALIZED，永久嘟嘟。
 *  4. **写入用非阻塞 + 代次检查**。新音效可以立刻顶掉还在播的旧音效（不排队成一条长尾），
 *     也不会因为 `write()` 永久阻塞而卡住 `release()`。
 *
 * 降级策略：AudioTrack 不可用时退回 ToneGenerator 提示音——宁可音色差，也不要没声音。
 */
class SoundEngine {

    private companion object {
        const val TAG = "ChuDaD-Audio"
        const val SAMPLE_RATE = 44100
        /** 新建 AudioTrack 失败后的重试冷却：太短会每声都撞一次失败，太长则恢复慢 */
        const val TRACK_RETRY_MS = 3000L
        /** 写入失败（track 已死）后的重建冷却：这种情况多半是新 track 能立刻建起来，不必等 3 秒 */
        const val WRITE_FAIL_RETRY_MS = 500L
        /** 非阻塞写入的轮询间隔，同时决定「新音效顶掉旧音效」的响应粒度 */
        const val WRITE_POLL_MS = 4L
        /** 释放时等待音频线程退出的上限 */
        const val RELEASE_WAIT_MS = 400L
        /** 单段音频写入的最长耗时（安全阀：track 卡死时不会永久占住音频线程） */
        const val MAX_WRITE_MS = 5000L
    }

    /**
     * 音效清单。
     *
     * @param fallbackTone 降级为 ToneGenerator 时用的提示音
     * @param fallbackDurMs 降级提示音时长
     * @param minGapMs 同一音效的最小触发间隔（0 = 不去重）。滑动选牌会逐张触发，
     *   不去重的话 13 张会排出 1.4 秒的声音尾巴，手指早就离开了。
     */
    private enum class Sfx(val fallbackTone: Int, val fallbackDurMs: Int, val minGapMs: Long) {
        TAP_SELECT(ToneGenerator.TONE_PROP_ACK, 40, 110),
        TAP_DESELECT(ToneGenerator.TONE_PROP_NACK, 30, 110),
        CARD_PLAY(ToneGenerator.TONE_PROP_BEEP, 80, 0),
        STRONG_PLAY(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300, 0),
        WIN(ToneGenerator.TONE_CDMA_PIP, 200, 0),
        LOSE(ToneGenerator.TONE_PROP_NACK, 200, 0),
        LAST_CARD(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 260, 0),
        ERROR(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 150, 0),
        CONFIRM(ToneGenerator.TONE_PROP_ACK, 50, 0)
    }

    /** 保护 track / toneGenerator 的创建与销毁（音频线程创建、主线程释放） */
    private val lock = Any()

    @Volatile private var released = false

    @Volatile private var track: AudioTrack? = null

    /**
     * 播放代次：每发起一个新音效自增。音频线程在写入循环里比对它，
     * 一旦发现被更新的音效取代就立刻退出，实现「后来者优先」。
     */
    @Volatile private var generation = 0

    /** 下次允许尝试新建 AudioTrack 的时刻（uptimeMillis）；0 = 随时可以 */
    @Volatile private var nextCreateAllowedMs = 0L

    private var toneGenerator: ToneGenerator? = null

    /** PCM 缓存：合成一次反复用（仅音频线程访问，无需加锁） */
    private val pcmCache = HashMap<Sfx, ShortArray>()

    /** 每种音效上次触发时刻（用于去重，调用线程访问） */
    private val lastPlayMs = LongArray(Sfx.values().size)

    /** 串行执行音频任务的单线程调度器（避免并发写 AudioTrack） */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SoundEngine").apply { isDaemon = true }
    }

    init {
        // 预热最常见的两个音效，避免第一次出牌时才在音频线程上做合成。
        // 注意：这里**不**创建 AudioTrack —— 创建推迟到首次播放，失败还能在下次重试。
        submit {
            pcmFor(Sfx.CARD_PLAY)
            pcmFor(Sfx.TAP_SELECT)
        }
    }

    // ==================== 对外音效接口（均异步，调用线程零开销） ====================

    /** 轻触选牌：两声上行短"嗒"音，干脆清晰 */
    fun tapSelect() = play(Sfx.TAP_SELECT)

    /** 取消选牌：一声下行短音，与选中形成方向对比 */
    fun tapDeselect() = play(Sfx.TAP_DESELECT)

    /** 出牌：模拟纸牌甩上桌面的"唰"声（噪声滑音 + 低频闷响） */
    fun cardPlay() = play(Sfx.CARD_PLAY)

    /** 四带一/同花顺：低频轰鸣 + 渐弱噪声爆炸 */
    fun strongPlay() = play(Sfx.STRONG_PLAY)

    /** 胜利：上行琶音 C-E-G-C + 长尾和音，明亮庆祝感 */
    fun win() = play(Sfx.WIN)

    /** 失败：下行三连音，柔和不刺耳（长辈友好） */
    fun lose() = play(Sfx.LOSE)

    /** 剩最后一张提醒：双音急促提示 */
    fun lastCardAlert() = play(Sfx.LAST_CARD)

    /** 错误：低沉双"咚"，避免尖锐蜂鸣 */
    fun error() = play(Sfx.ERROR)

    /** 确认：清脆的单击"叮" */
    fun confirm() = play(Sfx.CONFIRM)

    // ==================== 播放链路 ====================

    private fun play(key: Sfx) {
        if (released) return
        val now = SystemClock.uptimeMillis()
        if (key.minGapMs > 0 && now - lastPlayMs[key.ordinal] < key.minGapMs) return
        lastPlayMs[key.ordinal] = now
        generation++
        val gen = generation
        submit { playNow(key, gen) }
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // 已 release，正常运行期不会走到
        }
    }

    /** 音频线程：优先合成路径，AudioTrack 不可用或写入失败则降级为提示音 */
    private fun playNow(key: Sfx, gen: Int) {
        if (released) return
        val t = obtainTrack()
        if (t != null) {
            if (writeAll(t, pcmFor(key), gen)) return
            dropTrack("写入失败：${key.name}")
        }
        playFallback(key)
    }

    /**
     * 取一个可用的 AudioTrack：有就复用，没有就新建，建失败则进入 3 秒冷却。
     *
     * 这是「失败可恢复」的核心——旧实现只在构造时试一次，失败即永久降级。
     * 典型的可恢复场景：回前台的瞬间系统音频服务正忙、被其它 App 占满，几百毫秒后就正常了。
     */
    private fun obtainTrack(): AudioTrack? {
        track?.let { t ->
            if (t.state == AudioTrack.STATE_INITIALIZED) return t
            discardTrack("state 已失效")   // 这种失效与资源无关，不冷却，立刻重建
        }
        if (SystemClock.uptimeMillis() < nextCreateAllowedMs) return null
        synchronized(lock) {
            if (released) return null
            track?.let { return it }
            val created = createTrack()
            if (created == null) {
                nextCreateAllowedMs = SystemClock.uptimeMillis() + TRACK_RETRY_MS
                return null
            }
            track = created
            nextCreateAllowedMs = 0
            return created
        }
    }

    /** 新建 AudioTrack；成功返回实例，失败返回 null 并打日志（不再静默吞异常） */
    private fun createTrack(): AudioTrack? {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // getMinBufferSize 返回的是「字节」。旧实现写成 coerceAtLeast(sampleRate / 4)，
            // 即 11025 字节 = 5512 采样 ≈ 125ms —— 把缓冲下限抬到了 125ms，每声都慢半拍。
            // 这里改用 2 倍最小缓冲（通常 40~80ms）：足够防 underrun，又不拖延迟。
            val base = if (minBuf > 0) minBuf else SAMPLE_RATE / 25 * 2   // 兜底 40ms
            val bufBytes = (base * 2).coerceAtLeast(4096)
            val t = AudioTrack(
                attrs, fmt, bufBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (t.state == AudioTrack.STATE_INITIALIZED) {
                Log.i(TAG, "AudioTrack 就绪：minBuf=$minBuf bytes, buffer=$bufBytes bytes")
                return t
            }
            Log.w(TAG, "AudioTrack 未初始化（state=${t.state}, buffer=$bufBytes），降级为提示音")
            try { t.release() } catch (_: Exception) {}
            return null
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack 创建失败，降级为提示音：${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    /** 写入失败后丢弃 track，短暂冷却后重建（避免「每声都新建一次、每声都失败」的空转） */
    private fun dropTrack(reason: String) {
        discardTrack(reason)
        nextCreateAllowedMs = SystemClock.uptimeMillis() + WRITE_FAIL_RETRY_MS
    }

    /** 丢弃 track 实例（暂停 → 清缓冲 → 释放，三步顺序不能反） */
    private fun discardTrack(reason: String) {
        synchronized(lock) {
            val t = track ?: return
            track = null
            Log.w(TAG, "AudioTrack 丢弃（$reason）")
            try { t.pause(); t.flush(); t.release() } catch (_: Exception) {}
        }
    }

    /**
     * 把整段 PCM 写进 AudioTrack。
     *
     * 旧实现是「先 play() 再阻塞 write()」：起播瞬间缓冲是空的，必然 underrun 一次，
     * 表现为每声之前有一小段静音。这里改为先写满缓冲再起播。
     * 写入用 WRITE_NON_BLOCKING + 轮询，既能在被新音效取代时立刻退出（代次变了），
     * 也不会把 `release()` 卡死在一个永不返回的 `write()` 上。
     *
     * @return true = 正常播完或被取代；false = 写入出错，调用方应降级
     */
    private fun writeAll(t: AudioTrack, buf: ShortArray, gen: Int): Boolean {
        try {
            var off = 0
            val startMs = SystemClock.uptimeMillis()
            while (off < buf.size) {
                // 被更新的音效取代：补一段淡出再退出。直接 return 会让波形从中间断掉，
                // 听感上是一声「啪」的爆音；淡出 12ms 后自然收尾。
                if (released || gen != generation) {
                    if (off > 0) fadeOut(t, buf, off)
                    return true
                }
                if (SystemClock.uptimeMillis() - startMs > MAX_WRITE_MS) {
                    Log.w(TAG, "写入超时（${buf.size} 采样），放弃剩余部分")
                    if (off > 0) fadeOut(t, buf, off)
                    return true
                }
                val n = t.write(buf, off, buf.size - off, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) {
                    Log.w(TAG, "AudioTrack.write 返回错误码 $n")
                    return false
                }
                if (n > 0) {
                    off += n
                    continue
                }
                // 缓冲已满：没起播就先起播，已起播就等消费者腾出空间。
                // 这里**不能** flush：flush 会把上一声还没播完的部分直接砍掉（爆音）。
                // 「后来者优先」由上面的代次检查实现，它最多让新音效晚一个缓冲（约 40~80ms）。
                // 每次都查实时 playState：stop() 可能在中途把 track 暂停，
                // 缓存的状态会让这里一直 sleep 到超时
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    t.play()
                } else {
                    Thread.sleep(WRITE_POLL_MS)
                }
            }
            // 整段比缓冲还短时（一次就写完），此时还没起播
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            return true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack 写入异常：${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    /** 把 buf 从 from 起的一小段按线性包络降到 0 后写入（约 12ms），用于中途收尾防爆音 */
    private fun fadeOut(t: AudioTrack, buf: ShortArray, from: Int) {
        val n = minOf(buf.size - from, SAMPLE_RATE / 80)
        if (n <= 0) return
        val tail = ShortArray(n)
        for (i in 0 until n) {
            tail[i] = (buf[from + i] * (1.0 - i.toDouble() / n)).toInt().toShort()
        }
        var off = 0
        while (off < n) {
            val w = try {
                t.write(tail, off, n - off, AudioTrack.WRITE_NON_BLOCKING)
            } catch (_: Exception) {
                return
            }
            if (w <= 0) return
            off += w
        }
    }

    /** ToneGenerator 兜底（音色是电信令音，但保证「有声音」而不是静默） */
    private fun playFallback(key: Sfx) {
        val tg = synchronized(lock) {
            if (released) return
            toneGenerator ?: try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 100).also {
                    toneGenerator = it
                    Log.i(TAG, "已启用提示音兜底")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ToneGenerator 创建失败（完全静音）：${e.javaClass.simpleName}: ${e.message}")
                null
            }
        } ?: return
        try {
            tg.startTone(key.fallbackTone, key.fallbackDurMs)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator.startTone 失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ==================== 生命周期 ====================

    /** 暂停播放并清空缓冲（切后台调用）。保留 track 实例，回前台无需重建。 */
    fun stop() {
        generation++          // 让音频线程放弃当前音效
        synchronized(lock) {
            try {
                track?.let { t ->
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                    t.flush()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 回到前台：清除失败冷却。
     *
     * 后台期间系统可能回收掉我们的 track，或曾经因资源紧张失败过；
     * 不清冷却的话，回前台后的前几声还会继续用提示音。
     */
    fun onForeground() {
        nextCreateAllowedMs = 0
    }

    /** 彻底释放（视图销毁时调用，不要绑在 surface 上） */
    fun release() {
        if (released) return
        released = true
        generation++
        // 先暂停并清空：正在轮询写入的音频线程才能尽快退出
        try {
            track?.let { t ->
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                t.flush()
            }
        } catch (_: Exception) {}
        executor.shutdown()
        try {
            if (!executor.awaitTermination(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "音频线程未在 ${RELEASE_WAIT_MS}ms 内退出，仍继续释放 track")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lock) {
            val t = track
            track = null
            try { t?.release() } catch (_: Exception) {}
            try { toneGenerator?.release() } catch (_: Exception) {}
            toneGenerator = null
        }
    }

    // ==================== PCM 合成（仅音频线程调用） ====================

    private fun pcmFor(key: Sfx): ShortArray = pcmCache.getOrPut(key) {
        val d = synth(key)
        ShortArray(d.size) { i ->
            (d[i] * 32767.0).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
    }

    private fun synth(key: Sfx): DoubleArray = when (key) {
        Sfx.TAP_SELECT -> tones(listOf(980.0 to 0.05, 1320.0 to 0.06))
        Sfx.TAP_DESELECT -> tones(listOf(760.0 to 0.05))
        Sfx.CARD_PLAY -> sweepNoise(durSec = 0.13, fStart = 5200.0, fEnd = 1500.0, gain = 0.5) +
                thud(150.0, 0.09, 0.5)
        Sfx.STRONG_PLAY -> {
            val boom = DoubleArray((SAMPLE_RATE * 0.55).toInt())
            addInto(boom, thud(55.0, 0.45, 0.95))     // 低频冲击
            addInto(boom, thud(110.0, 0.30, 0.55))
            addInto(boom, noiseBurst(0.45, 0.6, decayFast = true))   // 爆炸噪声（前重后轻）
            boom
        }
        Sfx.WIN -> {
            val seq = tones(
                listOf(
                    523.25 to 0.14,   // C5
                    659.25 to 0.14,   // E5
                    783.99 to 0.14,   // G5
                    1046.5 to 0.34    // C6（长一点收尾）
                )
            )
            val tail = mix(listOf(note(523.25, 0.5, 0.25), note(659.25, 0.5, 0.2), note(783.99, 0.5, 0.2)))
            concat(seq, offset(tail, (seq.size * 0.75).toInt()))
        }
        Sfx.LOSE -> tones(listOf(440.0 to 0.16, 349.23 to 0.16, 261.63 to 0.34))
        Sfx.LAST_CARD -> concat(tones(listOf(1560.0 to 0.09)), tones(listOf(1980.0 to 0.12)))
        Sfx.ERROR -> concat(
            thud(160.0, 0.10, 0.7),
            offset(thud(140.0, 0.12, 0.7), (SAMPLE_RATE * 0.13).toInt())
        )
        Sfx.CONFIRM -> tones(listOf(1046.5 to 0.10))
    }

    // ==================== DSP 基础构件 ====================

    /** 单个音符：基频 + 两次泛音，指数衰减包络，柔和起音防爆音 */
    private fun note(freq: Double, durSec: Double, gain: Double): DoubleArray {
        val n = (SAMPLE_RATE * durSec).toInt()
        val out = DoubleArray(n)
        val tau = durSec / 5.0
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toDouble()
            val env = exp(-t / tau) * (1.0 - exp(-t / 0.004))
            val s = sin(2 * PI * freq * t) +
                    0.35 * sin(2 * PI * freq * 2 * t) +
                    0.12 * sin(2 * PI * freq * 3 * t)
            out[i] = s * env * gain / 1.5
        }
        return out
    }

    /** 一组音符顺序拼接（旋律） */
    private fun tones(seq: List<Pair<Double, Double>>): DoubleArray {
        var result = DoubleArray(0)
        for ((f, d) in seq) result = concat(result, note(f, d, 0.55))
        return result
    }

    /** 低频闷响（无高频成分，像拍桌子） */
    private fun thud(freq: Double, durSec: Double, gain: Double): DoubleArray {
        val n = (SAMPLE_RATE * durSec).toInt()
        val out = DoubleArray(n)
        val tau = durSec / 6.0
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toDouble()
            out[i] = sin(2 * PI * freq * t) * exp(-t / tau) * (1.0 - exp(-t / 0.003)) * gain
        }
        return out
    }

    /** 噪声下滑音：模拟纸牌摩擦空气的"唰" */
    private fun sweepNoise(durSec: Double, fStart: Double, fEnd: Double, gain: Double): DoubleArray {
        val n = (SAMPLE_RATE * durSec).toInt()
        val out = DoubleArray(n)
        var lp = 0.0
        var phase = 0.0
        for (i in 0 until n) {
            val k = i / n.toDouble()
            val env = sin(PI * k) // 起收皆淡
            // 一阶低通随频率下移，白噪声过带通近似"嘶——"
            val white = Random.Default.nextDouble() * 2.0 - 1.0
            val alpha = (2 * PI * (fStart + (fEnd - fStart) * k * k) / SAMPLE_RATE).coerceAtMost(0.5)
            lp += alpha * (white - lp)
            phase += 2 * PI * 180.0 / SAMPLE_RATE
            out[i] = (lp * 2.2 + 0.15 * sin(phase)) * env * gain
        }
        return out
    }

    /** 噪声爆炸（decayFast：前半段能量集中） */
    private fun noiseBurst(durSec: Double, gain: Double, decayFast: Boolean): DoubleArray {
        val n = (SAMPLE_RATE * durSec).toInt()
        val out = DoubleArray(n)
        var lp = 0.0
        for (i in 0 until n) {
            val k = i / n.toDouble()
            val white = Random.Default.nextDouble() * 2.0 - 1.0
            val alpha = 0.25
            lp += alpha * (white - lp)
            val env = if (decayFast) exp(-k * 6.0) else exp(-k * 3.0)
            out[i] = lp * 2.0 * env * gain
        }
        return out
    }

    // ==================== 数组工具 ====================

    private fun concat(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    private fun offset(a: DoubleArray, skip: Int): DoubleArray =
        if (skip <= 0) a else if (skip >= a.size) DoubleArray(0) else a.copyOfRange(skip, a.size)

    private fun mix(parts: List<DoubleArray>): DoubleArray {
        val len = parts.maxOf { it.size }
        val out = DoubleArray(len)
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out.map { it.coerceIn(-1.0, 1.0) }.toDoubleArray()
    }

    private fun addInto(dst: DoubleArray, src: DoubleArray) {
        val n = minOf(dst.size, src.size)
        for (i in 0 until n) dst[i] = (dst[i] + src[i]).coerceIn(-1.0, 1.0)
    }
}
