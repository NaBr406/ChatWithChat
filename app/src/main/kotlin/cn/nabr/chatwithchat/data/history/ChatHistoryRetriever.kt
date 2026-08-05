package cn.nabr.chatwithchat.data.history

import androidx.sqlite.db.SimpleSQLiteQuery
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity
import cn.nabr.chatwithchat.data.repository.SettingRepository
import kotlinx.coroutines.CancellationException

class ChatHistoryRetriever(
    private val historyDao: ChatHistoryDao,
    private val settingRepository: SettingRepository,
    private val promptBuilder: ChatHistoryPromptBuilder = ChatHistoryPromptBuilder(),
    private val vectorStore: HistoryVectorStore? = null
) {
    suspend fun retrieve(request: HistoryRetrievalRequest): HistoryRetrievalReport {
        if (!settingRepository.fetchMemoryEnabled()) {
            return HistoryRetrievalReport(HistoryRecallSnapshot.disabled())
        }
        val startedAt = System.currentTimeMillis()
        val normalized = ChatHistoryQueryNormalizer.normalize(
            buildString {
                append(request.query)
                request.recentContext?.let { append('\n').append(it) }
            }.take(MAX_QUERY_LENGTH)
        )
        if (normalized.tokens.isEmpty() || normalized.matchQuery.isBlank()) {
            return HistoryRetrievalReport(
                snapshot = HistoryRecallSnapshot(mode = HistoryRecallMode.NONE),
                latencyMillis = System.currentTimeMillis() - startedAt
            )
        }
        var lexicalErrorCode: String? = null
        val lexical = runCatching {
            historyDao.searchLexical(
                SimpleSQLiteQuery(
                    """
                    SELECT p.* FROM chat_history_projection_fts f
                    JOIN chat_history_projection p ON p.projection_id = f.docid
                    WHERE chat_history_projection_fts MATCH ?
                      AND p.eligibility_state = ?
                      AND p.chat_id != ?
                    ORDER BY p.updated_at DESC, p.turn_key ASC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf<Any>(normalized.matchQuery, HistoryEligibilityState.ELIGIBLE, request.currentChatId, MAX_CANDIDATES)
                )
            )
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            lexicalErrorCode = "history_fts_failed"
            emptyList()
        }
        val lexicalCandidates = lexical
            .mapNotNull { projection -> scoreLexical(projection, normalized.tokens) }
            .filter { it.lexicalScore ?: 0f >= MIN_LEXICAL_SCORE }
            .map { it.copy(fusedScore = it.lexicalScore ?: 0f) }
        val vectorCandidates = vectorStore?.let { store ->
            runCatching { store.search(request.query, request.limit) }.getOrElse { emptyList() }
        }.orEmpty()
        val eligibleVectorSnippets = buildList {
            for (candidate in vectorCandidates) {
                if (candidate.score < MIN_VECTOR_SCORE || !candidate.score.isFinite()) continue
                if (size >= MAX_CANDIDATES) break
                val projection = historyDao.findProjection(candidate.turnKey)
                if (
                    projection != null &&
                    projection.eligibilityState == HistoryEligibilityState.ELIGIBLE &&
                    projection.chatId != request.currentChatId
                ) {
                    add(candidate.turnKey to projection.toSnippet(vectorScore = candidate.score))
                }
            }
        }.distinctBy { (turnKey, _) -> turnKey }
        val vectorByKey = eligibleVectorSnippets.associate { (turnKey, snippet) -> turnKey to snippet.vectorScore }
        val fused = lexicalCandidates.map { candidate ->
            val vector = vectorByKey[candidate.turnKey]
            candidate.copy(vectorScore = vector, fusedScore = (candidate.lexicalScore ?: 0f) * 0.65f + (vector ?: 0f) * 0.35f)
        } + eligibleVectorSnippets.mapNotNull { (turnKey, snippet) ->
            if (lexicalCandidates.any { it.turnKey == turnKey }) null else snippet
        }
        val deduplicated = deduplicateEquivalent(fused)
        val rendered = promptBuilder.build(
            deduplicated
                .distinctBy { it.turnKey }
                .sortedWith(compareByDescending<ChatHistorySnippet> { it.fusedScore }.thenBy { it.turnKey }),
            request.tokenBudget
        )
        val mode = when {
            rendered.snippets.isEmpty() && lexicalErrorCode != null && eligibleVectorSnippets.isEmpty() -> HistoryRecallMode.FAILED
            rendered.snippets.isEmpty() -> HistoryRecallMode.NONE
            eligibleVectorSnippets.isNotEmpty() && lexicalCandidates.isNotEmpty() -> HistoryRecallMode.HYBRID
            eligibleVectorSnippets.isNotEmpty() -> HistoryRecallMode.SEMANTIC
            else -> HistoryRecallMode.LEXICAL
        }
        val indexState = historyDao.indexState(HISTORY_INDEX_STATE_ID)
        val vectorSnapshot = historyDao.vectorSnapshot(HISTORY_VECTOR_SNAPSHOT_ID)
        val diagnostics = buildList {
            add("lexical_candidates=${lexicalCandidates.size}")
            add("vector_candidates=${eligibleVectorSnippets.size}")
            lexicalErrorCode?.let(::add)
        }
        return HistoryRetrievalReport(
            snapshot = HistoryRecallSnapshot(
                projectionGeneration = indexState?.projectionGeneration,
                projectionHash = indexState?.projectionHash,
                vectorGeneration = indexState?.vectorPublishedGeneration,
                vectorHash = vectorSnapshot?.projectionHash,
                snippets = rendered.snippets,
                mode = mode,
                errorCode = lexicalErrorCode,
                prompt = rendered.prompt,
                estimatedTokens = rendered.estimatedTokens,
                diagnostics = diagnostics
            ),
            lexicalCandidateCount = lexicalCandidates.size,
            vectorCandidateCount = eligibleVectorSnippets.size,
            latencyMillis = System.currentTimeMillis() - startedAt
        )
    }

    private fun deduplicateEquivalent(candidates: List<ChatHistorySnippet>): List<ChatHistorySnippet> {
        val seen = mutableSetOf<String>()
        return candidates
            .sortedWith(compareByDescending<ChatHistorySnippet> { it.fusedScore }.thenBy { it.turnKey })
            .filter { candidate ->
                val key = buildString {
                    append(normalizeEquivalentText(candidate.userContent))
                    append('\u0000')
                    append(normalizeEquivalentText(candidate.assistantContent))
                }
                seen.add(key)
            }
    }

    private fun normalizeEquivalentText(text: String): String =
        text.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun scoreLexical(
        projection: ChatHistoryProjectionEntity,
        queryTokens: List<String>
    ): ChatHistorySnippet? {
        val distinctQueryTokens = queryTokens.distinct()
        val candidateTokens = projection.searchTerms.split(' ').toSet()
        val overlap = distinctQueryTokens.count { it in candidateTokens }
        if (overlap == 0) return null
        val score = overlap.toFloat() / distinctQueryTokens.size.coerceAtLeast(1)
        val minimumCoverage = when {
            distinctQueryTokens.size <= 2 -> 1f
            else -> MIN_MULTI_TOKEN_COVERAGE
        }
        if (score < minimumCoverage) return null
        return projection.toSnippet(lexicalScore = score)
    }

    private fun ChatHistoryProjectionEntity.toSnippet(
        lexicalScore: Float? = null,
        vectorScore: Float? = null
    ): ChatHistorySnippet = ChatHistorySnippet(
        turnKey = turnKey,
        chatId = chatId,
        userMessageId = userMessageId,
        assistantMessageId = assistantMessageId,
        title = title,
        createdAt = createdAt,
        userContent = userContent,
        assistantContent = assistantContent,
        lexicalScore = lexicalScore,
        vectorScore = vectorScore,
        fusedScore = lexicalScore ?: vectorScore ?: 0f
    )

    private companion object {
        const val MAX_QUERY_LENGTH = 2_000
        const val MAX_CANDIDATES = 32
        const val MIN_LEXICAL_SCORE = 0.18f
        const val MIN_MULTI_TOKEN_COVERAGE = 0.75f
        const val MIN_VECTOR_SCORE = 0.55f
    }
}
