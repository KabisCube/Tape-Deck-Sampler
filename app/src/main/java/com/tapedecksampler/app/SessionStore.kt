package com.tapedecksampler.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

class SessionStore @Throws(IOException::class) constructor(files: File) {
    class Entry internal constructor(@JvmField val directory: File, @JvmField val number: Int) {
        @JvmField val id: String = directory.name
        @JvmField val tape = File(directory, "tape.wav")
        @JvmField val color: String = read("color.txt")
        private val label = read("name.txt").ifEmpty { defaultName(number) }

        private fun read(name: String): String = try { File(directory, name).readText().trim() } catch (e: Exception) { "" }

        fun name(): String = label
    }

    private val root = File(files, "sessions")

    init {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create projects folder")
    }

    @Throws(IOException::class)
    fun list(): ArrayList<Entry> =
        directories().mapNotNull { entry(it) }.filter { it.tape.isFile }.sortedBy { it.number }.toCollection(ArrayList())

    @Throws(IOException::class)
    fun create(): Entry = create("")

    @Throws(IOException::class)
    fun create(name: String?): Entry = create(name, "")

    @Throws(IOException::class)
    fun rename(entry: Entry, name: String?) {
        val label = name?.trim().orEmpty()
        if (label.isEmpty() || label.length > MAX_NAME_LENGTH) throw IOException("Use 1 to $MAX_NAME_LENGTH characters")
        File(entry.directory, "name.txt").writeText(label)
    }

    @Throws(IOException::class)
    fun recolor(entry: Entry, color: String) = File(entry.directory, "color.txt").writeText(color)

    @Throws(IOException::class)
    fun delete(entry: Entry) {
        entry.directory.listFiles()?.forEach { if (!it.delete()) throw IOException("Cannot delete ${it.name}") }
        if (!entry.directory.delete()) throw IOException("Cannot delete project")
    }

    @Throws(IOException::class)
    fun create(name: String?, color: String?): Entry {
        val label = name?.trim().orEmpty()
        if (label.length > MAX_NAME_LENGTH) throw IOException("Use at most $MAX_NAME_LENGTH characters")
        val number = (directories().mapNotNull { entry(it) }.maxOfOrNull { it.number } ?: 0) + 1
        if (number > MAX_PROJECTS) throw IOException("Project limit reached")
        val directory = File(root, folderName(number))
        if (!directory.mkdir()) throw IOException("Cannot create project")
        if (label.isNotEmpty()) File(directory, "name.txt").writeText(label)
        if (!color.isNullOrEmpty()) File(directory, "color.txt").writeText(color)
        return Entry(directory, number)
    }

    @Throws(IOException::class)
    fun migrate(legacy: File): Entry {
        val directory = File(root, folderName(1))
        if (!directory.isDirectory && !directory.mkdir()) throw IOException("Cannot create first project")
        val entry = Entry(directory, 1)
        if (entry.tape.exists()) return entry
        val pending = File(directory, "tape.wav.pending")
        Files.copy(legacy.toPath(), pending.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Tape(pending).use {}
        Files.move(pending.toPath(), entry.tape.toPath())
        return entry
    }

    private fun directories(): Array<File> = root.listFiles(File::isDirectory) ?: throw IOException("Cannot read projects folder")

    private fun entry(directory: File): Entry? {
        val id = directory.name
        if (!id.matches(SESSION_ID)) return null
        val number = id.substring(FOLDER_PREFIX.length).toInt()
        return if (number > 0) Entry(directory, number) else null
    }

    companion object {
        private val DEFAULT_NAME = Regex("Project ([0-9]{2,6})")
        const val MAX_NAME_LENGTH = 48
        private const val MAX_PROJECTS = 999999
        private const val FOLDER_PREFIX = "session-"
        private val SESSION_ID = Regex("$FOLDER_PREFIX[0-9]{6}")

        private fun folderName(number: Int) = String.format(Locale.US, "$FOLDER_PREFIX%06d", number)

        private fun defaultName(number: Int) = String.format(Locale.US, "Project %02d", number)

        @JvmStatic
        fun nextDefaultName(entries: Iterable<Entry>): String {
            var number = 1
            for (entry in entries) DEFAULT_NAME.matchEntire(entry.name())?.let { number = maxOf(number, it.groupValues[1].toInt() + 1) }
            return defaultName(number)
        }
    }
}
