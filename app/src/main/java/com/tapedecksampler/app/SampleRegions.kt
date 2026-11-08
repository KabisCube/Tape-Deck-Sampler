package com.tapedecksampler.app

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class SampleRegions {
    class Region(@JvmField val start: Int, @JvmField val end: Int, @JvmField val id: String, @JvmField val name: String, @JvmField val color: Int) {
        init { require(start >= 0 && end <= Tape.LENGTH && end > start) { "Invalid sample region" } }
    }

    private var regions = ArrayList<Region>()
    private val colors = LinkedHashMap<String, Int>()
    private var undo: ArrayList<Region>? = null
    private var revision = 0L

    @Synchronized fun snapshot(): ArrayList<Region> = ArrayList(regions)
    @Synchronized fun revision(): Long = revision
    @Synchronized fun colors(): Map<String, Int> = LinkedHashMap(colors)
    @Synchronized fun restoreColor(id: String, color: Int) { colors[id] = color }

    @Synchronized fun restore(region: Region) {
        colors[region.id] = region.color
        regions.add(region)
        regions.sortBy { it.start }
        revision++
    }

    @Synchronized fun color(id: String): Int = colors[id] ?: nextColor()

    @Synchronized fun checkpoint() { undo = ArrayList(regions) }

    @Synchronized fun undo(): Boolean {
        val previous = undo ?: return false
        undo = regions
        regions = previous
        revision++
        return true
    }

    @Synchronized fun place(start: Int, end: Int, id: String, name: String) {
        val color = colors.getOrPut(id) { nextColor() }
        remove(start, end)
        regions.add(Region(start, end, id, name, color))
        regions.sortBy { it.start }
    }

    @Synchronized fun remove(start: Int, end: Int) {
        val remaining = ArrayList<Region>()
        for (region in regions) {
            if (region.end <= start || region.start >= end) remaining.add(region)
            else {
                if (region.start < start) remaining.add(Region(region.start, start, region.id, region.name, region.color))
                if (region.end > end) remaining.add(Region(end, region.end, region.id, region.name, region.color))
            }
        }
        regions = remaining
        revision++
    }

    private fun nextColor(): Int {
        val hue = (colors.size * GOLDEN_ANGLE + HUE_OFFSET) % 360 / 60
        val x = BRIGHTNESS * (1 - SATURATION * abs(hue % 2 - 1))
        val low = BRIGHTNESS * MIN_CHANNEL
        val high = BRIGHTNESS
        val (r, g, b) = when {
            hue < 1 -> Triple(high, x, low)
            hue < 2 -> Triple(x, high, low)
            hue < 3 -> Triple(low, high, x)
            hue < 4 -> Triple(low, x, high)
            hue < 5 -> Triple(x, low, high)
            else -> Triple(high, low, x)
        }
        return 0xff000000.toInt() or ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
    }

    companion object {
        private const val GOLDEN_ANGLE = 137.508f
        private const val HUE_OFFSET = 35
        private const val BRIGHTNESS = 0.97f
        private const val SATURATION = 0.48f
        private const val MIN_CHANNEL = 0.52f
        private const val HASH_BLOCK_BYTES = 8192

        @JvmStatic
        fun identity(samples: ShortArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BLOCK_BYTES)
            var i = 0
            while (i < samples.size) {
                val count = minOf(buffer.size / 2, samples.size - i)
                for (j in 0 until count) {
                    val value = samples[i++].toInt()
                    buffer[j * 2] = value.toByte()
                    buffer[j * 2 + 1] = (value shr 8).toByte()
                }
                digest.update(buffer, 0, count * 2)
            }
            return "sample:" + digest.digest().joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 255) }
        }
    }
}
