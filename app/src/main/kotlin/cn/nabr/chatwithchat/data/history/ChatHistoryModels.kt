package cn.nabr.chatwithchat.data.history

data class ChatHistoryProjection(
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
    val projectionVersion: Int = CURRENT_PROJECTION_VERSION,
    val eligibilityState: String = HistoryEligibilityState.ELIGIBLE,
    val createdAt: Long,
    val updatedAt: Long
)

data class ChatHistoryProjectionBuildResult(
    val projection: ChatHistoryProjection?,
    val reason: String? = null
)

object HistoryEligibilityState {
    const val ELIGIBLE = "eligible"
    const val STALE = "stale"
    const val INVALID_SOURCE = "invalid_source"
}

object HistoryQueueOperation {
    const val RECONCILE = "RECONCILE"
}

object HistoryBackfillStatus {
    const val IDLE = "IDLE"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val FAILED = "FAILED"
}

object HistoryVectorStatus {
    const val MISSING = "MISSING"
    const val STALE = "STALE"
    const val READY = "READY"
    const val FAILED = "FAILED"
}

enum class HistoryRecallMode {
    NONE,
    DISABLED,
    LEXICAL,
    SEMANTIC,
    HYBRID,
    FAILED
}

data class ChatHistorySnippet(
    val turnKey: String,
    val chatId: Int,
    val userMessageId: Int,
    val assistantMessageId: Int,
    val title: String,
    val createdAt: Long,
    val userContent: String,
    val assistantContent: String,
    val lexicalScore: Float? = null,
    val vectorScore: Float? = null,
    val fusedScore: Float = 0f
)

data class HistoryRecallSnapshot(
    val projectionGeneration: Long? = null,
    val projectionHash: String? = null,
    val vectorGeneration: Long? = null,
    val vectorHash: String? = null,
    val snippets: List<ChatHistorySnippet> = emptyList(),
    val mode: HistoryRecallMode = HistoryRecallMode.NONE,
    val errorCode: String? = null,
    val diagnostics: List<String> = emptyList(),
    val prompt: String? = null,
    val estimatedTokens: Int = 0
) {
    init {
        require(estimatedTokens >= 0) { "estimatedTokens must not be negative" }
    }

    companion object {
        fun disabled(): HistoryRecallSnapshot = HistoryRecallSnapshot(mode = HistoryRecallMode.DISABLED)
    }
}

data class HistoryRetrievalRequest(
    val query: String,
    val currentChatId: Int,
    val recentContext: String? = null,
    val limit: Int = 4,
    val tokenBudget: Int = 400
)

data class HistoryRetrievalReport(
    val snapshot: HistoryRecallSnapshot,
    val lexicalCandidateCount: Int = 0,
    val vectorCandidateCount: Int = 0,
    val latencyMillis: Long = 0L
)

const val CURRENT_PROJECTION_VERSION = 1
const val HISTORY_BACKFILL_CHECKPOINT_ID = "history_backfill"
const val HISTORY_INDEX_STATE_ID = "history"
const val HISTORY_VECTOR_SNAPSHOT_ID = "current"
const val HISTORY_VECTOR_INDEX_SCHEMA_VERSION = 1
