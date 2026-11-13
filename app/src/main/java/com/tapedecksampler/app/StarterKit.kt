package com.tapedecksampler.app

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/*
 * Synthesizes the default samples a fresh project gets. Each cassette color family
 * has its own kit; every voice is rendered from oscillators, noise and filters at runtime.
 */
object StarterKit {
    const val LOUDNESS = 0.3f
    const val SUSTAIN = 0.15f
    const val CEILING = 0.92f
    const val CHILL = 0
    const val ANALOG = 1
    const val FART = 2
    const val HOUSE = 3
    const val BREAKCORE = 4
    const val TOYS = 5
    const val ROSE = 6
    const val ANGELIC = 7
    const val DARK = 8
    const val METAL = 9
    const val TOXIC = 10
    const val EMO = 11
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
    private const val SLOTS = 24
    private const val BALANCE_WINDOW_DIVISOR = 10
    private const val SPEAKER_HZ = 150.0
    private const val BALANCE_TARGET = 0.2
    private const val MAX_OVERDRIVE = 2.5
    private const val KNEE = 0.6f
    private val RATE = Tape.RATE.toDouble()
    @JvmField val BPM = intArrayOf(90, 100, 100, 124, 170, 110, 108, 80, 85, 160, 170, 140)
    private val CLEAN_PARTIALS = doubleArrayOf(1.0, 0.45, 0.22, 0.12, 0.06, 0.03)
    private val FMAJ7 = doubleArrayOf(87.31, 130.81, 164.81, 220.0, 261.63, 329.63)
    private val DARK_TWINKLE = doubleArrayOf(110.0, 164.81, 246.94, 196.0, 87.31, 130.81, 220.0, 164.81)
    private val METAL_HZ = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)
    private val BREAK_KICKS = setOf(0, 2, 10, 11)
    private val BREAK_SNARES = setOf(4, 7, 9, 12, 15)
    private val NAMES = arrayOf(
        arrayOf("Kick", "Tom", "Lofi snare", "Closed hat", "Shaker", "Keys", "Rhodes pad", "Soft bass"),
        arrayOf("Kick", "Low tom", "Snare", "Closed hat", "Open hat", "Clap", "Cowbell", "Conga"),
        arrayOf("Kick", "Tom", "Snare", "Closed hat", "Open hat", "Clap", "Trill", "Tuba"),
        arrayOf("Kick", "Clap", "Closed hat", "Open hat", "Rimshot", "Acid bass", "Chord stab", "Ride"),
        arrayOf("Break", "Gabber kick", "Amen snare", "Snare roll", "Stutter", "Reese bass", "Rave stab", "Reverse crash"),
        arrayOf("Toy drum", "Rattle", "Plink", "Toy piano", "Toy phone", "Squeaker", "Kitten", "Chick"),
        arrayOf("Kick", "Snare", "Triangle", "Piano", "Plink", "Toy phone", "Lamb", "Dove"),
        arrayOf("Kick", "Snare", "Organ", "Singing bowl", "Breath", "Choir", "Shimmer", "Swell"),
        arrayOf("Kick", "Snare", "Hat", "Rumble", "Drone", "Clang", "Growl", "Bass"),
        arrayOf("Kick", "Snare", "Hat", "China", "Tom", "Chug", "Power chord", "Bass"),
        arrayOf("Two-step break", "Gabber kick", "Amen snare", "Snare roll", "Stutter", "Reese bass", "Rave stab", "Reverse crash"),
        arrayOf("Kick", "Snare", "Hat", "Ghost snare", "Twinkle", "Strum", "Lead", "Bass"))

    @JvmStatic fun kitFor(color: Int): Int = when (color) {
        0 -> EMO
        24 -> ANGELIC
        1 -> DARK
        8 -> TOYS
        9 -> ROSE
        in 2..7 -> CHILL
        12 -> BREAKCORE
        13 -> TOXIC
        in 10..17 -> HOUSE
        19 -> FART
        23 -> METAL
        else -> ANALOG
    }

    @JvmStatic fun names(kit: Int): Array<String> = NAMES[kit]

    @JvmStatic
    @JvmOverloads
    @Throws(java.io.IOException::class)
    fun seed(tape: Tape, sequencer: Sequencer, kit: Int = CHILL) {
        sequencer.tempo(BPM[kit])
        var position = 0
        for (voice in 0 until Sequencer.TRACKS) {
            val sound = balance(sound(kit, voice))
            val data = ShortArray(sound.size) { (sound[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort() }
            tape.write(position, data, data.size)
            sequencer.tracks[voice].start = position
            sequencer.tracks[voice].end = position + data.size
            position += data.size
        }
        tape.flush()
    }

    @JvmStatic fun sound(kit: Int, voice: Int): FloatArray {
        val seed = 133L + kit * 31L + voice
        return when (kit) {
            ANALOG -> analog(voice, seed)
            FART -> fart(voice, seed)
            HOUSE -> house(voice, seed)
            BREAKCORE -> breakcore(voice, seed)
            TOYS -> toys(voice, seed)
            ROSE -> rose(voice, seed)
            ANGELIC -> angelic(voice, seed)
            DARK -> dark(voice, seed)
            METAL -> metal(voice, seed)
            EMO -> emo(voice, seed)
            TOXIC -> if (voice == 0) twoStep(seed) else breakcore(voice, seed)
            else -> chill(voice, seed)
        }
    }

    private fun chill(voice: Int, seed: Long): FloatArray = when (voice) {
        2 -> render(0.45, seed) { t ->
            val pop = if (chance(0.0015)) noise() * 0.2 else 0.0
            room(smooth(1, smooth(0, noise(), 3500.0), 3500.0) * rise(t, 0.002) * decay(t, 9.0) + sin(2 * PI * 185 * t) * decay(t, 22.0) * 0.4 + pop)
        }
        4 -> render(0.4, seed) { t ->
            val beads = bandpass(0, noise(), 5500.0, 1.2)
            val gesture = (if (t < 0.12) sin(PI * t / 0.12) else 0.0) + (if (t > 0.18) decay(t - 0.18, 14.0) * min(1.0, (t - 0.18) / 0.02) else 0.0)
            room(beads * gesture)
        }
        5 -> render(1.6, seed) { t ->
            var v = 0.0
            for (hz in doubleArrayOf(293.66, 369.99, 440.0, 554.37)) v += sin(2 * PI * hz * t + 1.2 * decay(t, 6.0) * sin(2 * PI * hz * t))
            v * decay(t, 2.5) * (1 + 0.15 * sin(2 * PI * 5 * t))
        }
        6 -> render(2.2, seed) { t ->
            var v = 0.0
            for ((k, hz) in doubleArrayOf(146.83, 220.0, 277.18, 329.63, 369.99).withIndex()) for (copy in 0..1) {
                val tuned = hz * (if (copy == 0) 1.0 else 1.0015)
                v += sine(k * 2 + copy, tuned) + 0.3 * sine(10 + k * 2 + copy, tuned * 2) * decay(t, 3.0)
            }
            room(smooth(1, smooth(0, v * rise(t, 0.45) * min(1.0, (2.2 - t) / 0.6), 1500.0), 1500.0) + noise() * 0.05)
        }
        7 -> render(0.9, seed) { t -> (sine(0, 73.42) + 0.2 * sine(1, 146.83)) * rise(t, 0.01) * decay(t, 2.2) }
        else -> voice(voice, Tape.RATE / 2)
    }

    private fun analog(voice: Int, seed: Long): FloatArray = when (voice) {
        0 -> render(0.8, seed) { t -> drive(sine(0, 50 + 70 * decay(t, 25.0)) * decay(t, 5.0), 1.3) + noise() * decay(t, 600.0) * 0.15 }
        1 -> render(0.5, seed) { t -> sine(0, 90 + 60 * decay(t, 20.0)) * decay(t, 9.0) + lowpass(1, noise(), 1500.0) * decay(t, 40.0) * 0.2 }
        2 -> render(0.4, seed) { t -> (sine(0, 180.0) + 0.6 * sine(1, 330.0)) * decay(t, 20.0) * 0.6 + lowpass(2, highpass(3, noise(), 1800.0), 7000.0) * decay(t, 14.0) }
        3 -> render(0.15, seed) { t -> metal(0) * decay(t, 60.0) }
        4 -> render(0.6, seed) { t -> metal(0) * decay(t, 6.0) }
        5 -> render(0.4, seed) { t ->
            val bursts = decay(t, 120.0) + (if (t > 0.011) decay(t - 0.011, 120.0) else 0.0) + (if (t > 0.022) decay(t - 0.022, 12.0) else 0.0)
            bandpass(0, noise(), 1200.0, 2.0) * bursts * 2.0
        }
        6 -> render(0.4, seed) { t -> bandpass(2, square(0, 540.0) + square(1, 800.0), 800.0, 3.0) * (decay(t, 12.0) + 0.4 * decay(t, 60.0)) }
        else -> render(0.4, seed) { t -> sine(0, 320 + 90 * decay(t, 60.0)) * decay(t, 12.0) }
    }

    private fun fart(voice: Int, seed: Long): FloatArray = squash(fartVoice(voice, seed), 3.0)

    private fun squash(sound: FloatArray, amount: Double): FloatArray {
        var peak = 0f
        for (value in sound) peak = max(peak, abs(value))
        if (peak > 0) for (i in sound.indices) sound[i] = drive(sound[i] / peak.toDouble(), amount).toFloat()
        return sound
    }

    private fun fartVoice(voice: Int, seed: Long): FloatArray = when (voice) {
        0 -> render(0.55, seed) { t -> toot(58 - 22 * t / 0.55, 170.0, 0.3, 0.15, 0.0) * rise(t, 0.01) * decay(t, 4.0) }
        1 -> render(0.45, seed) { t -> toot(125 - 45 * t / 0.45, 330.0, 0.25, 0.2, 0.0) * rise(t, 0.008) * decay(t, 5.0) }
        2 -> render(0.3, seed) { t -> toot(150.0, 520.0, 0.45, 1.2, 0.02) * decay(t, 11.0) + bandpass(9, noise(), 1400.0, 0.7) * decay(t, 25.0) * 0.6 }
        3 -> render(0.12, seed) { t -> toot(420 + 160 * t / 0.12, 1300.0, 0.12, 0.1, 0.0) * rise(t, 0.004) * decay(t, 25.0) }
        4 -> render(0.45, seed) { t -> toot(330 + 260 * t / 0.45, 1100.0, 0.15, 0.2, 0.01) * rise(t, 0.01) * decay(t, 4.0) }
        5 -> render(0.4, seed) { t -> toot(95.0, 450.0, 0.4, 0.9, 0.03) * (decay(t, 25.0) + (if (t > 0.09) decay(t - 0.09, 14.0) else 0.0)) }
        6 -> render(0.8, seed) { t -> toot(26 + 6 * sin(2 * PI * 3 * t), 280.0, 0.35, 0.3, 0.015) * rise(t, 0.01) * (1 - t / 0.8) }
        else -> render(1.4, seed) { t -> toot((68 - 14 * t) * (1 + 0.06 * sin(2 * PI * 4.5 * t)), 240.0, 0.3, 0.25, 0.012) * sin(PI * min(1.0, t / 1.4)).pow(0.5) }
    }

    private fun house(voice: Int, seed: Long): FloatArray = when (voice) {
        0 -> render(0.5, seed) { t -> drive(sine(0, 55 + 160 * decay(t, 40.0)) * decay(t, 7.0), 1.5) + highpass(1, noise(), 3000.0) * decay(t, 300.0) * 0.4 }
        1 -> render(0.5, seed) { t ->
            val bursts = decay(t, 150.0) + (if (t > 0.009) decay(t - 0.009, 150.0) else 0.0) + (if (t > 0.018) decay(t - 0.018, 10.0) else 0.0)
            val clap = bandpass(0, noise(), 1500.0, 1.6) * bursts * 2.0
            clap + echo(1, clap, 47.0, 0.4) * 0.3
        }
        2 -> render(0.12, seed) { t -> (highpass(0, noise(), 7000.0) * 0.6 + metal(1) * 0.5) * decay(t, 45.0) }
        3 -> render(0.5, seed) { t -> (highpass(0, noise(), 7000.0) * 0.6 + metal(1) * 0.5) * decay(t, 7.0) }
        4 -> render(0.12, seed) { t -> bandpass(0, triangle(1, 500.0) + noise() * 0.6, 1700.0, 4.0) * decay(t, 60.0) * 2.0 }
        5 -> render(0.5, seed) { t -> drive(lowpass(1, saw(0, 55.0), 180 + 2600 * decay(t, 9.0), 7.0), 2.5) * decay(t, 4.0) }
        6 -> render(0.9, seed) { t ->
            var v = 0.0
            var slot = 0
            for (hz in doubleArrayOf(220.0, 261.63, 329.63, 392.0)) { v += saw(slot++, hz) + saw(slot++, hz * 1.006) }
            val stab = lowpass(8, v * 0.25, 400 + 3000 * decay(t, 12.0), 1.5) * decay(t, 5.0)
            stab + echo(9, stab, 45000.0 / BPM[HOUSE], 0.35) * 0.5
        }
        else -> render(1.2, seed) { t -> ride(t, 3.0) }
    }

    private fun breakcore(voice: Int, seed: Long): FloatArray = when (voice) {
        0 -> {
            val step = 60.0 / BPM[BREAKCORE] / 4
            render(step * 16, seed) { t ->
                val n = (t / step).toInt()
                val local = t - n * step
                var v = 0.0
                if (n in BREAK_KICKS) v += sin(2 * PI * (52 * local + 120 / 35.0 * (1 - exp(-35 * local)))) * decay(local, 9.0)
                if (n in BREAK_SNARES) {
                    val ghost = if (n == 7 || n == 9) 0.45 else 1.0
                    v += ghost * (noise() * decay(local, 22.0) * 0.8 + sin(2 * PI * 190 * local) * decay(local, 30.0) * 0.6)
                }
                val hat = highpass(0, noise(), 6000.0) * decay(local, 45.0) * 0.35
                if (n % 2 == 0) v += hat
                lowpass(1, drive(v, 1.8), 9000.0)
            }
        }
        1 -> render(0.5, seed) { t -> drive(sine(0, 50 + 200 * decay(t, 25.0)) * decay(t, 5.0), 6.0) }
        2 -> render(0.3, seed) { t -> drive(bandpass(0, noise(), 3000.0, 0.7) * decay(t, 18.0) * 1.4 + (sin(2 * PI * 205 * t) * decay(t, 25.0) + 0.5 * sin(2 * PI * 330 * t) * decay(t, 35.0)) * 0.7, 1.6) }
        3 -> {
            val hits = ArrayList<Double>()
            var at = 0.0
            var gap = 0.13
            while (at < 1.15) { hits.add(at); at += gap; gap = max(0.018, gap * 0.87) }
            render(1.25, seed) { t ->
                val local = t - (hits.lastOrNull { it <= t } ?: 0.0)
                (0.35 + 0.65 * t / 1.25) * (highpass(0, noise(), 1000.0) * decay(local, 30.0) + sin(2 * PI * 200 * local) * decay(local, 35.0) * 0.6)
            }
        }
        4 -> {
            val slice = 60.0 / BPM[BREAKCORE] / 8
            render(slice * 12, seed) { t ->
                val n = if (t < slice * 8) (t / slice).toInt() else 8 + ((t - slice * 8) / (slice / 2)).toInt()
                val local = if (n < 8) t - n * slice else t - slice * 8 - (n - 8) * slice / 2
                val pitch = 1 + n * 0.06
                lowpass(1, drive((noise() * decay(local, 30.0) * 0.8 + sin(2 * PI * 190 * pitch * local) * decay(local, 28.0) * 0.7) * (0.6 + n * 0.04), 2.0), 9000.0)
            }
        }
        5 -> render(1.0, seed) { t -> drive(lowpass(3, saw(0, 55.0) + saw(1, 55.6) + saw(2, 54.5), 500 + 150 * sin(2 * PI * 0.8 * t), 1.2), 2.0) * rise(t, 0.02) * min(1.0, (1.0 - t) / 0.25) }
        6 -> render(0.6, seed) { t ->
            var v = 0.0
            var slot = 0
            for (hz in doubleArrayOf(261.63, 311.13, 392.0, 523.25)) { v += saw(slot++, hz) + saw(slot++, hz * 1.008) + 0.6 * sine(slot++, hz * 2) }
            drive(lowpass(14, v * 0.3, 1500 + 3500 * decay(t, 8.0), 1.2), 1.5) * decay(t, 6.0)
        }
        else -> render(1.6, seed) { t -> (highpass(0, noise(), 4000.0) * 0.8 + metal(1) * 0.4) * rise(t, 0.002) * decay(t, 2.2) }.reversedArray()
    }

    private fun emo(voice: Int, seed: Long): FloatArray = when (voice) {
        0 -> render(0.6, seed) { t -> room(sine(0, 58 + 80 * decay(t, 35.0)) * decay(t, 9.0) + bandpass(1, noise(), 2500.0, 1.0) * decay(t, 150.0) * 0.5) }
        1 -> render(0.6, seed) { t ->
            val hit = (sin(2 * PI * 190 * t) + 0.5 * sin(2 * PI * 340 * t)) * decay(t, 18.0) * 0.6 + bandpass(1, noise(), 2200.0, 0.6) * decay(t, 12.0)
            room(hit + highpass(2, noise(), 4000.0) * decay(t, 7.0) * 0.25)
        }
        2 -> render(0.3, seed) { t -> room((metal(0) * 0.6 + highpass(8, noise(), 6000.0) * 0.5) * (decay(t, 14.0) + 0.3 * decay(t, 4.0))) }
        3 -> render(0.35, seed) { t -> room(bandpass(0, noise(), 2400.0, 0.8) * decay(t, 20.0) * 0.8 + sin(2 * PI * 200 * t) * decay(t, 30.0) * 0.3) }
        4 -> {
            val step = 60.0 / BPM[EMO] / 4
            val bar = (step * 16 * RATE).roundToInt()
            val tremolo = 8 / (step * 16)
            val twoBars = render((bar * 2 + 0.5) / RATE, seed, false) { t ->
                var v = 0.0
                for (copy in 0..1) for (n in DARK_TWINKLE.indices) {
                    val local = t - copy * step * 16 - n * step * 2
                    if (local < 0 || local > 2.5) continue
                    var tone = 0.0
                    for ((k, amp) in CLEAN_PARTIALS.withIndex()) {
                        val hz = DARK_TWINKLE[n] * (k + 1)
                        tone += amp * (sin(2 * PI * hz * local) + 0.5 * sin(2 * PI * hz * 1.0035 * local)) * decay(local, 1.0 + 0.8 * k)
                    }
                    v += tone * min(1.0, local / 0.006) * (0.85 + 0.15 * sin(2 * PI * tremolo * t))
                }
                lowpass(17, lowpass(16, room(v), 1800.0), 1800.0) + noise() * 0.004
            }
            twoBars.copyOfRange(bar, bar * 2)
        }
        5 -> render(2.6, seed) { t ->
            var v = 0.0
            for ((string, hz) in FMAJ7.withIndex()) {
                val local = t - string * 0.06
                if (local >= 0) v += cleanTone(hz, local, 0.8) * min(1.0, local / 0.005) * decay(local, 0.9)
            }
            room(lowpass(17, lowpass(16, v, 1700.0), 1700.0) + noise() * 0.004)
        }
        6 -> render(1.8, seed) { t ->
            val hz = 329.63 * (1 + 0.006 * sin(2 * PI * 5.5 * t) * min(1.0, t / 0.4))
            var v = 0.0
            for ((k, amp) in CLEAN_PARTIALS.withIndex()) v += amp * (sine(k, hz * (k + 1)) + 0.5 * sine(6 + k, hz * (k + 1) * 1.0035)) * decay(t, 0.6 * k)
            room(lowpass(17, lowpass(16, v * min(1.0, t / 0.006) * decay(t, 1.1), 2400.0), 2400.0) + noise() * 0.004)
        }
        else -> render(1.6, seed) { t -> room(lowpass(17, lowpass(16, cleanTone(55.0, t, 1.4) * min(1.0, t / 0.004) * decay(t, 1.6), 900.0), 900.0) + noise() * 0.004) }
    }

    private fun cleanTone(hz: Double, t: Double, partialDecay: Double): Double {
        var v = 0.0
        for ((k, amp) in CLEAN_PARTIALS.withIndex()) {
            val partial = hz * (k + 1)
            v += amp * (sin(2 * PI * partial * t) + 0.5 * sin(2 * PI * partial * 1.0035 * t)) * decay(t, partialDecay * k)
        }
        return v
    }

    private fun Gen.room(x: Double): Double = x + (echo(20, x, 23.0, 0.45) + echo(21, x, 37.0, 0.4) + echo(22, x, 53.0, 0.35)) * 0.18

    private fun twoStep(seed: Long): FloatArray {
        val step = 60.0 / BPM[TOXIC] / 4
        return render(step * 16, seed) { t ->
            val n = (t / step).toInt().coerceAtMost(15)
            val local = t - n * step
            var v = 0.0
            if (n == 0 || n == 10) v += sin(2 * PI * (50 * local + 140 / 40.0 * (1 - exp(-40 * local)))) * decay(local, 8.0)
            if (n == 4 || n == 12) v += bandpass(0, noise(), 2800.0, 0.7) * decay(local, 16.0) * 1.3 + sin(2 * PI * 200 * local) * decay(local, 25.0) * 0.6
            if (n == 7 || n == 14) v += bandpass(0, noise(), 3000.0, 0.8) * decay(local, 35.0) * 0.4
            val shuffle = if (n % 2 == 1) step * 0.12 else 0.0
            if (local > shuffle) v += highpass(1, noise(), 7000.0) * decay(local - shuffle, 50.0) * (if (n % 2 == 1) 0.4 else 0.25)
            lowpass(6, drive(v, 1.6), 10000.0)
        }
    }

    private fun toys(voice: Int, seed: Long): FloatArray = when (voice) {
        0 -> render(0.3, seed) { t -> sine(0, 220 + 60 * decay(t, 40.0)) * decay(t, 18.0) + bandpass(1, noise(), 2500.0, 2.0) * decay(t, 120.0) }
        1 -> {
            var grain = 0.0
            render(0.4, seed) { t ->
                val shake = decay(t, 9.0) + (if (t > 0.18) decay(t - 0.18, 12.0) else 0.0)
                if (chance(0.006 * shake)) grain = noise()
                grain *= 0.993
                bandpass(0, grain + noise() * 0.02 * shake, 4000.0, 2.0)
            }
        }
        2 -> render(0.5, seed) { t -> (sin(2 * PI * 1318.5 * t) + 0.4 * sin(2 * PI * 1318.5 * 3.9 * t) * decay(t, 30.0)) * decay(t, 12.0) }
        3 -> render(0.9, seed) { t -> toyPiano(t, 880.0) }
        4 -> render(1.0, seed) { t -> ringPhone(t) }
        5 -> render(0.3, seed) { t ->
            val hz = 1200 + 500 * sin(PI * t / 0.3) * (1 - t / 0.6)
            bandpass(1, square(0, hz, 0.3), hz * 1.5, 3.0) * sin(PI * t / 0.3) + bandpass(2, noise(), 3000.0, 2.0) * 0.15 * sin(PI * t / 0.3)
        }
        6 -> render(0.75, seed) { t ->
            val u = t / 0.75
            val src = saw(0, 600 + 250 * sin(PI * u) - 120 * u)
            (bandpass(1, src, 350 + 500 * sin(PI * min(1.0, u * 1.3)), 5.0) + 0.7 * bandpass(2, src, 2300 - 1300 * u, 7.0)) * rise(t, 0.05) * (1 - u).pow(0.6)
        }
        else -> render(0.45, seed) { t -> chirps(t) }
    }

    private fun rose(voice: Int, seed: Long): FloatArray = when (voice) {
        0 -> {
            var held = 0.0
            render(0.45, seed) { t ->
                val v = sine(0, 65 + 70 * decay(t, 30.0)) * decay(t, 9.0) + lowpass(1, noise(), 800.0) * decay(t, 15.0) * 0.6
                if ((t * RATE).toInt() % 4 == 0) held = crush(drive(v, 2.0), 6)
                held
            }
        }
        1 -> {
            var held = 0.0
            render(0.5, seed) { t ->
                val v = lowpass(0, noise(), 6000.0) * decay(t, 8.0) + sin(2 * PI * 190 * t) * decay(t, 30.0) * 0.5 + noise() * 0.04
                if ((t * RATE).toInt() % 3 == 0) held = crush(drive(v, 1.6), 5)
                held
            }
        }
        2 -> render(1.8, seed) { t ->
            val partials = doubleArrayOf(1803.0, 2067.0, 2848.0, 2912.0, 3710.0)
            val amps = doubleArrayOf(1.0, 0.45, 0.33, 0.3, 0.15)
            var v = 0.0
            for (k in partials.indices) v += amps[k] * sin(2 * PI * partials[k] * t) * decay(t, 1.5 + k * 0.4)
            v + highpass(0, noise(), 5000.0) * decay(t, 200.0) * 0.5
        }
        3 -> render(1.7, seed) { t ->
            var v = 0.0
            for (n in 1..8) v += (sin(2 * PI * 440 * n * t) + sin(2 * PI * 441.3 * n * t)) * 0.5 / n.toDouble().pow(1.3) * decay(t, 1.0 + 0.7 * n)
            lowpass(1, v + bandpass(0, noise(), 2500.0, 1.0) * decay(t, 60.0) * 0.3, 6000.0) + noise() * 0.01
        }
        4 -> render(0.45, seed) { t -> (sin(2 * PI * 1766 * t) + 0.3 * sin(2 * PI * 1766 * 2.7 * t) * decay(t, 20.0)) * decay(t, 10.0) + noise() * decay(t, 80.0) * 0.1 }
        5 -> render(0.6, seed) { t ->
            val beep = (t / 0.2).toInt()
            val local = t - beep * 0.2
            val hz = doubleArrayOf(1696.0, 1061.0, 1340.0)[min(2, beep)]
            if (local < 0.15) lowpass(0, square(1, hz, 0.35), 5000.0) * minOf(1.0, local / 0.004, (0.15 - local) / 0.01) else lowpass(0, 0.0, 5000.0)
        }
        6 -> render(0.9, seed) { t ->
            val u = t / 0.9
            val tremolo = 0.75 + 0.25 * sin(2 * PI * 7 * t)
            val src = saw(0, 380 * (1 + 0.04 * sin(2 * PI * 7 * t)) * (1 - 0.15 * u)) * tremolo
            (bandpass(1, src, 750.0, 4.0) + 0.8 * bandpass(2, src, 1500 - 300 * u, 6.0) + 0.3 * bandpass(3, src, 2600.0, 8.0)) * rise(t, 0.06) * (1 - u).pow(0.5)
        }
        else -> render(0.9, seed) { t ->
            val coo = if (t < 0.42) 0 else 1
            val local = t - coo * 0.42
            if (local > 0.35) lowpass(1, 0.0, 900.0) else
                lowpass(1, sine(0, (if (coo == 0) 420.0 else 400.0) - 60 * local / 0.35) + bandpass(2, noise(), 600.0, 1.0) * 0.15, 900.0) * sin(PI * local / 0.35)
        }
    }

    private fun angelic(voice: Int, seed: Long): FloatArray = when (voice) {
        0 -> render(0.6, seed) { t -> val v = sine(0, 50 + 60 * decay(t, 20.0)) * decay(t, 6.0); v + echo(1, v, 61.0, 0.5) * 0.25 }
        1 -> render(0.8, seed) { t ->
            val v = lowpass(0, highpass(1, noise(), 2000.0), 9000.0) * decay(t, 10.0)
            v + (echo(2, v, 37.0, 0.6) + echo(3, v, 53.0, 0.55) + echo(4, v, 71.0, 0.5)) * 0.35
        }
        2 -> render(2.4, seed) { t ->
            var v = 0.0
            var slot = 0
            for (note in doubleArrayOf(110.0, 164.81)) for ((k, harmonic) in doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0).withIndex()) v += sine(slot++, note * harmonic * (1 + 0.0015 * sin(2 * PI * 5.5 * t))) * doubleArrayOf(1.0, 0.7, 0.4, 0.35, 0.2, 0.15)[k]
            val out = v * rise(t, 0.4) * min(1.0, (2.4 - t) / 0.5)
            out + echo(14, out, 113.0, 0.45) * 0.3
        }
        3 -> render(2.4, seed) { t ->
            var v = 0.0
            for ((k, ratio) in doubleArrayOf(1.0, 2.71, 5.15).withIndex()) {
                val hz = 392.0 * ratio
                v += (sin(2 * PI * hz * t) + sin(2 * PI * (hz + 0.6 + k * 0.4) * t)) * decay(t, 0.8 + k * 0.7) / (k + 1)
            }
            v * rise(t, 0.15)
        }
        4 -> render(2.2, seed) { t ->
            val air = noise()
            val v = bandpass(0, air, 440.0, 30.0) + bandpass(1, air, 659.26, 30.0) + 0.7 * bandpass(2, air, 880.0, 30.0) + lowpass(3, highpass(4, air, 2000.0), 6000.0) * 0.05
            v * rise(t, 0.6) * min(1.0, (2.2 - t) / 0.6)
        }
        5 -> render(2.2, seed) { t ->
            var v = 0.0
            var slot = 0
            for ((n, hz) in doubleArrayOf(220.0, 329.63, 440.0, 554.37).withIndex()) {
                val vibrato = 1 + 0.004 * sin(2 * PI * 5.2 * t + n)
                for (detune in doubleArrayOf(0.997, 1.0, 1.003)) v += saw(slot++, hz * detune * vibrato)
            }
            v *= 0.1
            val voice = bandpass(12, v, 800.0, 5.0) + 0.7 * bandpass(13, v, 1150.0, 6.0) + 0.3 * bandpass(14, v, 2900.0, 8.0)
            val out = voice * rise(t, 0.4) * min(1.0, (2.2 - t) / 0.5)
            out + echo(15, out, 89.0, 0.5) * 0.3
        }
        6 -> render(1.8, seed) { t ->
            val v = (sine(0, 880.0) + 0.7 * sine(1, 1318.5) + 0.5 * sine(2, 1760.0)) * (0.8 + 0.2 * sin(2 * PI * 6 * t)) * rise(t, 0.5) * min(1.0, (1.8 - t) / 0.4)
            v + echo(5, v, 300.0, 0.5) * 0.4
        }
        else -> render(1.5, seed) { t ->
            val v = sine(0, 220.0) + sine(1, 277.18) + sine(2, 329.63) + lowpass(3, noise(), 3000.0) * 0.4
            v * (t / 1.5).pow(3.0)
        }
    }

    private fun dark(voice: Int, seed: Long): FloatArray = when (voice) {
        0 -> render(0.7, seed) { t -> drive(sine(0, 45 + 90 * decay(t, 20.0)) * decay(t, 4.0), 4.0) + lowpass(1, noise(), 300.0) * decay(t, 10.0) }
        1 -> render(0.5, seed) { t -> drive(bandpass(0, noise(), 900.0, 0.8) * 1.5 + highpass(1, noise(), 4000.0) * 0.4, 2.5) * decay(t, 7.0) }
        2 -> render(0.2, seed) { t -> bandpass(0, metal(1) + noise() * 0.5, 5000.0, 2.0) * decay(t, 30.0) }
        3 -> render(1.6, seed) { t -> (lowpass(0, noise(), 120.0) * 8 + sine(1, 38.0) * (0.6 + 0.4 * sin(2 * PI * 3 * t))) * rise(t, 0.4) * min(1.0, (1.6 - t) / 0.3) }
        4 -> render(2.0, seed) { t -> lowpass(3, saw(0, 55.0) + saw(1, 55.4) + saw(2, 82.5), 300 + 250 * sin(2 * PI * 0.7 * t), 2.0) * rise(t, 0.2) * min(1.0, (2.0 - t) / 0.4) }
        5 -> render(1.2, seed) { t ->
            var v = 0.0
            for ((k, hz) in doubleArrayOf(311.0, 467.0, 739.0, 1120.0, 1489.0).withIndex()) v += sin(2 * PI * hz * t) / (k + 1)
            drive(v * decay(t, 3.0), 2.0)
        }
        6 -> render(1.1, seed) { t ->
            val hz = 55 * (1 + 0.03 * sin(2 * PI * 6 * t))
            val src = drive(saw(0, hz) + saw(1, hz * 1.01) + noise() * 0.3, 3.0)
            val vowel = 0.5 - 0.5 * cos(PI * min(1.0, t / 0.8))
            val v = bandpass(2, src, 300 + 500 * vowel, 4.0) + 0.7 * bandpass(3, src, 900 + 600 * vowel, 5.0) + 0.5 * lowpass(4, src, 200.0)
            drive(v * 2, 2.5) * rise(t, 0.03) * min(1.0, (1.1 - t) / 0.3)
        }
        else -> render(0.9, seed) { t -> drive(lowpass(1, square(0, 41.2), 400.0) + sine(2, 41.2), 2.0) * rise(t, 0.005) * decay(t, 2.0) }
    }

    private fun metal(voice: Int, seed: Long): FloatArray = when (voice) {
        0 -> render(0.3, seed) { t -> sine(0, 60 + 100 * decay(t, 60.0)) * decay(t, 14.0) + bandpass(1, noise(), 3500.0, 1.5) * decay(t, 200.0) * 0.8 }
        1 -> render(0.4, seed) { t -> bandpass(0, noise(), 2500.0, 0.7) * decay(t, 14.0) + (sine(1, 210.0) + 0.6 * sine(2, 330.0)) * decay(t, 20.0) * 0.6 }
        2 -> render(0.15, seed) { t -> metal(0) * decay(t, 40.0) }
        3 -> render(1.4, seed) { t -> drive(bandpass(8, metal(0) + highpass(9, noise(), 3000.0), 5000.0, 0.8) * 1.5, 1.8) * decay(t, 3.5) }
        4 -> render(0.6, seed) { t -> sine(0, 110 + 50 * decay(t, 15.0)) * decay(t, 6.0) }
        5 -> render(0.35, seed) { t -> lowpass(2, drive(pluck(0, 82.41, 0.97) + pluck(1, 164.81, 0.96) * 0.5, 12.0), 2500.0) * decay(t, 8.0) }
        6 -> render(1.6, seed) { t ->
            val v = drive((pluck(0, 82.41, 0.999) + pluck(1, 123.47, 0.999) + pluck(2, 164.81, 0.999)) * 1.2, 8.0)
            lowpass(4, lowpass(3, v, 3200.0, 0.9), 5000.0) * min(1.0, (1.6 - t) / 0.3)
        }
        else -> render(0.9, seed) { t -> lowpass(1, drive(pluck(0, 41.2, 0.998) * 1.5, 2.0), 900.0) }
    }

    private fun Gen.metal(base: Int): Double {
        var sum = 0.0
        for (k in METAL_HZ.indices) sum += square(base + k, METAL_HZ[k])
        return highpass(base + 6, highpass(base + 7, sum / METAL_HZ.size, 6500.0), 6500.0)
    }

    private fun Gen.toot(hz: Double, formant: Double, rough: Double, wet: Double, sputter: Double): Double {
        val wobble = (smooth(20, noise(), 8.0) * 60).coerceIn(-2.0, 2.0)
        val pulse = flap(hz * (1 + 0.08 * wobble), rough)
        val body = bandpass(1, pulse, formant, 2.5) + 0.6 * bandpass(5, pulse, formant * 2.4, 3.5) + 0.8 * lowpass(2, pulse * 4, formant * 0.5)
        val air = bandpass(3, noise(), formant * 3, 0.8) * wet * (0.3 + abs(smooth(22, body, 40.0)) * 20).coerceAtMost(1.5)
        return (body + air * 0.08) * gap(sputter)
    }

    private fun Gen.ride(t: Double, rate: Double): Double {
        var v = 0.0
        for ((k, hz) in doubleArrayOf(3020.0, 4230.0, 5170.0, 6210.0, 7400.0, 8120.0).withIndex()) v += sin(2 * PI * hz * t) / (k + 1)
        return v * 0.4 * decay(t, rate) + highpass(0, noise(), 8000.0) * decay(t, rate * 1.6) * 0.4
    }

    private fun Gen.toyPiano(t: Double, hz: Double): Double =
        (sin(2 * PI * hz * t) + 0.5 * sin(2 * PI * hz * 2 * t) + 0.3 * sin(2 * PI * hz * 5.4 * t) * decay(t, 15.0)) * decay(t, 4.0) +
            bandpass(0, noise(), 3000.0, 2.0) * decay(t, 150.0) * 0.4

    private fun Gen.ringPhone(t: Double): Double {
        val ringing = t < 0.38 || (t > 0.55 && t < 0.93)
        val clapper = if (sin(2 * PI * 22 * t) > 0) 1.0 else 0.4
        val tone = 0.6 * square(0, 1938.0, 0.3) + 0.4 * square(1, 2929.0, 0.4)
        return bandpass(2, if (ringing) tone * clapper else 0.0, 2400.0, 1.2)
    }

    private fun Gen.chirps(t: Double): Double {
        val chirp = (t / 0.13).toInt()
        val local = t - chirp * 0.13
        if (local > 0.07 || chirp > 2) return 0.0
        val shape = sin(PI * local / 0.07)
        return sine(0, 3200 + 1600 * shape + 150 * sin(2 * PI * 60 * local)) * shape * shape
    }

    private fun bell(t: Double, hz: Double): Double {
        var v = 0.0
        for ((k, ratio) in doubleArrayOf(1.0, 2.76, 5.40, 8.93).withIndex()) v += sin(2 * PI * hz * ratio * t) * decay(t, 2.0 + k * 3.0) / (k + 1)
        return v
    }

    private fun decay(t: Double, rate: Double) = exp(-t * rate)
    private fun rise(t: Double, seconds: Double) = min(1.0, t / seconds)
    private fun drive(x: Double, amount: Double) = tanh(x * amount) / tanh(amount)
    private fun crush(x: Double, bits: Int): Double { val levels = (1 shl (bits - 1)).toDouble(); return (x * levels).roundToInt() / levels }

    private fun render(seconds: Double, seed: Long, edges: Boolean = true, shape: Gen.(Double) -> Double): FloatArray {
        val length = (seconds * RATE).toInt()
        val gen = Gen(seed)
        return FloatArray(length) { i ->
            val edge = if (edges) minOf(1.0, i / EDGE_IN_FRAMES, (length - 1 - i) / EDGE_OUT_FRAMES) else 1.0
            (gen.shape(i / RATE) * edge).toFloat()
        }
    }

    private class Gen(seed: Long) {
        private val random = Random(seed)
        private val phases = DoubleArray(SLOTS)
        private val states = DoubleArray(SLOTS)
        private val lows = DoubleArray(SLOTS)
        private val bands = DoubleArray(SLOTS)
        private val lines = arrayOfNulls<DoubleArray>(SLOTS)
        private val heads = IntArray(SLOTS)

        private var flapPhase = 0.0
        private var flapRate = 1.0
        private var flapLevel = 1.0
        private var gapLeft = 0
        private var gapLevel = 1.0

        fun noise() = random.nextDouble() * 2 - 1

        fun flap(hz: Double, roughness: Double): Double {
            flapPhase += hz * flapRate / RATE
            if (flapPhase < 1) return 0.0
            flapPhase -= 1
            flapRate = 1 + roughness * noise()
            flapLevel = 1 - roughness * 0.8 * random.nextDouble()
            return flapLevel
        }

        fun gap(odds: Double): Double {
            if (gapLeft > 0) gapLeft-- else if (chance(odds / 100)) gapLeft = (RATE * (0.006 + 0.02 * random.nextDouble())).toInt()
            gapLevel += ((if (gapLeft > 0) 0.05 else 1.0) - gapLevel) * 0.02
            return gapLevel
        }
        fun chance(odds: Double) = random.nextDouble() < odds
        fun phase(slot: Int, hz: Double): Double { phases[slot] = (phases[slot] + hz / RATE) % 1.0; return phases[slot] }
        fun sine(slot: Int, hz: Double) = sin(2 * PI * phase(slot, hz))
        fun saw(slot: Int, hz: Double) = 2 * phase(slot, hz) - 1
        fun square(slot: Int, hz: Double, width: Double = 0.5) = if (phase(slot, hz) < width) 1.0 else -1.0
        fun triangle(slot: Int, hz: Double) = 1 - 4 * abs(phase(slot, hz) - 0.5)

        fun smooth(slot: Int, x: Double, hz: Double): Double {
            states[slot] += (x - states[slot]) * (1 - exp(-2 * PI * hz / RATE))
            return states[slot]
        }

        fun highpass(slot: Int, x: Double, hz: Double) = x - smooth(slot, x, hz)

        private fun filter(slot: Int, x: Double, hz: Double, q: Double) {
            val f = 2 * sin(PI * min(hz, RATE / 6) / RATE)
            lows[slot] += f * bands[slot]
            bands[slot] += f * (x - lows[slot] - bands[slot] / q)
        }

        fun bandpass(slot: Int, x: Double, hz: Double, q: Double): Double { filter(slot, x, hz, q); return bands[slot] }
        fun lowpass(slot: Int, x: Double, hz: Double, q: Double = 0.7): Double { filter(slot, x, hz, q); return lows[slot] }

        fun echo(slot: Int, x: Double, ms: Double, feedback: Double): Double {
            val line = lines[slot] ?: DoubleArray(max(1, (ms * RATE / 1000).toInt())).also { lines[slot] = it }
            val head = heads[slot]
            val out = line[head]
            line[head] = x + out * feedback
            heads[slot] = (head + 1) % line.size
            return out
        }

        fun pluck(slot: Int, hz: Double, damping: Double): Double {
            val line = lines[slot] ?: DoubleArray(max(2, (RATE / hz).roundToInt())) { noise() }.also { lines[slot] = it }
            val head = heads[slot]
            val next = (head + 1) % line.size
            val out = line[head]
            line[head] = (line[head] + line[next]) * 0.5 * damping
            heads[slot] = next
            return out
        }
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
    fun loudness(sound: FloatArray): Double {
        val window = Tape.RATE / BALANCE_WINDOW_DIVISOR
        val weighted = DoubleArray(sound.size)
        var low1 = 0.0
        var low2 = 0.0
        val coefficient = 1 - exp(-2 * PI * SPEAKER_HZ / RATE)
        for (i in sound.indices) {
            low1 += (sound[i] - low1) * coefficient
            val high = sound[i] - low1
            low2 += (high - low2) * coefficient
            weighted[i] = high - low2
        }
        var loudest = 0.0
        var start = 0
        do {
            val end = min(sound.size, start + window)
            var sum = 0.0
            for (i in start until end) sum += weighted[i] * weighted[i]
            loudest = max(loudest, sqrt(sum / max(1, end - start)))
            start += window / 4
        } while (start + window <= sound.size)
        return loudest
    }

    @JvmStatic
    fun balance(sound: FloatArray): FloatArray {
        val loud = loudness(sound)
        var peak = 0f
        for (value in sound) peak = max(peak, abs(value))
        if (loud <= 0 || peak <= 0) return sound
        val gain = min(BALANCE_TARGET / loud, MAX_OVERDRIVE * CEILING / peak)
        for (i in sound.indices) {
            val x = sound[i] * gain
            val size = abs(x)
            sound[i] = if (size <= KNEE) x.toFloat() else Math.copySign(KNEE + (CEILING - KNEE) * tanh((size - KNEE) / (CEILING - KNEE)), x).toFloat()
        }
        return sound
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
