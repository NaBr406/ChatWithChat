package cn.nabr.chatwithchat.data.database.dao

data class MemoryDailyEvidenceDecisionUpdate(
    val evidenceKey: String,
    val contentHash: String,
    val decision: String,
    val targetEntryId: String?
)
