package com.tapedecksampler.app

import kotlin.math.abs

class WaveSummary {
    private val peaks = FloatArray(MAX_PEAKS)
    private var count = 0
    private var stride = INITIAL_STRIDE
    private var pending = 0L
    private var peak = 0f
    private var length = 0L

    fun accept(sample: Short) {
        if (count == peaks.size && pending == 0L) {
            for (i in 0 until count / 2) peaks[i] = maxOf(peaks[i * 2], peaks[i * 2 + 1])
            count /= 2
            stride *= 2
        }
        peak = maxOf(peak, abs(sample.toInt()) / Tape.PCM_SCALE)
        length++
        if (++pending == stride) append()
    }

    private fun append() {
        peaks[count++] = peak
        peak = 0f
        pending = 0
    }

    fun finish() { if (pending > 0) append() }
    fun length(): Long = length

    private companion object {
        const val MAX_PEAKS = 65536
        const val INITIAL_STRIDE = 480L
    }

    fun waveform(start: Long, end: Long, bins: Int): FloatArray {
        val result = FloatArray(bins)
        for (i in 0 until bins) {
            val a = start + (end - start) * i / bins
            val b = start + (end - start) * (i + 1) / bins
            val first = minOf(count.toLong(), a / stride).toInt()
            val last = minOf(count.toLong(), maxOf(first + 1L, (b + stride - 1) / stride)).toInt()
            for (x in first until last) result[i] = maxOf(result[i], peaks[x])
        }
        return result
    }
}
