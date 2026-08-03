package cn.nabr.chatwithchat.data.history

data class ChatHistoryTurnProjection(
    val turnKey: String,
    val chatId: Int,
    val userMessageId: Int,
    val assistantMessageId: Int,
    val assistantPlatformUid: String,
    val title: String,
    val userContent: String,
    val assistantContent: String,
    val searchTerms: String,
    val contentHash: String,
    val projectionVersion: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class ChatHistoryProjectionBuildResult(
    val projection: ChatHistoryTurnProjection? = null,
    val skipCode: String? = null
)

data class ChatHistorySnippet(
    val turnKey: String,
    val chatId: Int,
    val userMessageId: Int,
    val assistantMessageId: Int,
    val chatTitle: String,
    val createdAt: Long,
    val text: String,
    val lexicalScore: Float? = null,
    val vectorScore: Float? = null,
    val fusedScore: Float = 0f
)

enum class HistoryRecallMode {
    NONE,
    DISABLED,
    LEXICAL,
    HYBRID,
    FAILED
}

data class HistoryRecallDiagnostic(
    val code: String,
    val count: Int? = null
)

data class HistoryRecallSnapshot(
    val projectionGeneration: Long? = null,
    val projectionHash: String? = null,
    val vectorPublishedGeneration: Long? = null,
    val snippets: List<ChatHistorySnippet> = emptyList(),
    val mode: HistoryRecallMode = HistoryRecallMode.NONE,
    val diagnostics: List<HistoryRecallDiagnostic> = emptyList(),
    val errorCode: String? = null,
    val prompt: String? = null,
    val estimatedTokens: Int = 0
)

data class ChatHistoryRetrievalRequest(
    val currentChatId: Int,
    val query: String,
    val recentContext: String? = null,
    val limit: Int = 4,
    val tokenBudget: Int = 400
)

object ChatHistoryContract {
    const val PROJECTION_VERSION = 1
    const val ELIGIBLE = "eligible"
    const val OPERATION_RECONCILE = "RECONCILE"
    const val BACKFILL_ID = "history_backfill"
    const val INDEX_STATE_ID = "history"
    const val BACKFILL_IDLE = "IDLE"
    const val BACKFILL_RUNNING = "RUNNING"
    const val BACKFILL_PAUSED = "PAUSED"
    const val BACKFILL_FAILED = "FAILED"
    const val VECTOR_UNAVAILABLE = "UNAVAILABLE"
    const val VECTOR_STALE = "STALE"
    const val VECTOR_READY = "READY"
    const val VECTOR_FAILED = "FAILED"
}
