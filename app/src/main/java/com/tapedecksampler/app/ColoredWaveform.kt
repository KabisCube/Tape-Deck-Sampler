package com.tapedecksampler.app

import kotlin.math.abs

object ColoredWaveform {
    const val EMPTY_COLOR = 0xff899580.toInt()

    class Segment internal constructor(@JvmField val start: Int, @JvmField val end: Int, @JvmField val color: Int, @JvmField val peak: Float)

    @JvmStatic
    fun build(tape: Tape, start: Int, end: Int, bins: Int, regions: List<SampleRegions.Region>): List<Segment> {
        require(start >= 0 && end <= Tape.LENGTH && end > start && bins > 0) { "Invalid waveform window" }
        val segments = ArrayList<Segment>(bins + regions.size * 2)
        var regionIndex = 0
        synchronized(tape) {
            for (bin in 0 until bins) {
                var from = start + ((end - start).toLong() * bin / bins).toInt()
                val limit = start + ((end - start).toLong() * (bin + 1) / bins).toInt()
                while (from < limit) {
                    while (regionIndex < regions.size && regions[regionIndex].end <= from) regionIndex++
                    var to = limit
                    var color = EMPTY_COLOR
                    if (regionIndex < regions.size) {
                        val region = regions[regionIndex]
                        if (region.start <= from) { to = minOf(to, region.end); color = region.color }
                        else to = minOf(to, region.start)
                    }
                    var peak = 0
                    for (frame in from until to) peak = maxOf(peak, abs(tape.samples[frame].toInt()))
                    segments.add(Segment(from, to, color, peak / Tape.PCM_SCALE))
                    from = to
                }
            }
        }
        return segments
    }
}
