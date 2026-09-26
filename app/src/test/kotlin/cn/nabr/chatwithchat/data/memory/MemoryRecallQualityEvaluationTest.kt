package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallQualityEvaluationTest {
    private val codec = MarkdownMemoryCodec()
    private val chunker = MemoryChunker()

    @Test
    fun `optimized corpus improves recall safety without losing stable usefulness`() {
        val oldEntries = listOf(
            entry(
                id = "old_address",
                text = "称呼用户为大哥。",
                type = "stable_profile",
                source = MemorySource.EXPLICIT_USER_STATEMENT,
                canonicalKey = "identity.preferred_address",
                recallState = MemoryRecallState.CORE,
                updatedAt = 10
            ),
            entry(
                id = "old_address_duplicate",
                text = "称呼用户为老板。",
                type = "stable_profile",
                source = MemorySource.USER_CONFIRMED,
                canonicalKey = "identity.preferred_address",
                recallState = MemoryRecallState.CORE,
                updatedAt = 20
            ),
            entry(
                id = "old_project_stale",
                text = "ChatWithChat 当前测试结果为绿色。",
                type = "project_context",
                canonicalKey = "project.chatwithchat.test_result",
                scope = "project:chatwithchat",
                recallState = MemoryRecallState.CORE,
                updatedAt = 1
            ),
            entry(
                id = "old_private",
                text = "用户有一位前任伴侣。",
                type = "important_person",
                sensitivity = MemorySensitivity.PRIVATE,
                canonicalKey = "relationship.ex_partner",
                scope = MemoryScope.PERSONAL,
                recallState = MemoryRecallState.QUERY,
                updatedAt = 30
            )
        )
        val optimizedEntries = listOf(
            oldEntries[1].copy(id = "current_address", text = "称呼用户为老板。"),
            oldEntries[2].copy(
                id = "active_project",
                text = "ChatWithChat 是用户长期维护的 Android 聊天项目。",
                canonicalKey = "project.chatwithchat",
                recallState = MemoryRecallState.QUERY,
                durability = MemoryDurability.ACTIVE,
                freshness = MemoryFreshness.REFRESHABLE,
                lifecycleState = MemoryLifecycleState.ACTIVE,
                refreshAfterDays = 30,
                updatedAt = 100
            ),
            oldEntries[3]
        )

        val oldMetrics = evaluate(oldEntries)
        val optimizedMetrics = evaluate(optimizedEntries)

        println(
            "memory-recall-ab|old=$oldMetrics|optimized=$optimizedMetrics"
        )

        assertEquals(1, oldMetrics.activeDuplicateRate)
        assertEquals(0, optimizedMetrics.activeDuplicateRate)
        assertEquals(1, oldMetrics.staleCoreCount)
        assertEquals(0, optimizedMetrics.staleCoreCount)
        assertEquals(0, oldMetrics.privateLeakageCount)
        assertEquals(0, optimizedMetrics.privateLeakageCount)
        assertEquals(1, oldMetrics.usefulStableRecallCount)
        assertEquals(1, optimizedMetrics.usefulStableRecallCount)
        assertTrue(optimizedMetrics.usefulStableRecallCount >= oldMetrics.usefulStableRecallCount)
    }

    private fun evaluate(entries: List<MarkdownMemoryEntry>): RecallMetrics {
        val current = entries.filter { it.validity == MemoryValidity.CURRENT }
        val activeDuplicateRate = current
            .filter { it.canonicalKey != null }
            .groupingBy { it.canonicalKey to it.scope }
            .eachCount()
            .values
            .sumOf { (it - 1).coerceAtLeast(0) }
        val chunks = chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            codec.renderLongTerm(entries),
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        ).chunks
        val snapshot = MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            canonicalSourceHash = "ab",
            recallProjectionHash = "ab",
            generation = 1,
            chunks = chunks
        )
        val core = snapshot.selectCoreResults(includePrivate = true)
        val privateLeakageCount = core.count { it.sensitivity == MemorySensitivity.PRIVATE }
        return RecallMetrics(
            activeDuplicateRate = activeDuplicateRate,
            staleCoreCount = current.count {
                it.recallState == MemoryRecallState.CORE && !MemoryFreshnessPolicy.allowsCore(it)
            },
            privateLeakageCount = privateLeakageCount,
            usefulStableRecallCount = if (core.any { it.type == "stable_profile" }) 1 else 0
        )
    }

    private fun entry(
        id: String,
        text: String,
        type: String,
        source: String = MemorySource.EXPLICIT_USER_STATEMENT,
        sensitivity: String = MemorySensitivity.NORMAL,
        canonicalKey: String,
        scope: String = MemoryScope.GENERAL,
        recallState: String,
        updatedAt: Long
    ) = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = type,
        sensitivity = sensitivity,
        source = source,
        createdAt = 1,
        updatedAt = updatedAt,
        canonicalKey = canonicalKey,
        scope = scope,
        recallState = recallState
    )

    private data class RecallMetrics(
        val activeDuplicateRate: Int,
        val staleCoreCount: Int,
        val privateLeakageCount: Int,
        val usefulStableRecallCount: Int
    ) {
        override fun toString(): String =
            "duplicate=$activeDuplicateRate|stale_core=$staleCoreCount|" +
                "private_leakage=$privateLeakageCount|useful_stable=$usefulStableRecallCount"
    }
}
