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
    val alwaysIncludeTypes: Set<String> = emptySet(),
    val limit: Int = 8,
    val candidateLimit: Int = 200,
    val tokenBudget: Int = 900,
    val includePrivate: Boolean = true,
    val strategy: MemoryRetrievalStrategy = MemoryRetrievalStrategy.LEXICAL
)

data class MemoryRetrievalResult(
    val chunkId: String,
    val entryId: String?,
    val sourcePath: String,
    val text: String,
    val type: String?,
    val sensitivity: String?,
    val source: String?,
    val contentHash: String,
    val lexicalScore: Float? = null,
    val vectorScore: Float? = null,
    val fusedScore: Float,
    val updatedAt: Long
)

data class MemoryRetrievalReport(
    val results: List<MemoryRetrievalResult>,
    val mode: MemoryRetrievalMode,
    val errorMessage: String? = null
)

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
    if (request.limit <= 0 || request.tokenBudget <= 0) return emptyList()
    var usedTokens = 0
    return asSequence()
        .distinctBy(MemoryRetrievalResult::deduplicationKey)
        .distinctBy { result -> normalizeExactMemoryText(result.text) }
        .filter { result ->
            val resultTokens = TokenUsageEstimator.estimateText(
                text = result.text,
                model = "",
                clientType = ClientType.OPENAI
            ) + MEMORY_RETRIEVAL_RESULT_TOKEN_OVERHEAD
            if (usedTokens + resultTokens > request.tokenBudget) {
                false
            } else {
                usedTokens += resultTokens
                true
            }
        }
        .take(request.limit)
        .toList()
}

internal fun MemoryRetrievalResult.deduplicationKey(): String =
    entryId?.let { value -> "entry:$value" } ?: "hash:$contentHash"

internal fun MemoryRetrievalRequest.combinedQuery(): String = listOfNotNull(
    query.trim().takeIf { it.isNotBlank() },
    recentContext?.trim()?.takeIf { it.isNotBlank() }
).joinToString(separator = "\n").take(MAX_MEMORY_RETRIEVAL_QUERY_CHARS)

/**
 * The current user turn is the authoritative lexical signal. Recent history
 * is useful for semantic embeddings, but including it in token matching lets
 * unrelated earlier turns make every memory candidate score above zero.
 */
internal fun MemoryRetrievalRequest.lexicalQuery(): String = query
    .trim()
    .take(MAX_MEMORY_RETRIEVAL_QUERY_CHARS)

private const val MEMORY_RETRIEVAL_RESULT_TOKEN_OVERHEAD = 24
private const val MAX_MEMORY_RETRIEVAL_QUERY_CHARS = 8_000
