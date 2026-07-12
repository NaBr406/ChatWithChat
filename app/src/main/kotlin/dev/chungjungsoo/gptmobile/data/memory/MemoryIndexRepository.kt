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
            indexedChunks = indexedFiles.sumOf { it.chunks.size },
            changedChunkIds = indexedFiles.flatMapTo(mutableSetOf()) { indexedFile ->
                indexedFile.chunks.map { it.chunkId }
            }
        )
    }

    override suspend fun rebuildFile(file: File): Result<MemoryIndexRebuildResult> = runCatching {
        val sourcePath = memoryFileStore.relativePath(file).getOrThrow()
        val previousChunkIds = memoryIndexDao.getChunksForSource(sourcePath).mapTo(mutableSetOf()) { it.chunkId }
        if (!file.exists()) {
            memoryIndexDao.removeDocument(sourcePath)
            return@runCatching MemoryIndexRebuildResult(
                indexedDocuments = 0,
                indexedChunks = 0,
                changedChunkIds = previousChunkIds
            )
        }

        val indexedFile = buildIndexedFile(file).getOrThrow()
        memoryIndexDao.replaceDocument(indexedFile.document, indexedFile.chunks)
        MemoryIndexRebuildResult(
            indexedDocuments = 1,
            indexedChunks = indexedFile.chunks.size,
            changedChunkIds = previousChunkIds + indexedFile.chunks.map { it.chunkId }
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
        val chunks = memoryChunker.chunksFor(sourcePath = sourcePath, markdown = markdown)
            .map { chunk -> chunk.toRoomChunk(indexedAt) }
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
        val searchableText = normalizeSearchText(listOfNotNull(heading, text, type, source).joinToString(" "))
        val searchableTokens = tokenize(searchableText).toSet()
        val normalizedQuery = normalizeSearchText(rawQuery)
        var score = if (normalizedQuery.isNotBlank() && searchableText.contains(normalizedQuery)) {
            EXACT_QUERY_SCORE
        } else {
            0f
        }

        tokens.forEach { token ->
            if (token in searchableTokens || searchableText.contains(token)) {
                score += when {
                    token.isCjkToken() && token.length >= 3 -> CJK_TRIGRAM_MATCH_SCORE
                    token.isCjkToken() -> CJK_BIGRAM_MATCH_SCORE
                    else -> TOKEN_MATCH_SCORE
                }
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

    private fun MemoryCorpusChunk.toRoomChunk(indexedAt: Long): MemoryChunk = MemoryChunk(
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
        indexedAt = indexedAt
    )

    private fun tokenize(query: String): List<String> = buildList {
        val normalized = normalizeSearchText(query)
        LATIN_TOKEN_REGEX.findAll(normalized).forEach { match ->
            match.value.takeIf { it.length >= MIN_TOKEN_LENGTH }?.let(::add)
        }
        CJK_SEQUENCE_REGEX.findAll(normalized).forEach { match ->
            val sequence = match.value
            if (sequence.length == 1) add(sequence)
            CJK_GRAM_SIZES.forEach { gramSize ->
                if (sequence.length >= gramSize) {
                    for (index in 0..sequence.length - gramSize) {
                        add(sequence.substring(index, index + gramSize))
                    }
                }
            }
        }
    }.distinct()

    private fun normalizeSearchText(text: String): String = text
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.isCjkToken(): Boolean = any { character -> character.code in 0x3400..0x9FFF }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class IndexedMemoryFile(
        val document: MemoryDocument,
        val chunks: List<MemoryChunk>
    )

    companion object {
        private val LATIN_TOKEN_REGEX = Regex("[a-z0-9_-]+")
        private val CJK_SEQUENCE_REGEX = Regex("[\\u3400-\\u9fff]+")
        private val CJK_GRAM_SIZES = listOf(2, 3)
        private const val MIN_TOKEN_LENGTH = 2
        private const val EXACT_QUERY_SCORE = 6f
        private const val TOKEN_MATCH_SCORE = 1f
        private const val CJK_BIGRAM_MATCH_SCORE = 1f
        private const val CJK_TRIGRAM_MATCH_SCORE = 1.5f
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
    val score: Float,
    val contentHash: String = ""
)

data class MemoryIndexRebuildResult(
    val indexedDocuments: Int,
    val indexedChunks: Int,
    val changedChunkIds: Set<String> = emptySet()
)

object MemoryDocumentScope {
    const val LONG_TERM = "long_term"
    const val DAILY = "daily"
}
