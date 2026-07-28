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
                (1..30).map { index ->
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

        val retrievedTargetCount = prepared.retrievedMemories.count { memory ->
            memory.entryId?.startsWith("target_event_") == true
        }
        val prompt = prepared.prompt.orEmpty()
        val promptTargetCount = targetEntries.count { entry ->
            prompt.lineSequence().any { line -> line.contains("id: ${entry.id},") }
        }

        println(
            "memory-recall-eval total=${targetEntries.size + distractorEntries.size} " +
                "retrieved=${prepared.retrievedMemories.map { "${it.entryId}:${it.lexicalScore}" }} " +
                "targetTop8=$retrievedTargetCount promptTargets=$promptTargetCount " +
                "promptChars=${prompt.length}"
        )

        assertTrue("Expected at least 6 target events in the top 8", retrievedTargetCount >= 6)
        assertTrue("Expected at least 4 target events in the assembled prompt", promptTargetCount >= 4)
        assertFalse("Unrelated events must not leak into the prompt", prompt.contains("[DISTRACTOR]"))
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
