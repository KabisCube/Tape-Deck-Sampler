package com.tapedecksampler.app

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

object StarterKit {
    const val LOUDNESS = 0.3f
    const val SUSTAIN = 0.15f
    const val CEILING = 0.92f
    private const val KICK = 0
    private const val TOM = 1
    private const val SNARE = 2
    private const val CLOSED_HAT = 3
    private const val OPEN_HAT = 4
    private const val SQUARE_LEAD = 5
    private const val SAW_PAD = 6
    private const val EDGE_IN_FRAMES = 48.0
    private const val EDGE_OUT_FRAMES = 480.0
    private const val RMS_WINDOW_DIVISOR = 20

    @JvmStatic
    @Throws(java.io.IOException::class)
    fun seed(tape: Tape, sequencer: Sequencer) {
        val length = Tape.RATE / 2
        for (voice in 0 until Sequencer.TRACKS) {
            val sound = voice(voice, length)
            val data = ShortArray(length) { (sound[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort() }
            tape.write(voice * length, data, length)
            sequencer.tracks[voice].start = voice * length
            sequencer.tracks[voice].end = (voice + 1) * length
        }
        tape.flush()
    }

    @JvmStatic
    fun voice(voice: Int, length: Int): FloatArray {
        val random = Random(133L + voice)
        val out = FloatArray(length)
        var phase = 0.0
        var phase2 = 0.0
        var lastNoise = 0.0
        var soft = 0.0
        var filter = 0.0
        val rate = Tape.RATE.toDouble()
        for (i in 0 until length) {
            val t = i / rate
            val noise = random.nextDouble() * 2 - 1
            val high = noise - lastNoise
            lastNoise = noise
            soft += (noise - soft) * 0.18
            val value = when (voice) {
                KICK -> {
                    phase += 2 * PI * (47 + 125 * exp(-t * 32)) / rate
                    sin(phase) * exp(-t * 12) + noise * exp(-t * 700) * 0.2
                }
                TOM -> {
                    phase += 2 * PI * (95 + 80 * exp(-t * 30)) / rate
                    sin(phase) * exp(-t * 14)
                }
                SNARE -> {
                    phase += 2 * PI * (175 + 40 * exp(-t * 40)) / rate
                    sin(phase) * exp(-t * 28) + soft * exp(-t * 26)
                }
                CLOSED_HAT -> high * exp(-t * 85)
                OPEN_HAT -> high * exp(-t * 12)
                SQUARE_LEAD -> {
                    phase += 2 * PI * 130.81 / rate
                    val square = if (sin(phase) >= 0) 1.0 else -1.0
                    filter += (square - filter) * 0.08
                    filter
                }
                SAW_PAD -> {
                    phase += 2 * PI * 220 / rate
                    phase2 += 2 * PI * 220.9 / rate
                    val saw = ((phase / PI) % 2 - 1) + ((phase2 / PI) % 2 - 1)
                    filter += (saw - filter) * (0.02 + 0.35 * exp(-t * 9))
                    filter * exp(-t * 4)
                }
                else -> tanh(sin(2 * PI * 55 * t) * 2) * exp(-t * 9)
            }
            val edge = minOf(1.0, i / EDGE_IN_FRAMES, (length - 1 - i) / EDGE_OUT_FRAMES)
            out[i] = (value * edge).toFloat()
        }
        return level(out)
    }

    @JvmStatic
    fun level(sound: FloatArray): FloatArray {
        val window = Tape.RATE / RMS_WINDOW_DIVISOR
        var loudest = 0.0
        var start = 0
        while (start + window <= sound.size) {
            var sum = 0.0
            for (i in start until start + window) sum += sound[i] * sound[i]
            loudest = maxOf(loudest, sqrt(sum / window))
            start += window / 2
        }
        var peak = 0f
        var total = 0.0
        for (value in sound) { peak = maxOf(peak, abs(value)); total += value * value }
        total = sqrt(total / sound.size)
        if (loudest <= 0 || peak <= 0) return sound
        val gain = minOf(LOUDNESS / loudest, SUSTAIN / total, (CEILING / peak).toDouble()).toFloat()
        for (i in sound.indices) sound[i] *= gain
        return sound
    }
}
