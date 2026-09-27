package cn.nabr.chatwithchat.presentation.ui.memory

import android.content.Context
import android.net.Uri
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal const val MEMORY_EXPORT_MIME_TYPE = "text/markdown"

private val memoryExportTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

internal fun memoryExportFileName(now: LocalDateTime = LocalDateTime.now()): String =
    "ChatWithChat-Memory-${now.format(memoryExportTimestampFormatter)}.md"

internal fun saveMemoryMarkdown(
    context: Context,
    uri: Uri,
    markdown: String
) {
    val outputStream = context.contentResolver.openOutputStream(uri, "wt")
        ?: error("Unable to open memory export destination")
    writeMemoryMarkdown(outputStream, markdown)
}

internal fun writeMemoryMarkdown(
    outputStream: OutputStream,
    markdown: String
) {
    outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write(markdown)
    }
}
