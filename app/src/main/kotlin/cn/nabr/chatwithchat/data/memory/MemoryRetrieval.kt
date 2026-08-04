package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.token.TokenUsageEstimator

interface MemoryRetriever {
    suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>>

    suspend fun retrieveWithDiagnostics(request: MemoryRetrievalRequest): Result<MemoryRetrievalReport> =
        retrieve(request).map { results ->
            MemoryRetrievalReport(
                results = results,
                mode = inferMemoryRetrievalMode(request, results)
            )
        }
}

interface MemoryMaintenanceCorpusReader {
    suspend fun retrieveWorkingSet(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>>
}

data class MemoryRetrievalRequest(
    val corpus: MemoryCorpus,
    val query: String,
    val recentContext: String? = null,
    val recallScope: String = MemoryScope.GENERAL,
    val limit: Int = 8,
    val candidateLimit: Int = 200,
    /** Null means retrieval is not constrained by a memory-specific token budget. */
    val tokenBudget: Int? = null,
    val includePrivate: Boolean = true,
    val strategy: MemoryRetrievalStrategy = MemoryRetrievalStrategy.LEXICAL
) {
    init {
        require(MarkdownMemoryMetadataPolicy.isScope(recallScope)) { "Invalid memory recall scope" }
    }
}

/**
 * The immutable per-turn query shared by long-term and history recall.
 *
 * The current user message is kept as the primary section. Recent turns are a
 * bounded secondary section; role labels are retained so lexical recall can
 * prevent assistant-only claims from becoming user intent.
 */
data class MemoryRecallQuerySnapshot(
    val currentUserMessage: String,
    val recentContext: String? = null
) {
    val primaryText: String = normalizeComponent(currentUserMessage).take(MAX_PRIMARY_QUERY_CHARS)
    val recentText: String = normalizeComponent(recentContext.orEmpty()).take(MAX_RECENT_CONTEXT_CHARS)
    val renderedText: String = if (primaryText.isBlank() && recentText.isBlank()) {
        ""
    } else {
        buildString {
            append("Current user message:\n")
            append(primaryText)
            if (recentText.isNotBlank()) {
                append("\nRecent conversation context:\n")
                append(recentText)
            }
        }
    }
    val snapshotHash: String = renderedText.sha256Utf8()

    val isBlank: Boolean
        get() = primaryText.isBlank() && recentText.isBlank()

    internal fun contextSegments(): List<MemoryRecallContextSegment> = buildList {
        var currentRole = MemoryRecallContextRole.UNKNOWN
        val currentText = StringBuilder()

        fun flush() {
            currentText.toString().trim().takeIf(String::isNotBlank)?.let { text ->
                add(MemoryRecallContextSegment(currentRole, text))
            }
            currentText.clear()
        }

        recentText.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { line ->
                when {
                    line.startsWith("assistant:", ignoreCase = true) -> {
                        flush()
                        currentRole = MemoryRecallContextRole.ASSISTANT
                        currentText.append(line.substringAfter(':').trim())
                    }
                    line.startsWith("user:", ignoreCase = true) -> {
                        flush()
                        currentRole = MemoryRecallContextRole.USER
                        currentText.append(line.substringAfter(':').trim())
                    }
                    else -> {
                        if (currentText.isNotEmpty()) currentText.append(' ')
                        currentText.append(line)
                    }
                }
            }
        flush()
    }

    private companion object {
        const val MAX_PRIMARY_QUERY_CHARS = 8_000
        const val MAX_RECENT_CONTEXT_CHARS = 8_000

        fun normalizeComponent(value: String): String = value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .trim()
    }
}

internal enum class MemoryRecallContextRole {
    USER,
    ASSISTANT,
    UNKNOWN
}

internal data class MemoryRecallContextSegment(
    val role: MemoryRecallContextRole,
    val text: String
)

data class MemoryRetrievalResult(
    val chunkId: String,
    val entryId: String?,
    val sourcePath: String,
    val text: String,
    val type: String?,
    val sensitivity: String?,
    val source: String?,
    val updatedAt: Long,
    val chatId: Int? = null,
    val createdAt: Long = 0L,
    val section: String? = null,
    val canonicalKey: String? = null,
    val scope: String? = null,
    val recallState: String? = null,
    val validity: String? = null,
    val lastObservedAt: Long = updatedAt,
    val supersededBy: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val extraMetadata: Map<String, String> = emptyMap(),
    val embeddingContentHash: String,
    val rankingHash: String = embeddingContentHash,
    val lexicalScore: Float? = null,
    val vectorScore: Float? = null,
    val fusedScore: Float
)

data class MemoryRetrievalReport(
    val results: List<MemoryRetrievalResult>,
    val mode: MemoryRetrievalMode,
    val errorMessage: String? = null,
    val recallProjectionHash: String? = null,
    val diagnostics: List<MemoryProjectionDiagnostic> = emptyList(),
    val coreResults: List<MemoryRetrievalResult> = emptyList(),
    val canonicalRevision: Long? = null,
    val canonicalSourceHash: String? = null
) {
    val tieredRecall: TieredMemoryRecall
        get() = TieredMemoryRecall(coreResults = coreResults, queryResults = results)
}

enum class MemoryRetrievalMode {
    LEXICAL,
    LEXICAL_FALLBACK,
    SEMANTIC,
    HYBRID,
    FAILED,
    NONE
}

enum class MemoryRetrievalStrategy {
    LEXICAL,
    VECTOR,
    HYBRID
}

private fun inferMemoryRetrievalMode(
    request: MemoryRetrievalRequest,
    results: List<MemoryRetrievalResult>
): MemoryRetrievalMode = when {
    results.isEmpty() -> MemoryRetrievalMode.NONE
    results.any { result -> result.vectorScore != null } &&
        results.any { result -> result.lexicalScore != null } -> MemoryRetrievalMode.HYBRID
    results.any { result -> result.vectorScore != null } -> MemoryRetrievalMode.SEMANTIC
    request.strategy == MemoryRetrievalStrategy.LEXICAL ||
        results.any { result -> result.lexicalScore != null } -> MemoryRetrievalMode.LEXICAL
    else -> MemoryRetrievalMode.NONE
}

data class MemoryRetrievalConfig(
    val strategy: MemoryRetrievalStrategy = MemoryRetrievalStrategy.LEXICAL,
    val lexicalWeight: Float = 1f,
    val vectorWeight: Float = 0f
)

internal fun List<MemoryRetrievalResult>.packFor(request: MemoryRetrievalRequest): List<MemoryRetrievalResult> {
    if (request.limit <= 0 || request.tokenBudget?.let { budget -> budget <= 0 } == true) return emptyList()
    if (request.tokenBudget == null) {
        return asSequence()
            .distinctBy(MemoryRetrievalResult::deduplicationKey)
            .distinctBy { result -> normalizeExactMemoryText(result.text) }
            .take(request.limit)
            .toList()
    }
    var usedTokens = 0
    val tokenBudget = requireNotNull(request.tokenBudget)
    return asSequence()
        .distinctBy(MemoryRetrievalResult::deduplicationKey)
        .distinctBy { result -> normalizeExactMemoryText(result.text) }
        .filter { result ->
            val resultTokens = TokenUsageEstimator.estimateText(
                text = result.text,
                model = "",
                clientType = ClientType.OPENAI
            ) + MEMORY_RETRIEVAL_RESULT_TOKEN_OVERHEAD
            if (usedTokens + resultTokens > tokenBudget) {
                false
            } else {
                usedTokens += resultTokens
                true
            }
        }
        .take(request.limit)
        .toList()
}

internal fun MemoryRetrievalRequest.queryLayerRequest(): MemoryRetrievalRequest =
    if (corpus == MemoryCorpus.CHAT_RECALL_LONG_TERM) {
        copy(
            limit = limit.coerceAtMost(MAX_QUERY_RECALL_FACTS),
            tokenBudget = null
        )
    } else {
        this
    }

internal fun MemoryRetrievalResult.deduplicationKey(): String =
    entryId?.let { value -> "entry:$value" } ?: "embedding:$embeddingContentHash"

internal fun MemoryRetrievalRequest.querySnapshot(): MemoryRecallQuerySnapshot =
    MemoryRecallQuerySnapshot(query, recentContext)

internal fun MemoryRetrievalRequest.combinedQuery(): String = querySnapshot().renderedText

internal fun MemoryRetrievalRequest.lexicalQuery(): String = querySnapshot().renderedText

private const val MEMORY_RETRIEVAL_RESULT_TOKEN_OVERHEAD = 24
private const val MAX_QUERY_RECALL_FACTS = 8

internal fun List<MemoryProjectionDiagnostic>.toBoundedErrorMessage(): String? =
    take(MAX_REPORTED_PROJECTION_DIAGNOSTICS)
        .joinToString(separator = ",") { diagnostic -> "${diagnostic.code}:${diagnostic.count}" }
        .takeIf(String::isNotBlank)

private const val MAX_REPORTED_PROJECTION_DIAGNOSTICS = 4
