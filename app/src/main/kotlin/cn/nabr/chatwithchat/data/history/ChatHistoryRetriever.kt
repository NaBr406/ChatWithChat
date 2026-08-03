package cn.nabr.chatwithchat.data.history

import androidx.sqlite.db.SimpleSQLiteQuery
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryLexicalHit
import cn.nabr.chatwithchat.data.memory.containsInternalMemoryMetadata
import cn.nabr.chatwithchat.data.repository.SettingRepository
import kotlin.math.abs

class ChatHistoryRetriever(
    private val historyDao: ChatHistoryDao,
    private val settingRepository: SettingRepository? = null,
    private val vectorStore: HistoryVectorStore? = null,
    private val promptBuilder: ChatHistoryPromptBuilder = ChatHistoryPromptBuilder()
) {
    suspend fun retrieve(request: ChatHistoryRetrievalRequest): HistoryRecallSnapshot {
        if (settingRepository?.fetchMemoryEnabled() == false) {
            return HistoryRecallSnapshot(mode = HistoryRecallMode.DISABLED)
        }
        val query = request.query.trim()
        if (query.isBlank()) return HistoryRecallSnapshot()
        val indexState = historyDao.getIndexState(ChatHistoryContract.INDEX_STATE_ID)
        val backfillCheckpoint = historyDao.getBackfillCheckpoint(ChatHistoryContract.BACKFILL_ID)

        val searchInput = listOfNotNull(
            query.takeIf(String::isNotBlank),
            request.recentContext?.takeIf(String::isNotBlank)
        ).joinToString(" ")
        val match = ChatHistoryQueryNormalizer.ftsMatchExpression(searchInput)
        if (match.isBlank()) return HistoryRecallSnapshot()
        val lexicalHits = runCatching {
            historyDao.searchLexical(
                SimpleSQLiteQuery(
                    """
                    SELECT p.projection_id AS projectionId, p.turn_key AS turnKey, p.chat_id AS chatId,
                        p.user_message_id AS userMessageId, p.assistant_message_id AS assistantMessageId,
                        p.assistant_platform_uid AS assistantPlatformUid, p.title AS title,
                        p.user_content AS userContent, p.assistant_content AS assistantContent,
                        p.search_terms AS searchTerms, p.content_hash AS contentHash,
                        p.projection_version AS projectionVersion, p.eligibility_state AS eligibilityState,
                        p.created_at AS createdAt, p.updated_at AS updatedAt,
                        bm25(chat_history_projection_fts) AS lexicalScore
                    FROM chat_history_projection_fts
                    JOIN chat_history_projection p ON p.projection_id = chat_history_projection_fts.rowid
                    WHERE chat_history_projection_fts MATCH ?
                        AND p.eligibility_state = 'eligible'
                        AND p.chat_id != ?
                    ORDER BY bm25(chat_history_projection_fts), p.turn_key
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf<Any>(match, request.currentChatId, MAX_LEXICAL_CANDIDATES)
                )
            )
        }.getOrElse {
            return HistoryRecallSnapshot(
                mode = HistoryRecallMode.FAILED,
                diagnostics = listOf(HistoryRecallDiagnostic("lexical_query_failed")),
                errorCode = "lexical_query_failed"
            )
        }
        val vectorResult = vectorStore?.query(searchInput, MAX_VECTOR_CANDIDATES)
        val vectorHits: List<HistoryVectorHit> = vectorResult?.getOrElse { emptyList() } ?: emptyList()
        val vectorFailed = vectorResult?.isFailure == true
        val lexicalByKey = lexicalHits.associateBy(ChatHistoryLexicalHit::turnKey)
        val vectorByKey = vectorHits.associateBy(HistoryVectorHit::turnKey)
        val vectorOnlyProjections = historyDao.getProjectionsByTurnKeys(
            vectorHits.map(HistoryVectorHit::turnKey).filterNot(lexicalByKey::containsKey)
        ).filter { projection -> projection.chatId != request.currentChatId }
            .associateBy { projection -> projection.turnKey }
        val candidates = lexicalHits.map { hit ->
            val vectorScore = vectorByKey[hit.turnKey]?.score
            val lexicalScore = lexicalRelevance(hit, query)
            ChatHistorySnippet(
                turnKey = hit.turnKey,
                chatId = hit.chatId,
                userMessageId = hit.userMessageId,
                assistantMessageId = hit.assistantMessageId,
                chatTitle = hit.title,
                createdAt = hit.createdAt,
                text = renderSnippet(hit),
                lexicalScore = lexicalScore,
                vectorScore = vectorScore,
                fusedScore = fuse(lexicalScore, vectorScore)
            )
        } + vectorHits.mapNotNull { vector ->
            val projection = vectorOnlyProjections[vector.turnKey] ?: return@mapNotNull null
            if (vector.score < MIN_VECTOR_SCORE) return@mapNotNull null
            val hit = projection.toLexicalHit()
            ChatHistorySnippet(
                turnKey = hit.turnKey,
                chatId = hit.chatId,
                userMessageId = hit.userMessageId,
                assistantMessageId = hit.assistantMessageId,
                chatTitle = hit.title,
                createdAt = hit.createdAt,
                text = renderSnippet(hit),
                vectorScore = vector.score,
                fusedScore = vector.score * VECTOR_WEIGHT
            )
        }
        val selected = promptBuilder.build(
            snippets = candidates
                .filter { snippet -> snippet.fusedScore >= MIN_FUSED_SCORE }
                .sortedWith(compareByDescending<ChatHistorySnippet> { it.fusedScore }.thenBy { it.turnKey }),
            tokenBudget = request.tokenBudget,
            maximumSnippets = request.limit.coerceIn(0, MAX_SNIPPETS)
        )
        val backfillIncomplete = backfillCheckpoint?.status == ChatHistoryContract.BACKFILL_RUNNING
        val indexStale = indexState?.vectorStatus == ChatHistoryContract.VECTOR_STALE
        val mode = when {
            selected.snippets.isEmpty() && backfillIncomplete -> HistoryRecallMode.BACKFILL_INCOMPLETE
            selected.snippets.isEmpty() && indexStale -> HistoryRecallMode.STALE
            selected.snippets.isEmpty() -> HistoryRecallMode.NONE
            selected.snippets.any { snippet -> snippet.vectorScore != null } -> HistoryRecallMode.HYBRID
            else -> HistoryRecallMode.LEXICAL
        }
        val diagnostics = buildList {
            add(HistoryRecallDiagnostic("lexical_candidates", lexicalHits.size))
            add(HistoryRecallDiagnostic("vector_candidates", vectorHits.size))
            if (vectorFailed) add(HistoryRecallDiagnostic("vector_unavailable"))
            if (backfillIncomplete) {
                add(HistoryRecallDiagnostic("backfill_incomplete"))
            }
            if (indexStale) add(HistoryRecallDiagnostic("index_stale"))
        }
        return HistoryRecallSnapshot(
            projectionGeneration = indexState?.projectionGeneration,
            projectionHash = indexState?.projectionHash,
            vectorPublishedGeneration = indexState?.vectorPublishedGeneration,
            snippets = selected.snippets,
            mode = mode,
            diagnostics = diagnostics,
            prompt = selected.prompt,
            estimatedTokens = selected.estimatedTokens
        )
    }

    private fun lexicalRelevance(hit: ChatHistoryLexicalHit, query: String): Float {
        val normalizedQuery = ChatHistoryQueryNormalizer.normalize(query)
        val normalizedText = ChatHistoryQueryNormalizer.normalize(
            "${hit.title} ${hit.userContent} ${hit.assistantContent}"
        )
        val terms = ChatHistoryQueryNormalizer.searchTerms(query)
        val overlap = terms.count { term -> hit.searchTerms.contains(term) }
        val exact = if (normalizedQuery.length >= 2 && normalizedText.contains(normalizedQuery)) 0.7f else 0f
        val bm25Boost = (1f / (1f + abs(hit.lexicalScore).toFloat())).coerceIn(0f, 1f)
        return (exact + overlap.toFloat() / terms.size.coerceAtLeast(1) * 0.25f + bm25Boost * 0.05f)
            .coerceIn(0f, 1f)
    }

    private fun fuse(lexical: Float, vector: Float?): Float = if (vector == null) {
        lexical
    } else {
        lexical * 0.55f + vector.coerceIn(-1f, 1f) * 0.45f
    }

    private fun renderSnippet(hit: ChatHistoryLexicalHit): String {
        val user = truncateAtSentence(hit.userContent, MAX_USER_CHARS)
        val assistant = truncateAtSentence(hit.assistantContent, MAX_ASSISTANT_CHARS)
        val rendered = "在“${hit.title.ifBlank { "未命名对话" }}”中，用户说：$user；助手回答：$assistant"
        return if (rendered.containsInternalMemoryMetadata()) {
            "历史对话片段包含内部标记，已省略具体内容。"
        } else {
            rendered
        }
    }

    private fun cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity.toLexicalHit(): ChatHistoryLexicalHit =
        ChatHistoryLexicalHit(
            projectionId = projectionId,
            turnKey = turnKey,
            chatId = chatId,
            userMessageId = userMessageId,
            assistantMessageId = assistantMessageId,
            assistantPlatformUid = assistantPlatformUid,
            title = title,
            userContent = userContent,
            assistantContent = assistantContent,
            searchTerms = searchTerms,
            contentHash = contentHash,
            projectionVersion = projectionVersion,
            eligibilityState = eligibilityState,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lexicalScore = 0.0
        )

    private fun truncateAtSentence(value: String, maximum: Int): String {
        val text = value.trim().take(maximum)
        if (text.length < maximum) return text
        val boundary = text.lastIndexOfAny(charArrayOf('。', '！', '？', '.', '!', '?', '\n'))
        return if (boundary >= maximum / 2) text.take(boundary + 1) else text
    }

    companion object {
        private const val MAX_LEXICAL_CANDIDATES = 32
        private const val MAX_VECTOR_CANDIDATES = 32
        private const val MAX_SNIPPETS = 4
        private const val MAX_USER_CHARS = 600
        private const val MAX_ASSISTANT_CHARS = 1_600
        private const val MIN_FUSED_SCORE = 0.08f
        private const val MIN_VECTOR_SCORE = 0.45f
        private const val VECTOR_WEIGHT = 0.45f
    }
}
