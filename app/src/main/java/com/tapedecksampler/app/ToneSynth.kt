package com.tapedecksampler.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

object ToneSynth {
    const val WAVE = 0
    const val NOTE = 1
    const val LENGTH = 2
    const val ATTACK = 3
    const val RELEASE = 4
    const val SWEEP = 5
    const val SINE = 0
    const val TRIANGLE = 1
    const val SAW = 2
    const val SQUARE = 3
    const val NOISE = 4
    private val LEVELS = doubleArrayOf(0.98, 0.98, 0.72, 0.56, 0.6)
    private const val A4_NOTE = 69
    private const val A4_HZ = 440.0
    private const val DECLICK_FRAMES = 48.0
    @JvmField val WAVES = arrayOf("Sine", "Triangle", "Saw", "Square", "Noise")
    @JvmField val LABELS = arrayOf("Wave", "Note", "Length", "Attack", "Release", "Sweep")
    @JvmField val MINIMUM = intArrayOf(0, 24, 50, 0, 0, -36)
    @JvmField val MAXIMUM = intArrayOf(WAVES.size - 1, 96, 5000, 2000, 2000, 36)
    @JvmField val STEP = intArrayOf(1, 1, 50, 10, 10, 1)
    @JvmField val DEFAULTS = intArrayOf(SINE, 60, 500, 0, 100, 0)

    @JvmStatic fun hint(parameter: Int): String = when (parameter) {
        WAVE -> "TONE COLOR / NOISE HAS NO PITCH"
        NOTE -> noteName(MINIMUM[NOTE]) + "-" + noteName(MAXIMUM[NOTE]) + " / STARTING PITCH"
        LENGTH -> "${MINIMUM[LENGTH]}-${MAXIMUM[LENGTH]} ms / TOTAL DURATION"
        ATTACK -> "0-${MAXIMUM[ATTACK]} ms / FADE IN"
        RELEASE -> "0-${MAXIMUM[RELEASE]} ms / FADE OUT"
        else -> "${MINIMUM[SWEEP]} TO +${MAXIMUM[SWEEP]} st / GLIDES TO THE NOTE"
    }

    @JvmStatic fun wave(shape: Int, phase: Double): Double = when (shape) {
        SINE -> sin(2 * PI * phase)
        TRIANGLE -> 1 - 4 * abs(phase - 0.5)
        SAW -> 2 * phase - 1
        else -> if (phase < 0.5) 1.0 else -1.0
    }

    @JvmStatic fun noteName(note: Int) = PitchCorrector.KEYS[note % 12] + (note / 12 - 1)

    @JvmStatic fun display(parameter: Int, value: Int): String = when (parameter) {
        WAVE -> WAVES[value]
        NOTE -> noteName(value)
        SWEEP -> MainActivity.signed(value) + " st"
        else -> "$value ms"
    }

    @JvmStatic fun render(settings: IntArray): ShortArray {
        val count = Tape.RATE / 1000 * settings[LENGTH]
        val result = ShortArray(count)
        val base = A4_HZ * 2.0.pow((settings[NOTE] - A4_NOTE) / 12.0)
        val attack = maxOf(DECLICK_FRAMES, Tape.RATE / 1000.0 * settings[ATTACK])
        val release = maxOf(DECLICK_FRAMES, Tape.RATE / 1000.0 * settings[RELEASE])
        var phase = 0.0
        var random = 0x2545f491
        for (i in 0 until count) {
            val left = 1 - i / count.toDouble()
            phase = (phase + base * 2.0.pow(settings[SWEEP] * left * left / 12.0) / Tape.RATE) % 1
            random = random xor (random shl 13); random = random xor (random ushr 17); random = random xor (random shl 5)
            val value = if (settings[WAVE] == NOISE) random / Int.MAX_VALUE.toDouble() else wave(settings[WAVE], phase)
            val envelope = minOf(1.0, (i + 1) / attack, (count - i) / release)
            result[i] = Math.round(value * envelope * LEVELS[settings[WAVE]] * Short.MAX_VALUE).toInt().toShort()
        }
        return result
    }
}
