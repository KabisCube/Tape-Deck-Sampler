package com.tapedecksampler.app

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class CaptureFile @Throws(IOException::class) constructor(path: File) : AutoCloseable {
    private val file = RandomAccessFile(path, "rw")
    private val buffer = ByteArray(8192)
    private var frames = 0

    init {
        file.setLength(Tape.HEADER_BYTES.toLong())
        file.write(Tape.header(0))
    }

    fun frames(): Int = frames

    @Throws(IOException::class)
    fun write(samples: ShortArray, count: Int) {
        val size = minOf(count, samples.size, Tape.LENGTH - frames)
        var offset = 0
        while (offset < size) {
            val block = minOf(size - offset, buffer.size / Tape.BYTES_PER_SAMPLE)
            for (i in 0 until block) {
                val value = samples[offset + i].toInt()
                buffer[i * 2] = value.toByte()
                buffer[i * 2 + 1] = (value shr 8).toByte()
            }
            file.write(buffer, 0, block * Tape.BYTES_PER_SAMPLE)
            offset += block
        }
        frames += size
    }

    @Throws(IOException::class)
    override fun close() {
        file.use {
            it.seek(0)
            it.write(Tape.header(frames.toLong()))
        }
    }
}
