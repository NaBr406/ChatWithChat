package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class MemoryFilePaths(
    val rootDirectory: File
) {
    val longTermMemoryFile: File
        get() = File(rootDirectory, LONG_TERM_MEMORY_FILE_NAME)

    val dailyMemoryDirectory: File
        get() = File(rootDirectory, DAILY_MEMORY_DIRECTORY_NAME)

    val backupDirectory: File
        get() = File(rootDirectory, BACKUP_DIRECTORY_NAME)

    val stagingDirectory: File
        get() = File(rootDirectory, STAGING_DIRECTORY_NAME)

    fun dailyMemoryFile(date: LocalDate): File =
        File(dailyMemoryDirectory, "${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}.md")

    fun relativePath(file: File): String {
        val rootPath = rootDirectory.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        require(filePath.startsWith(rootPath)) {
            "Memory file is outside the managed store: ${file.absolutePath}"
        }
        return rootPath.relativize(filePath).joinToString("/")
    }

    internal fun memoryFile(relativePath: String): File {
        val segments = safeRelativePathSegments(relativePath)
        val isLongTermMemory = segments == listOf(LONG_TERM_MEMORY_FILE_NAME)
        val isDailyMemory = segments.size == 2 &&
            segments.first() == DAILY_MEMORY_DIRECTORY_NAME &&
            DAILY_MEMORY_FILE_REGEX.matches(segments.last())
        require(isLongTermMemory || isDailyMemory) {
            "Unsupported managed memory path: $relativePath"
        }
        return requireContained(File(rootDirectory, relativePath), rootDirectory)
    }

    internal fun stagedTargetFile(stagingId: String): File {
        require(STAGING_ID_REGEX.matches(stagingId)) {
            "Invalid memory staging ID"
        }
        return requireContained(File(stagingDirectory, "$stagingId.target"), stagingDirectory)
    }

    internal fun stagedTargetFileFromPath(relativePath: String): File {
        val segments = safeRelativePathSegments(relativePath)
        require(
            segments.size == 2 &&
                segments.first() == STAGING_DIRECTORY_NAME &&
                STAGED_TARGET_FILE_REGEX.matches(segments.last())
        ) {
            "Invalid staged target path: $relativePath"
        }
        return requireContained(File(rootDirectory, relativePath), stagingDirectory)
    }

    internal fun stagedTargetFiles(stagingIdPrefix: String): List<File> {
        require(STAGING_ID_REGEX.matches(stagingIdPrefix)) {
            "Invalid memory staging ID prefix"
        }
        return stagingDirectory
            .listFiles { file ->
                file.isFile &&
                    file.name.startsWith("${stagingIdPrefix}_") &&
                    STAGED_TARGET_FILE_REGEX.matches(file.name)
            }
            .orEmpty()
            .map { file -> requireContained(file, stagingDirectory) }
    }

    private fun safeRelativePathSegments(relativePath: String): List<String> {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) {
            "Expected a relative memory path"
        }
        require('\\' !in relativePath) {
            "Memory paths must use forward slashes"
        }
        val segments = relativePath.split('/')
        require(segments.none { segment -> segment.isBlank() || segment == "." || segment == ".." }) {
            "Memory path contains an unsafe segment: $relativePath"
        }
        return segments
    }

    private fun requireContained(file: File, parent: File): File {
        val rootPath = rootDirectory.canonicalFile.toPath()
        val parentPath = parent.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        require(parentPath.startsWith(rootPath) && filePath.startsWith(parentPath)) {
            "Memory path escapes the managed store: ${file.absolutePath}"
        }
        return file.canonicalFile
    }

    companion object {
        const val LONG_TERM_MEMORY_FILE_NAME = "MEMORY.md"
        const val DAILY_MEMORY_DIRECTORY_NAME = "memory"
        const val BACKUP_DIRECTORY_NAME = ".backups"
        const val STAGING_DIRECTORY_NAME = ".staging"

        private val DAILY_MEMORY_FILE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}\\.md")
        private val STAGING_ID_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        private val STAGED_TARGET_FILE_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}\\.target")

        fun fromContext(context: Context): MemoryFilePaths =
            MemoryFilePaths(File(context.filesDir, "memory_store"))
    }
}
