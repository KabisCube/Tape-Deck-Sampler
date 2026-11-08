package com.tapedecksampler.app

object TimelineWindow {
    private const val AVERAGE_LENGTHS_EXTRA = 2
    private const val MIN_PADDING_DIVISOR = 20
    private const val PADDING_DIVISOR = 8

    class Overview internal constructor(@JvmField val start: Long, @JvmField val span: Long)

    @JvmStatic
    fun overview(full: Long, regions: List<SampleRegions.Region>, fallback: Long): Overview {
        if (regions.isEmpty()) return Overview(0, minOf(full, fallback))
        val first = minOf(full, regions.minOf { it.start.toLong() })
        val last = maxOf(0L, regions.maxOf { it.end.toLong() })
        val total = regions.sumOf { (it.end - it.start).toLong() }
        val extra = (total * AVERAGE_LENGTHS_EXTRA + regions.size - 1) / regions.size
        val span = minOf(full, last - first + extra)
        return Overview(maxOf(0, minOf(first, full - span)), span)
    }

    private fun paddedLength(start: Long, end: Long): Long {
        val length = maxOf(1, end - start)
        return length + maxOf((Tape.RATE / MIN_PADDING_DIVISOR).toLong(), length / PADDING_DIVISOR) * 2
    }

    @JvmStatic
    fun fit(full: Long, span: Long, start: Long, end: Long): Long = minOf(full, maxOf(span, paddedLength(start, end)))

    @JvmStatic
    fun start(full: Long, span: Long, cursor: Long, clipStart: Long, clipEnd: Long): Long {
        val center = if (span >= minOf(full, paddedLength(clipStart, clipEnd))) clipStart + (clipEnd - clipStart) / 2 else cursor
        return maxOf(0, minOf(full - span, center - span / 2))
    }
}
