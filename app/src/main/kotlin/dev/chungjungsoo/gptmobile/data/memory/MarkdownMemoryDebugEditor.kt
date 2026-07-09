package dev.chungjungsoo.gptmobile.data.memory

import java.io.File

internal class MarkdownMemoryDebugEditor(
    private val memoryFileStore: MemoryFileStore,
    private val markdownMemoryCodec: MarkdownMemoryCodec,
    private val memoryIndexRebuilder: MemoryIndexRebuilder? = null
) {

    suspend fun deleteLongTermEntry(entryId: String): Result<MarkdownMemoryDeletionResult> = runCatching {
        val normalizedEntryId = entryId.trim().takeIf { it.isNotBlank() }
            ?: error("Memory entry id must not be blank")
        val removal = markdownMemoryCodec.removeEntriesById(
            markdown = memoryFileStore.readLongTermMemory().getOrThrow(),
            entryIds = setOf(normalizedEntryId)
        )
        if (removal.deletedCount <= 0) {
            return@runCatching MarkdownMemoryDeletionResult(
                entryId = normalizedEntryId,
                deletedCount = 0
            )
        }

        val replacement = memoryFileStore.replaceLongTermMemory(removal.markdown).getOrThrow()
        memoryIndexRebuilder?.rebuildFile(replacement.file)?.getOrThrow()
        MarkdownMemoryDeletionResult(
            entryId = normalizedEntryId,
            deletedCount = removal.deletedCount,
            file = replacement.file,
            backupFile = replacement.backupFile
        )
    }
}

internal data class MarkdownMemoryDeletionResult(
    val entryId: String,
    val deletedCount: Int,
    val file: File? = null,
    val backupFile: File? = null
)
