package com.tapedecksampler.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.roundToLong

object MidiExporter {
    private const val PPQ = 480
    private const val STEP_TICKS = PPQ / 4
    private const val PAIR_TICKS = STEP_TICKS * 2
    private const val MIDI_CHANNELS = 9
    private const val FIRST_NOTE = 36
    private const val VELOCITY = 100
    private const val NOTE_ON = 0x90
    private const val NOTE_OFF = 0x80
    private const val GATE_NUMERATOR = 4L
    private const val GATE_DENOMINATOR = 5L
    private const val MICROS_PER_MINUTE = 60_000_000
    private const val MS_PER_MINUTE = 60000.0
    private const val HEADER_LENGTH = 6
    private const val MULTI_TRACK_FORMAT = 1

    private class Event(val tick: Long, val order: Int, val data: ByteArray)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @JvmStatic
    @Throws(IOException::class)
    fun export(output: OutputStream, sequencer: Sequencer) {
        val pages: List<Sequencer.Page>
        synchronized(sequencer) {
            pages = (0 until Sequencer.PAGE_COUNT).map { sequencer.page(it) }.filter { it.enabled }.map { it.copy() }
        }
        val bpm = sequencer.bpm()
        val swing = sequencer.swing()
        val tracks = 1 + sequencer.tracks.size
        output.write(bytes('M'.code, 'T'.code, 'h'.code, 'd'.code, 0, 0, 0, HEADER_LENGTH, 0, MULTI_TRACK_FORMAT, tracks shr 8, tracks, PPQ shr 8, PPQ and 255))
        writeTrack(output, tempoEvents(bpm))
        val pageLength = (0 until Sequencer.STEPS).sumOf { stepLength(it, swing).toLong() }
        for (track in sequencer.tracks.indices) {
            val events = ArrayList<Event>()
            var pageStart = 0L
            for (page in pages) {
                val timing = page.trackEffects[track]
                val groove = if (timing.swingOverride == 0) swing else timing.swingOverride
                val nudge = (timing.nudge * bpm * PPQ / MS_PER_MINUTE).roundToLong()
                var tick = pageStart
                for (step in 0 until Sequencer.STEPS) {
                    val duration = stepLength(step, groove)
                    if ((page.steps[track] and (1 shl step)) != 0) {
                        val channel = track % MIDI_CHANNELS
                        val note = FIRST_NOTE + track
                        val noteOn = maxOf(0, tick + nudge)
                        val noteOff = maxOf(noteOn + 1, tick + nudge + maxOf(1, duration * GATE_NUMERATOR / GATE_DENOMINATOR))
                        events.add(Event(noteOn, 1, bytes(NOTE_ON or channel, note, VELOCITY)))
                        events.add(Event(noteOff, 0, bytes(NOTE_OFF or channel, note, 0)))
                    }
                    tick += duration
                }
                pageStart += pageLength
            }
            writeTrack(output, events)
        }
    }

    private fun stepLength(step: Int, swing: Int): Int = if (step % 2 == 0) PAIR_TICKS * swing / 100 else PAIR_TICKS - PAIR_TICKS * swing / 100

    private fun tempoEvents(bpm: Int): ArrayList<Event> {
        val micros = MICROS_PER_MINUTE / maxOf(1, bpm)
        return arrayListOf(Event(0, 0, bytes(0xff, 0x51, 3, micros shr 16, micros shr 8, micros)))
    }

    private fun writeTrack(output: OutputStream, events: ArrayList<Event>) {
        events.sortWith(compareBy<Event> { it.tick }.thenBy { it.order })
        val data = ByteArrayOutputStream()
        var previous = 0L
        for (event in events) {
            writeVariable(data, event.tick - previous)
            data.write(event.data)
            previous = event.tick
        }
        writeVariable(data, 0)
        data.write(bytes(0xff, 0x2f, 0))
        val track = data.toByteArray()
        val size = track.size
        output.write(bytes('M'.code, 'T'.code, 'r'.code, 'k'.code, size shr 24, size shr 16, size shr 8, size))
        output.write(track)
    }

    private fun writeVariable(output: OutputStream, start: Long) {
        var value = start
        var buffer = (value and 0x7f).toInt()
        while (true) {
            value = value shr 7
            if (value <= 0) break
            buffer = (buffer shl 8) or ((value and 0x7f) or 0x80).toInt()
        }
        while (true) {
            output.write(buffer and 0xff)
            if ((buffer and 0x80) == 0) return
            buffer = buffer shr 8
        }
    }
}
