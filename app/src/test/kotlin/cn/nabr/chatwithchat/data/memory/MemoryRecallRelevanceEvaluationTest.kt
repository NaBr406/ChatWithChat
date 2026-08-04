package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingAvailability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapabilitySource
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorPublishResult
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorQuery
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorQueryResult
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorSnapshot
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorSnapshotExpectation
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorSnapshotVerification
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorStore
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorUnavailableReason
import cn.nabr.chatwithchat.data.repository.MemoryRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A corpus-level smoke evaluation for the path used by local chat recall.
 *
 * The target events share all query terms while the distractors share only one
 * broad term. This makes a regression in candidate ranking visible without a
 * model or a device.
 */
class MemoryRecallRelevanceEvaluationTest {

    @Test
    fun `large event corpus keeps target events in retrieved memories and prompt`() = runBlocking {
        val targetEntries = (1..18).map { index ->
            MarkdownMemoryEntry(
                id = "target_event_$index",
                text = "上海摄影展筹备事件 $index：线下展览场地、作品装裱、海报宣传和志愿者排班均已确认。",
                type = "important_event",
                sensitivity = MemorySensitivity.NORMAL,
                source = MemorySource.EXPLICIT_USER_STATEMENT,
                updatedAt = index.toLong()
            )
        }
        val distractorEntries = buildList {
            addAll(
                (1..30).map { index ->
                    MarkdownMemoryEntry(
                        id = "distractor_shanghai_$index",
                        text = "上海出差记录 $index：讨论项目进度、预算和下一次会议安排。 [DISTRACTOR]",
                        type = "project_context",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        updatedAt = (100 + index).toLong()
                    )
                }
            )
            addAll(
                (1..30).map { index ->
                    MarkdownMemoryEntry(
                        id = "distractor_exhibition_$index",
                        text = "摄影展资料 $index：整理旧照片和展览目录，但尚未确定城市或线下场地。 [DISTRACTOR]",
                        type = "important_event",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        updatedAt = (200 + index).toLong()
                    )
                }
            )
            addAll(
                (1..24).map { index ->
                    MarkdownMemoryEntry(
                        id = "distractor_planning_$index",
                        text = "活动计划 $index：安排宣传、预算和人员排班，等待后续确认。 [DISTRACTOR]",
                        type = "light_productivity_preference",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        updatedAt = (300 + index).toLong()
                    )
                }
            )
            add(
                MarkdownMemoryEntry(
                    id = "address_current",
                    text = "请在一般场景称呼用户为新称呼。",
                    type = "stable_profile",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.USER_CONFIRMED,
                    updatedAt = 401,
                    canonicalKey = "identity.preferred_address",
                    scope = MemoryScope.GENERAL,
                    recallState = MemoryRecallState.CORE
                )
            )
            add(
                MarkdownMemoryEntry(
                    id = "address_work",
                    text = "工作场景称呼用户为总监。[SCOPED_WORK]",
                    type = "stable_profile",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.USER_CONFIRMED,
                    updatedAt = 402,
                    canonicalKey = "identity.preferred_address",
                    scope = MemoryScope.WORK
                )
            )
            add(
                MarkdownMemoryEntry(
                    id = "address_obsolete",
                    text = "过去在一般场景称呼用户为旧称呼。[SUPERSEDED]",
                    type = "stable_profile",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.USER_CONFIRMED,
                    updatedAt = 400,
                    canonicalKey = "identity.preferred_address",
                    scope = MemoryScope.GENERAL,
                    validity = MemoryValidity.OBSOLETE,
                    supersededBy = "address_current",
                    recallState = MemoryRecallState.MAINTENANCE_ONLY
                )
            )
            add(
                MarkdownMemoryEntry(
                    id = "hard_negative_camera",
                    text = "上海摄影器材采购清单只记录镜头保养。[HARD_NEGATIVE]",
                    type = "project_context",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.EXPLICIT_USER_STATEMENT,
                    updatedAt = 403
                )
            )
            add(
                MarkdownMemoryEntry(
                    id = "hard_negative_weekend",
                    text = "周末烘焙计划等待确认。[HARD_NEGATIVE]",
                    type = "important_event",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.EXPLICIT_USER_STATEMENT,
                    updatedAt = 404
                )
            )
            add(
                MarkdownMemoryEntry(
                    id = "hard_negative_single_cjk",
                    text = "大模型论文阅读记录。[SINGLE_CJK]",
                    type = "interest",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.EXPLICIT_USER_STATEMENT,
                    updatedAt = 405
                )
            )
        }
        val markdown = MarkdownMemoryCodec().renderLongTerm(targetEntries + distractorEntries)
        val chunking = MemoryChunker().chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown,
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        )
        val snapshot = MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            canonicalSourceHash = markdown.sha256Utf8(),
            recallProjectionHash = chunking.projectionHash,
            generation = 1L,
            chunks = chunking.chunks
        )
        val lexical = MarkdownLexicalRetriever(EvaluationSnapshotSource(snapshot))
        val hybrid = HybridMemoryRetriever(
            snapshotSource = EvaluationSnapshotSource(snapshot),
            lexicalRetriever = lexical,
            vectorStore = NoopMemoryVectorStore,
            embeddingCapabilitySource = MemoryEmbeddingCapabilitySource {
                MemoryEmbeddingCapability.Unavailable(
                    MemoryEmbeddingAvailability.Unavailable(
                        MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED
                    )
                )
            },
            vectorRecallStateSource = object : MemoryVectorRecallStateSource {
                override suspend fun expectedIdentity(
                    snapshot: MemoryCorpusSnapshot,
                    configuration: cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexConfiguration
                ) = null
            },
            repairTrigger = object : MemoryVectorRecallRepairTrigger {
                override fun requestRepair() = Unit
            }
        )
        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryRetriever = hybrid
        )
        val rankedCandidates = lexical.rankCandidates(
            request = MemoryRetrievalRequest(
                corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
                query = "请帮我安排上海摄影展的线下场地和宣传。",
                limit = 8,
                candidateLimit = 24,
                tokenBudget = null
            ),
            combinedQuery = "请帮我安排上海摄影展的线下场地和宣传。",
            snapshots = listOf(snapshot)
        )
        val candidateResults = rankedCandidates.take(8)

        val prepared = repository.prepareMemoryContext(
            chatRoom = ChatRoomV2(
                id = 1,
                title = "上海摄影展",
                enabledPlatform = listOf("platform")
            ),
            userMessages = listOf(
                MessageV2(
                    chatId = 1,
                    content = "请帮我安排上海摄影展的线下场地和宣传。",
                    platformType = null
                )
            ),
            assistantMessages = listOf(emptyList())
        )
        suspend fun recall(query: String) = repository.prepareMemoryContext(
            chatRoom = ChatRoomV2(id = 1, title = "Recall evaluation", enabledPlatform = listOf("platform")),
            userMessages = listOf(MessageV2(chatId = 1, content = query, platformType = null)),
            assistantMessages = listOf(emptyList())
        )
        val greeting = recall("你好")
        val unrelated = recall("今天天气如何")
        val address = recall("以后应该怎么称呼我")
        val singleCjk = recall("大")

        val candidateTargetCount = candidateResults.count { memory ->
            memory.entryId?.startsWith("target_event_") == true
        }
        val retrievedTargetCount = prepared.retrievedMemories.count { memory ->
            memory.entryId?.startsWith("target_event_") == true
        }
        val prompt = prepared.prompt.orEmpty()
        val promptTargetCount = targetEntries.count { entry ->
            prompt.contains(entry.text)
        }

        val lowestAcceptedScore = candidateResults.mapNotNull(MemoryRetrievalResult::lexicalScore).minOrNull()
        println(
            "memory-recall-eval total=${targetEntries.size + distractorEntries.size} " +
                "retrieved=${prepared.retrievedMemories.map { "${it.entryId}:${it.lexicalScore}" }} " +
                "targetTop8=$candidateTargetCount selectedTargets=$retrievedTargetCount " +
                "promptTargets=$promptTargetCount " +
                "lowestAccepted=$lowestAcceptedScore promptTokens=${prepared.snapshot.estimatedTokens}"
        )

        assertEquals(108, targetEntries.size + distractorEntries.size)
        assertEquals(18, rankedCandidates.count { memory -> memory.entryId?.startsWith("target_event_") == true })
        assertEquals(8, candidateTargetCount)
        assertEquals(8, retrievedTargetCount)
        assertEquals(8, promptTargetCount)
        assertFalse("Unrelated events must not leak into the prompt", prompt.contains("[DISTRACTOR]"))
        assertFalse("Hard negatives must not leak into the prompt", prompt.contains("[HARD_NEGATIVE]"))
        assertEquals(listOf("address_current"), greeting.retrievedMemories.mapNotNull { memory -> memory.entryId })
        assertTrue(greeting.snapshot.queryFacts.isEmpty())
        assertEquals(listOf("address_current"), unrelated.retrievedMemories.mapNotNull { memory -> memory.entryId })
        assertTrue(unrelated.snapshot.queryFacts.isEmpty())
        assertEquals(
            listOf("address_current", "address_work"),
            address.retrievedMemories.mapNotNull { memory -> memory.entryId }
        )
        assertEquals(listOf("工作场景称呼用户为总监。[SCOPED_WORK]"), address.snapshot.queryFacts.map { fact -> fact.text })
        assertTrue(address.prompt.orEmpty().contains("[SCOPED_WORK]"))
        assertFalse(address.prompt.orEmpty().contains("[SUPERSEDED]"))
        assertEquals(listOf("address_current"), singleCjk.retrievedMemories.mapNotNull { memory -> memory.entryId })
        assertTrue(singleCjk.snapshot.queryFacts.isEmpty())
        assertFalse(singleCjk.prompt.orEmpty().contains("[SINGLE_CJK]"))
        assertTrue(prepared.snapshot.estimatedTokens > 0)
    }
}

private class EvaluationSnapshotSource(
    private val snapshot: MemoryCorpusSnapshot
) : MemoryCorpusSnapshotSource {
    override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
        Result.success(listOf(snapshot))

    override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> = Result.success(true)
}

private object NoopMemoryVectorStore : MemoryVectorStore {
    override fun readManifest() = null

    override fun countChunks() = 0L

    override fun verifySnapshot(expectation: MemoryVectorSnapshotExpectation): MemoryVectorSnapshotVerification =
        MemoryVectorSnapshotVerification.Missing

    override fun replaceSnapshot(snapshot: MemoryVectorSnapshot): MemoryVectorPublishResult =
        MemoryVectorPublishResult.PUBLISHED

    override fun query(request: MemoryVectorQuery): MemoryVectorQueryResult =
        MemoryVectorQueryResult.Unavailable(MemoryVectorUnavailableReason.MISSING_MANIFEST)

    override fun clearSnapshot() = Unit

    override fun deleteDerivedStore() = Unit

    override fun recoverFromCorruption(cause: Throwable) = false

    override fun close() = Unit
}
