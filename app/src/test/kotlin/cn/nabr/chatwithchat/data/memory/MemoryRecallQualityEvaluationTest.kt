package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallQualityEvaluationTest {
    private val codec = MarkdownMemoryCodec()
    private val chunker = MemoryChunker()

    @Test
    fun `migration output removes cross scope project duplicate from query candidates`() {
        val nowSeconds = 1_790_306_209L
        val legacy = entry(
            id = "legacy_project",
            text = "ChatWithChat Android project legacy description.",
            type = "project_context",
            canonicalKey = "project.gptmobile_ai_app",
            scope = "project:gptmobile-ai-app",
            recallState = MemoryRecallState.QUERY,
            updatedAt = nowSeconds
        )
        val verified = entry(
            id = "verified_project",
            text = "ChatWithChat Android project verified description.",
            type = "project_context",
            source = MemorySource.USER_CONFIRMED,
            canonicalKey = "project.chatwithchat",
            scope = "project:chatwithchat",
            recallState = MemoryRecallState.QUERY,
            updatedAt = nowSeconds
        )
        val oldMarkdown = codec.renderLongTerm(listOf(legacy, verified))
        val migrated = MemoryMigrationPlanner.propose(oldMarkdown, nowSeconds + 1L)
        val oldCandidates = queryProjectCandidates(oldMarkdown, nowSeconds)
        val migratedCandidates = queryProjectCandidates(migrated.targetMarkdown, nowSeconds)

        println("memory-migration-recall-ab|before=${oldCandidates.size}|after=${migratedCandidates.size}")
        assertEquals(2, oldCandidates.size)
        assertEquals(1, migratedCandidates.size)
        assertEquals(verified.text, migratedCandidates.single())
        assertTrue(migrated.afterAudit.isHealthy)
    }

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

    private fun queryProjectCandidates(markdown: String, nowSeconds: Long): List<String> {
        val chunks = chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown,
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        ).chunks
        val snapshot = MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            canonicalSourceHash = markdown.sha256Utf8(),
            generation = 1L,
            chunks = chunks
        )
        val unusedSource = object : MemoryCorpusSnapshotSource {
            override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
                error("ranking uses the supplied snapshot")

            override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> =
                error("ranking uses the supplied snapshot")
        }
        val request = MemoryRetrievalRequest(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            query = "ChatWithChat Android project",
            strategy = MemoryRetrievalStrategy.LEXICAL
        )
        return MarkdownLexicalRetriever(unusedSource, nowMillis = { nowSeconds * 1_000L })
            .rankCandidates(request, request.query, listOf(snapshot))
            .map(MemoryRetrievalResult::text)
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
