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

    fun readMemoryFile(file: File): Result<String> = runCatching {
        ensureDirectories()
        file.readText(StandardCharsets.UTF_8)
    }

    fun listMemoryFiles(): Result<List<File>> = runCatching {
        ensureDirectories()
        buildList {
            add(ensureLongTermMemoryFile())
            if (paths.dailyMemoryDirectory.exists()) {
                addAll(
                    paths.dailyMemoryDirectory
                        .listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
                        .orEmpty()
                        .sortedBy { it.name }
                )
            }
        }
    }

    fun relativePath(file: File): Result<String> = runCatching {
        paths.relativePath(file)
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

    fun appendLongTermMemory(text: String): Result<File> = runCatching {
        ensureDirectories()
        val longTermFile = ensureLongTermMemoryFile()
        longTermFile.appendText(normalizeAppendedBlock(text), StandardCharsets.UTF_8)
        longTermFile
    }

    fun replaceLongTermMemory(content: String): Result<MemoryFileReplacement> = runCatching {
        ensureDirectories()
        val target = ensureLongTermMemoryFile()
        replaceMemoryFileInternal(target, content)
    }

    fun replaceMemoryFile(file: File, content: String): Result<MemoryFileReplacement> = runCatching {
        ensureDirectories()
        val target = requireManagedMemoryFile(file)
        if (!target.exists()) error("Memory file does not exist: ${target.absolutePath}")
        replaceMemoryFileInternal(target, content)
    }

    fun restoreMemoryFile(replacement: MemoryFileReplacement): Result<File> = runCatching {
        ensureDirectories()
        val target = requireManagedMemoryFile(replacement.file)
        if (!replacement.backupFile.exists()) {
            error("Memory backup does not exist: ${replacement.backupFile.absolutePath}")
        }
        writeAtomically(target, replacement.backupFile.readText(StandardCharsets.UTF_8))
        target
    }

    private fun replaceMemoryFileInternal(target: File, content: String): MemoryFileReplacement {
        val backup = backupMemoryFile(target)
        writeAtomically(target, normalizeFullFileContent(content))
        return MemoryFileReplacement(
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

    private fun backupMemoryFile(file: File): File {
        val timestamp = LocalDateTime.now(clock).format(BACKUP_TIMESTAMP_FORMATTER)
        val backup = File(paths.backupDirectory, "MEMORY.md.$timestamp.bak")
            .takeIf { file.canonicalFile == paths.longTermMemoryFile.canonicalFile }
            ?: File(paths.backupDirectory, "${file.name}.$timestamp.bak")
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return backup
    }

    private fun requireManagedMemoryFile(file: File): File {
        val rootPath = paths.rootDirectory.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        if (!filePath.startsWith(rootPath)) {
            error("Memory file is outside the managed store: ${file.absolutePath}")
        }
        return file.canonicalFile
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
