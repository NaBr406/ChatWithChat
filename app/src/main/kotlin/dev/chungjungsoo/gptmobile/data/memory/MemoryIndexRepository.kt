package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryIndexDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryChunk
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDocument
import java.io.File
import java.security.MessageDigest
import java.time.Clock

interface MemoryIndexSearcher {
    suspend fun search(request: MemoryIndexSearchRequest): Result<List<MemoryIndexSearchResult>>
}

interface MemoryIndexRebuilder {
    suspend fun rebuildFile(file: File): Result<MemoryIndexRebuildResult>
}

class MemoryIndexRepository(
    private val memoryFileStore: MemoryFileStore,
    private val memoryIndexDao: MemoryIndexDao,
    private val memoryChunker: MemoryChunker,
    private val clock: Clock = Clock.systemDefaultZone()
) : MemoryIndexSearcher, MemoryIndexRebuilder {

    suspend fun rebuildAll(): Result<MemoryIndexRebuildResult> = runCatching {
        val indexedFiles = memoryFileStore.listMemoryFiles().getOrThrow()
            .mapNotNull { file -> buildIndexedFile(file).getOrNull() }

        memoryIndexDao.replaceAll(
            documents = indexedFiles.map { it.document },
            chunks = indexedFiles.flatMap { it.chunks }
        )

        MemoryIndexRebuildResult(
            indexedDocuments = indexedFiles.size,
            indexedChunks = indexedFiles.sumOf { it.chunks.size }
        )
    }

    override suspend fun rebuildFile(file: File): Result<MemoryIndexRebuildResult> = runCatching {
        val sourcePath = memoryFileStore.relativePath(file).getOrThrow()
        if (!file.exists()) {
            memoryIndexDao.removeDocument(sourcePath)
            return@runCatching MemoryIndexRebuildResult(indexedDocuments = 0, indexedChunks = 0)
        }

        val indexedFile = buildIndexedFile(file).getOrThrow()
        memoryIndexDao.replaceDocument(indexedFile.document, indexedFile.chunks)
        MemoryIndexRebuildResult(
            indexedDocuments = 1,
            indexedChunks = indexedFile.chunks.size
        )
    }

    override suspend fun search(request: MemoryIndexSearchRequest): Result<List<MemoryIndexSearchResult>> = runCatching {
        val tokens = tokenize(request.query)
        if (tokens.isEmpty()) return@runCatching emptyList()

        memoryIndexDao.getSearchCandidates(
            sourcePath = request.sourcePath,
            includePrivate = request.includePrivate,
            limit = request.candidateLimit.coerceAtLeast(request.limit)
        )
            .mapNotNull { chunk ->
                val score = chunk.score(tokens, request.query)
                if (score <= 0f) null else chunk.toSearchResult(score)
            }
            .sortedWith(
                compareByDescending<MemoryIndexSearchResult> { it.score }
                    .thenBy { if (it.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) 0 else 1 }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.chunkIndex }
            )
            .take(request.limit)
    }

    private fun buildIndexedFile(file: File): Result<IndexedMemoryFile> = runCatching {
        val sourcePath = memoryFileStore.relativePath(file).getOrThrow()
        val markdown = memoryFileStore.readMemoryFile(file).getOrThrow()
        val indexedAt = clock.instant().epochSecond
        val chunks = memoryChunker.chunksFor(
            sourcePath = sourcePath,
            markdown = markdown,
            indexedAt = indexedAt
        )
        IndexedMemoryFile(
            document = MemoryDocument(
                sourcePath = sourcePath,
                title = memoryChunker.titleFor(markdown),
                scope = scopeFor(sourcePath),
                contentHash = markdown.sha256(),
                lastModifiedAt = file.lastModified().takeIf { it > 0L }?.div(1000) ?: indexedAt,
                indexedAt = indexedAt
            ),
            chunks = chunks
        )
    }

    private fun scopeFor(sourcePath: String): String =
        if (sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) {
            MemoryDocumentScope.LONG_TERM
        } else {
            MemoryDocumentScope.DAILY
        }

    private fun MemoryChunk.score(tokens: List<String>, rawQuery: String): Float {
        val searchableText = listOfNotNull(heading, text, type, source)
            .joinToString(" ")
            .lowercase()
        val normalizedQuery = rawQuery.trim().lowercase()
        var score = if (normalizedQuery.isNotBlank() && searchableText.contains(normalizedQuery)) {
            EXACT_QUERY_SCORE
        } else {
            0f
        }

        tokens.forEach { token ->
            if (searchableText.contains(token)) {
                score += TOKEN_MATCH_SCORE
            }
        }
        if (sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME && score > 0f) {
            score += LONG_TERM_BONUS
        }
        return score
    }

    private fun MemoryChunk.toSearchResult(score: Float): MemoryIndexSearchResult =
        MemoryIndexSearchResult(
            chunkId = chunkId,
            sourcePath = sourcePath,
            chunkIndex = chunkIndex,
            heading = heading,
            text = text,
            entryId = entryId,
            type = type,
            sensitivity = sensitivity,
            source = source,
            chatId = chatId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            score = score
        )

    private fun tokenize(query: String): List<String> =
        TOKEN_REGEX.findAll(query.lowercase())
            .map { it.value.trim() }
            .filter { it.length >= MIN_TOKEN_LENGTH }
            .distinct()
            .toList()

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class IndexedMemoryFile(
        val document: MemoryDocument,
        val chunks: List<MemoryChunk>
    )

    companion object {
        private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}_-]+")
        private const val MIN_TOKEN_LENGTH = 2
        private const val EXACT_QUERY_SCORE = 6f
        private const val TOKEN_MATCH_SCORE = 1f
        private const val LONG_TERM_BONUS = 0.25f
    }
}

data class MemoryIndexSearchRequest(
    val query: String,
    val sourcePath: String? = null,
    val includePrivate: Boolean = true,
    val limit: Int = 8,
    val candidateLimit: Int = 200
)

data class MemoryIndexSearchResult(
    val chunkId: String,
    val sourcePath: String,
    val chunkIndex: Int,
    val heading: String?,
    val text: String,
    val entryId: String?,
    val type: String?,
    val sensitivity: String?,
    val source: String?,
    val chatId: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val score: Float
)

data class MemoryIndexRebuildResult(
    val indexedDocuments: Int,
    val indexedChunks: Int
)

object MemoryDocumentScope {
    const val LONG_TERM = "long_term"
    const val DAILY = "daily"
}
