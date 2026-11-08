package com.tapedecksampler.app

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.ceil

object WavExporter {
    private const val MIN_QUIET_DIVISOR = 4
    private const val ECHO_TAIL_MS = 250
    private const val TAIL_PADDING_DIVISOR = 10
    private const val MAX_TAIL_SECONDS = 600L
    private const val BLOCK_FRAMES = 1024
    private const val QUIET_LEVEL = 2

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun export(destination: File, source: Sequencer, passes: Int = 1) {
        val sequence = source.exportCopy()
        sequence.prepareAll()
        val duration = sequence.playPasses(passes)
        var quietWindow = Tape.RATE / MIN_QUIET_DIVISOR
        var longestVoice = 0L
        for (track in sequence.tracks.indices) {
            val base = sequence.tracks[track]
            if (base.mute || base.gain == 0f) continue
            for (page in 0 until Sequencer.PAGE_COUNT) {
                val pattern = sequence.page(page)
                if (!pattern.enabled) continue
                for (step in 0 until Sequencer.STEPS) {
                    if ((pattern.steps[track] and (1 shl step)) == 0) continue
                    val controls = pattern.padEffects[track][step] ?: base
                    if (controls.mute || controls.gain == 0f) continue
                    longestVoice = maxOf(longestVoice, ceil((base.end - base.start) * 100.0 / controls.speed).toLong())
                    if (controls.powered(Sequencer.Effects.DELAY) && controls.delayMix > 0) quietWindow = maxOf(quietWindow, (controls.delayTime + ECHO_TAIL_MS) * (Tape.RATE / 1000))
                }
            }
        }
        val quietStart = duration + longestVoice + Tape.RATE / TAIL_PADDING_DIVISOR
        val samples = ShortArray(BLOCK_FRAMES)
        val bytes = ByteArray(samples.size * Tape.BYTES_PER_SAMPLE)
        var frames = 0L
        var lastSound = 0L
        var quiet = 0
        try {
            RandomAccessFile(destination, "rw").use { file ->
                file.setLength(Tape.HEADER_BYTES.toLong()); file.seek(Tape.HEADER_BYTES.toLong())
                while (frames < duration + Tape.RATE * MAX_TAIL_SECONDS) {
                    sequence.render(samples)
                    for (i in samples.indices) {
                        val sample = samples[i].toInt()
                        bytes[i * 2] = sample.toByte(); bytes[i * 2 + 1] = (sample shr 8).toByte()
                        if (sample != 0) lastSound = frames + i + 1
                        quiet = if (frames + i < quietStart || abs(sample) > QUIET_LEVEL) 0 else quiet + 1
                    }
                    file.write(bytes)
                    frames += samples.size
                    if (frames >= duration && quiet >= quietWindow && !sequence.hasActiveVoices()) break
                }
                val length = maxOf(duration, lastSound)
                file.setLength(length * Tape.BYTES_PER_SAMPLE + Tape.HEADER_BYTES); file.seek(0); file.write(Tape.header(length))
            }
        } finally { sequence.stop() }
    }
}
