package com.leaptv.jump

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 用 AudioTrack 实时合成音效,无需任何音频资源文件。
 *
 * 支持两类声音:
 *  - 持续蓄力音:正弦波,音调随力度从低到高连续升高。
 *  - 一次性短音效:起跳「嗖」(扫频上升)、落地「哒」(低频撞击)。
 */
class SoundEngine {

    private var audioTrack: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    // 持续蓄力音参数。
    @Volatile
    private var chargeFreq = 0f

    @Volatile
    private var chargeAmp = 0f

    // 活跃的一次性短音效(后台线程遍历,主线程触发)。
    private val activeSfx = CopyOnWriteArrayList<ActiveSfx>()

    /** 启动音频线程,开始播放静音流。 */
    @Suppress("DEPRECATION")
    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf.coerceAtLeast(4096),
            AudioTrack.MODE_STREAM
        )
        activeSfx.clear()
        running = true
        audioTrack?.play()
        thread = Thread { run() }.also { it.start() }
    }

    /** 设置蓄力音:ratio 0..1,音调从低到高。ratio<=0 时静音。 */
    fun setCharge(ratio: Float) {
        if (ratio <= 0f) {
            chargeFreq = 0f
            chargeAmp = 0f
        } else {
            chargeFreq = BASE_FREQ + (PEAK_FREQ - BASE_FREQ) * ratio
            chargeAmp = 0.28f
        }
    }

    /** 停止蓄力音。 */
    fun stopCharge() {
        chargeFreq = 0f
        chargeAmp = 0f
    }

    /** 起跳「嗖」:短促的扫频上升音。 */
    fun playSwoosh() {
        activeSfx.add(SwooshSfx())
    }

    /** 落地「哒」:短促的低频撞击音。 */
    fun playLand() {
        activeSfx.add(LandSfx())
    }

    /** 释放音频资源,结束线程。 */
    fun release() {
        running = false
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
            // ignore
        }
        try {
            audioTrack?.stop()
        } catch (_: IllegalStateException) {
            // ignore
        }
        audioTrack?.release()
        audioTrack = null
        thread = null
        activeSfx.clear()
    }

    private fun run() {
        val buffer = ShortArray(1024)
        var phase = 0.0
        var lastAmp = 0f
        while (running) {
            val f = chargeFreq
            val a = chargeAmp
            // 从静音进入有声时重置相位,避免爆音。
            if (lastAmp <= 0f && a > 0f) phase = 0.0
            lastAmp = a

            for (i in buffer.indices) {
                var sample = 0.0
                if (f > 0f && a > 0f) {
                    phase += TWO_PI * f / SAMPLE_RATE
                    if (phase > TWO_PI) phase -= TWO_PI
                    sample += sin(phase) * a
                }
                for (sfx in activeSfx) {
                    sample += sfx.nextSample()
                }
                buffer[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE.toDouble()).toInt().toShort()
            }
            activeSfx.removeAll { it.done }
            try {
                audioTrack?.write(buffer, 0, buffer.size)
            } catch (e: IllegalStateException) {
                break
            }
        }
    }

    /** 一次性短音效基类:维护相位与完成状态。 */
    private abstract class ActiveSfx {
        protected var sampleIndex = 0
        protected var phase = 0.0
        var done = false
            protected set

        protected fun tone(freq: Double): Double {
            phase += TWO_PI * freq / SAMPLE_RATE
            if (phase > TWO_PI) phase -= TWO_PI
            return sin(phase)
        }

        abstract fun nextSample(): Double
    }

    /** 起跳「嗖」:0.32s 内频率从 200Hz 扫到 1000Hz,快速起音后指数衰减。 */
    private class SwooshSfx : ActiveSfx() {
        private val totalSamples = (0.32 * SAMPLE_RATE).toInt()

        override fun nextSample(): Double {
            if (done) return 0.0
            val t = sampleIndex.toDouble() / totalSamples
            val freq = 200.0 + 800.0 * t
            val attack = min(1.0, sampleIndex / (SAMPLE_RATE * 0.01))
            val amp = attack * exp(-3.5 * t) * 0.5
            sampleIndex++
            if (sampleIndex >= totalSamples) done = true
            return tone(freq) * amp
        }
    }

    /** 落地「哒」:0.14s 低频撞击,基频 + 二次谐波,快速衰减。 */
    private class LandSfx : ActiveSfx() {
        private val totalSamples = (0.14 * SAMPLE_RATE).toInt()
        private var phase2 = 0.0

        override fun nextSample(): Double {
            if (done) return 0.0
            val t = sampleIndex.toDouble() / totalSamples
            val amp = exp(-18.0 * t) * 0.6

            val fundamental = tone(150.0)
            phase2 += TWO_PI * 300.0 / SAMPLE_RATE
            if (phase2 > TWO_PI) phase2 -= TWO_PI
            val harmonic = sin(phase2) * 0.4

            sampleIndex++
            if (sampleIndex >= totalSamples) done = true
            return (fundamental + harmonic) * amp
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BASE_FREQ = 180f
        private const val PEAK_FREQ = 800f
        private const val TWO_PI = 2.0 * PI
    }
}
