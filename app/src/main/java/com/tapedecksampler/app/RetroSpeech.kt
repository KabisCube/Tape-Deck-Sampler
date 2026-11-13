package com.tapedecksampler.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

object RetroSpeech {
    const val MAX_TEXT_LENGTH = 240
    private const val DATA_VERSION = "espeak-ng-1.52.0-voices2"
    private const val DATA_DIRECTORY = "espeak-ng-data"
    private const val OLD_LADY = "oldlady"
    private const val OLD_LADY_TUNE = "\nspeed 85\nintonation 3\nstressLength 170 130 230 230 0 0 400 520\n"
    private val voiceIds = arrayOf("en-us+klatt", "en-us+$OLD_LADY", "en-us+robosoft", "en-us+croak", "en-us+f1", "en-us+whisper")
    val voices = arrayOf("Klatt", "Old lady", "Robot", "Croak", "Female", "Whisper")

    init { System.loadLibrary("retro-speech") }

    @Synchronized
    @Throws(IOException::class)
    fun synthesize(context: Context, text: String, voice: Int): File {
        val phrase = text.trim()
        if (phrase.isEmpty() || phrase.length > MAX_TEXT_LENGTH) throw IOException("Enter up to $MAX_TEXT_LENGTH characters")
        if (phrase.any { it.code > 127 }) throw IOException("Retro speech supports English text only")
        val directory = File(context.filesDir, DATA_VERSION)
        val marker = File(directory, ".ready")
        if (!marker.exists()) {
            ZipInputStream(context.assets.open("speech/espeak-data.zip")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(directory, entry.name)
                    if (!target.canonicalPath.startsWith(directory.canonicalPath + File.separator)) throw IOException("Invalid speech data path")
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            if (!File(directory, "$DATA_DIRECTORY/phontab").exists()) throw IOException("Missing speech data")
            marker.writeText(DATA_VERSION)
        }
        val variants = File(directory, "$DATA_DIRECTORY/voices/!v")
        val tuned = File(variants, "grandma").readText().replace("name grandma", "name $OLD_LADY").replace(Regex("pitch [0-9]+ [0-9]+"), "pitch 105 270") + OLD_LADY_TUNE
        val custom = File(variants, OLD_LADY)
        if (!custom.exists() || custom.readText() != tuned) custom.writeText(tuned)
        val samples = generate(directory.absolutePath, phrase, voiceIds[voice])
        val file = File.createTempFile("speech-", ".wav", context.cacheDir)
        try {
            file.outputStream().use { output ->
                output.write(Tape.header(samples.size.toLong()))
                val bytes = ByteBuffer.allocate(samples.size * Tape.BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asShortBuffer().put(samples)
                output.write(bytes.array())
            }
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private external fun generate(directory: String, text: String, voice: String): ShortArray
}
