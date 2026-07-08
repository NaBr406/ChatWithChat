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

    fun dailyMemoryFile(date: LocalDate): File =
        File(dailyMemoryDirectory, "${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}.md")

    fun relativePath(file: File): String {
        val rootPath = rootDirectory.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return rootPath.relativize(filePath).joinToString("/")
    }

    companion object {
        const val LONG_TERM_MEMORY_FILE_NAME = "MEMORY.md"
        const val DAILY_MEMORY_DIRECTORY_NAME = "memory"
        const val BACKUP_DIRECTORY_NAME = ".backups"

        fun fromContext(context: Context): MemoryFilePaths =
            MemoryFilePaths(File(context.filesDir, "memory_store"))
    }
}
