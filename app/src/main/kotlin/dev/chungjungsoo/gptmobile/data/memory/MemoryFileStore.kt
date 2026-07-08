package dev.chungjungsoo.gptmobile.data.memory

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MemoryFileStore(
    private val paths: MemoryFilePaths,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    fun ensureStore(): Result<MemoryFileStoreSnapshot> = runCatching {
        ensureDirectories()
        val longTermFile = ensureLongTermMemoryFile()
        val todayFile = ensureDailyMemoryFile(LocalDate.now(clock))
        MemoryFileStoreSnapshot(
            rootDirectory = paths.rootDirectory,
            longTermMemoryFile = longTermFile,
            todayMemoryFile = todayFile
        )
    }

    fun readLongTermMemory(): Result<String> = runCatching {
        ensureDirectories()
        ensureLongTermMemoryFile().readText(StandardCharsets.UTF_8)
    }

    fun readDailyMemory(date: LocalDate = LocalDate.now(clock)): Result<String> = runCatching {
        ensureDirectories()
        ensureDailyMemoryFile(date).readText(StandardCharsets.UTF_8)
    }

    fun appendDailyNote(
        text: String,
        date: LocalDate = LocalDate.now(clock)
    ): Result<File> = runCatching {
        ensureDirectories()
        val dailyFile = ensureDailyMemoryFile(date)
        dailyFile.appendText(normalizeAppendedBlock(text), StandardCharsets.UTF_8)
        dailyFile
    }

    fun replaceLongTermMemory(content: String): Result<MemoryFileReplacement> = runCatching {
        ensureDirectories()
        val target = ensureLongTermMemoryFile()
        val backup = backupLongTermMemory(target)
        writeAtomically(target, normalizeFullFileContent(content))
        MemoryFileReplacement(
            file = target,
            backupFile = backup
        )
    }

    private fun ensureDirectories() {
        paths.rootDirectory.mkdirsOrThrow()
        paths.dailyMemoryDirectory.mkdirsOrThrow()
        paths.backupDirectory.mkdirsOrThrow()
    }

    private fun ensureLongTermMemoryFile(): File {
        val file = paths.longTermMemoryFile
        if (!file.exists()) {
            writeAtomically(file, LONG_TERM_MEMORY_HEADER)
        }
        return file
    }

    private fun ensureDailyMemoryFile(date: LocalDate): File {
        val file = paths.dailyMemoryFile(date)
        if (!file.exists()) {
            writeAtomically(file, dailyMemoryHeader(date))
        }
        return file
    }

    private fun backupLongTermMemory(file: File): File {
        val timestamp = LocalDateTime.now(clock).format(BACKUP_TIMESTAMP_FORMATTER)
        val backup = File(paths.backupDirectory, "MEMORY.md.$timestamp.bak")
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return backup
    }

    private fun writeAtomically(file: File, content: String) {
        file.parentFile?.mkdirsOrThrow()
        val tempFile = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        tempFile.writeText(content, StandardCharsets.UTF_8)
        try {
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun File.mkdirsOrThrow() {
        if (!exists() && !mkdirs()) {
            error("Unable to create directory: $absolutePath")
        }
        if (!isDirectory) {
            error("Expected directory but found file: $absolutePath")
        }
    }

    private fun normalizeAppendedBlock(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ""
        return "\n$trimmed\n"
    }

    private fun normalizeFullFileContent(content: String): String =
        content.trimEnd() + "\n"

    private fun dailyMemoryHeader(date: LocalDate): String =
        "# ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}\n\n"

    companion object {
        const val LONG_TERM_MEMORY_HEADER = "# ChatWithChat Memory\n\n"
        private val BACKUP_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

data class MemoryFileStoreSnapshot(
    val rootDirectory: File,
    val longTermMemoryFile: File,
    val todayMemoryFile: File
)

data class MemoryFileReplacement(
    val file: File,
    val backupFile: File
)
