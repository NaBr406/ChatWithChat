package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingAvailability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapabilitySource
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexConfiguration
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorManifest
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorPublishResult
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorQuery
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorQueryResult
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorSnapshot
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorSnapshotExpectation
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorSnapshotVerification
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorStore
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorUnavailableReason
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.repository.MemoryRepositoryImpl
import cn.nabr.chatwithchat.data.token.TokenUsageEstimator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallBaselineReportTest {

    @Test
    fun `current recall behavior produces a deterministic baseline report`() = runBlocking {
        val compactEntries = compactBaselineEntries()
        val rows = listOf(
            runScenario("greeting", "\u5728\u5417", compactEntries),
            runScenario("unrelated", "\u4eca\u5929\u5929\u6c14\u600e\u4e48\u6837", compactEntries),
            runScenario("preferred_address", "\u4f60\u5e94\u8be5\u600e\u4e48\u79f0\u547c\u6211", compactEntries),
            runScenario("preference", "\u8bf7\u7528\u4e2d\u6587\u56de\u590d", compactEntries),
            runScenario("project_paraphrase", "\u7ee7\u7eed\u5f00\u53d1 ChatWithChat \u7684\u8bb0\u5fc6\u53ec\u56de\u529f\u80fd", compactEntries),
            runScenario("chinese_rewrite", "\u522b\u7ed5\u5f2f\u5b50\uff0c\u76f4\u63a5\u8bf4\u91cd\u70b9", compactEntries),
            runScenario("single_cjk_weak", "\u6211\u8bfb\u5927\u51e0", compactEntries),
            runScenario("corpus_108", "\u8bf7\u5e2e\u6211\u5b89\u6392\u4e0a\u6d77\u6444\u5f71\u5c55\u7684\u7ebf\u4e0b\u573a\u5730\u548c\u5ba3\u4f20\u3002", largeCorpusEntries())
        )

        println(
            "memory-recall-baseline|case|mode|invocations|candidate_ids|selected_ids|" +
                "prompt_chars|legacy_pack_estimate_tokens|rendered_estimated_tokens"
        )
        rows.forEach { row -> println(row.tableRow()) }

        assertEquals(EXPECTED_BASELINE, rows)
        assertTrue(rows.first { it.name == "greeting" }.selectedIds.isEmpty())
        assertTrue(rows.first { it.name == "unrelated" }.selectedIds.isEmpty())
        assertTrue(rows.first { it.name == "single_cjk_weak" }.candidateIds.isEmpty())
        assertTrue(rows.first { it.name == "single_cjk_weak" }.selectedIds.isEmpty())
        val corpusRow = rows.first { it.name == "corpus_108" }
        assertTrue(corpusRow.selectedIds.all { id -> id.startsWith("target_event_") })
        assertEquals(8, corpusRow.selectedIds.size)
    }

    private suspend fun runScenario(
        name: String,
        query: String,
        entries: List<MarkdownMemoryEntry>
    ): BaselineRow {
        val markdown = MarkdownMemoryCodec().renderLongTerm(entries)
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
        val retriever = BaselineRecordingRetriever(snapshot)
        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryRetriever = retriever
        )

        val prepared = repository.prepareMemoryContext(
            chatRoom = ChatRoomV2(id = 1, title = "Baseline", enabledPlatform = listOf("platform")),
            userMessages = listOf(MessageV2(chatId = 1, content = query, platformType = null)),
            assistantMessages = listOf(emptyList())
        )
        val prompt = prepared.prompt.orEmpty()

        assertEquals(1, retriever.invocations)
        assertEquals(MemoryCorpus.CHAT_RECALL_LONG_TERM, retriever.lastRequest?.corpus)
        assertEquals(MemoryRetrievalStrategy.HYBRID, retriever.lastRequest?.strategy)
        assertEquals(8, retriever.lastRequest?.limit)
        assertEquals(24, retriever.lastRequest?.candidateLimit)
        assertEquals(null, retriever.lastRequest?.tokenBudget)
        assertTrue(retriever.lastRequest?.includePrivate == true)
        assertEquals(
            if (retriever.lastReport?.results.isNullOrEmpty()) MemoryRetrievalMode.NONE else MemoryRetrievalMode.LEXICAL_FALLBACK,
            retriever.lastReport?.mode
        )
        if (prepared.retrievedMemories.isNotEmpty()) {
            assertFalse(prompt.contains("id: "))
            assertFalse(prompt.contains("path: ${MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME}"))
            assertFalse(prompt.contains("[DISTRACTOR]"))
        }

        return BaselineRow(
            name = name,
            mode = checkNotNull(retriever.lastReport).mode,
            invocations = retriever.invocations,
            candidateIds = retriever.candidateIds,
            selectedIds = prepared.retrievedMemories.mapNotNull(MemoryRetrievalResult::entryId),
            promptChars = prompt.length,
            legacyPackEstimateTokens = prepared.retrievedMemories.sumOf { memory ->
                TokenUsageEstimator.estimateText(
                    text = memory.text,
                    model = "",
                    clientType = ClientType.OPENAI
                ) + 24
            },
            renderedEstimatedTokens = TokenUsageEstimator.estimateText(
                text = prompt,
                model = "",
                clientType = ClientType.OPENAI
            )
        )
    }

    private fun compactBaselineEntries(): List<MarkdownMemoryEntry> = listOf(
        entry(
            id = "mem_address",
            text = "\u5e0c\u671b\u4ee5\u540e\u88ab\u79f0\u547c\u4e3a\u5927\u54e5\u3002",
            type = "stable_profile",
            updatedAt = 60L
        ),
        entry(
            id = "mem_concise",
            text = "\u7528\u6237\u504f\u597d\u7b80\u6d01\u76f4\u63a5\u7684\u56de\u7b54\uff0c\u4e0d\u9700\u8981\u5197\u957f\u94fa\u57ab\u3002",
            type = "communication_style",
            updatedAt = 50L
        ),
        entry(
            id = "mem_language",
            text = "\u7528\u6237\u504f\u597d\u4f7f\u7528\u4e2d\u6587\u56de\u590d\u3002",
            type = "communication_style",
            updatedAt = 40L
        ),
        entry(
            id = "mem_project",
            text = "ChatWithChat Android \u9879\u76ee\u7684\u957f\u671f\u8bb0\u5fc6\u53ec\u56de\u529f\u80fd\u6b63\u5728\u5f00\u53d1\u4e2d\u3002",
            type = "project_context",
            updatedAt = 30L
        ),
        entry(
            id = "mem_education",
            text = "\u76ee\u524d\u5373\u5c06\u5347\u5165\u5927\u4e8c\uff0c\u4e13\u4e1a\u662f\u8ba1\u7b97\u673a\u79d1\u5b66\u4e0e\u6280\u672f\uff08\u8ba1\u79d1\uff09\u3002",
            type = "stable_profile",
            updatedAt = 20L
        ),
        entry(
            id = "mem_server",
            text = "Linux \u670d\u52a1\u5668\u7528\u4e8e Minecraft \u8054\u673a\u3002",
            type = "project_context",
            updatedAt = 10L
        )
    )

    private fun largeCorpusEntries(): List<MarkdownMemoryEntry> = buildList {
        addAll(
            (1..18).map { index ->
                entry(
                    id = "target_event_$index",
                    text = "\u4e0a\u6d77\u6444\u5f71\u5c55\u7b79\u5907\u4e8b\u4ef6 $index\uff1a\u7ebf\u4e0b\u5c55\u89c8\u573a\u5730\u3001\u4f5c\u54c1\u88c5\u88f1\u3001\u6d77\u62a5\u5ba3\u4f20\u548c\u5fd7\u613f\u8005\u6392\u73ed\u5747\u5df2\u786e\u8ba4\u3002",
                    type = "important_event",
                    updatedAt = index.toLong()
                )
            }
        )
        addAll(
            (1..30).map { index ->
                entry(
                    id = "distractor_shanghai_$index",
                    text = "\u4e0a\u6d77\u51fa\u5dee\u8bb0\u5f55 $index\uff1a\u8ba8\u8bba\u9879\u76ee\u8fdb\u5ea6\u3001\u9884\u7b97\u548c\u4e0b\u4e00\u6b21\u4f1a\u8bae\u5b89\u6392\u3002 [DISTRACTOR]",
                    type = "project_context",
                    updatedAt = (100 + index).toLong()
                )
            }
        )
        addAll(
            (1..30).map { index ->
                entry(
                    id = "distractor_exhibition_$index",
                    text = "\u6444\u5f71\u5c55\u8d44\u6599 $index\uff1a\u6574\u7406\u65e7\u7167\u7247\u548c\u5c55\u89c8\u76ee\u5f55\uff0c\u4f46\u5c1a\u672a\u786e\u5b9a\u57ce\u5e02\u6216\u7ebf\u4e0b\u573a\u5730\u3002 [DISTRACTOR]",
                    type = "important_event",
                    updatedAt = (200 + index).toLong()
                )
            }
        )
        addAll(
            (1..30).map { index ->
                entry(
                    id = "distractor_planning_$index",
                    text = "\u6d3b\u52a8\u8ba1\u5212 $index\uff1a\u5b89\u6392\u5ba3\u4f20\u3001\u9884\u7b97\u548c\u4eba\u5458\u6392\u73ed\uff0c\u7b49\u5f85\u540e\u7eed\u786e\u8ba4\u3002 [DISTRACTOR]",
                    type = "light_productivity_preference",
                    updatedAt = (300 + index).toLong()
                )
            }
        )
    }

    private fun entry(
        id: String,
        text: String,
        type: String,
        updatedAt: Long
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = type,
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = 1L,
        updatedAt = updatedAt
    )

    private companion object {
        val EXPECTED_BASELINE = listOf(
            BaselineRow(
                name = "greeting",
                mode = MemoryRetrievalMode.NONE,
                invocations = 1,
                candidateIds = emptyList(),
                selectedIds = emptyList(),
                promptChars = 0,
                legacyPackEstimateTokens = 0,
                renderedEstimatedTokens = 0
            ),
            BaselineRow(
                name = "unrelated",
                mode = MemoryRetrievalMode.NONE,
                invocations = 1,
                candidateIds = emptyList(),
                selectedIds = emptyList(),
                promptChars = 0,
                legacyPackEstimateTokens = 0,
                renderedEstimatedTokens = 0
            ),
            BaselineRow(
                name = "preferred_address",
                mode = MemoryRetrievalMode.LEXICAL_FALLBACK,
                invocations = 1,
                candidateIds = listOf("mem_address"),
                selectedIds = listOf("mem_address"),
                promptChars = 73,
                legacyPackEstimateTokens = 33,
                renderedEstimatedTokens = 54
            ),
            BaselineRow(
                name = "preference",
                mode = MemoryRetrievalMode.LEXICAL_FALLBACK,
                invocations = 1,
                candidateIds = listOf("mem_language"),
                selectedIds = listOf("mem_language"),
                promptChars = 73,
                legacyPackEstimateTokens = 31,
                renderedEstimatedTokens = 51
            ),
            BaselineRow(
                name = "project_paraphrase",
                mode = MemoryRetrievalMode.LEXICAL_FALLBACK,
                invocations = 1,
                candidateIds = listOf("mem_project"),
                selectedIds = listOf("mem_project"),
                promptChars = 100,
                legacyPackEstimateTokens = 40,
                renderedEstimatedTokens = 60
            ),
            BaselineRow(
                name = "chinese_rewrite",
                mode = MemoryRetrievalMode.LEXICAL_FALLBACK,
                invocations = 1,
                candidateIds = listOf("mem_concise"),
                selectedIds = listOf("mem_concise"),
                promptChars = 82,
                legacyPackEstimateTokens = 41,
                renderedEstimatedTokens = 61
            ),
            BaselineRow(
                name = "single_cjk_weak",
                mode = MemoryRetrievalMode.NONE,
                invocations = 1,
                candidateIds = emptyList(),
                selectedIds = emptyList(),
                promptChars = 0,
                legacyPackEstimateTokens = 0,
                renderedEstimatedTokens = 0
            ),
            BaselineRow(
                name = "corpus_108",
                mode = MemoryRetrievalMode.LEXICAL_FALLBACK,
                invocations = 1,
                candidateIds =
                (18 downTo 1).map { index -> "target_event_$index" } +
                    (30 downTo 25).map { index -> "distractor_exhibition_$index" },
                selectedIds = (18 downTo 11).map { index -> "target_event_$index" },
                promptChars = 403,
                legacyPackEstimateTokens = 464,
                renderedEstimatedTokens = 323
            )
        )
    }
}

private data class BaselineRow(
    val name: String,
    val mode: MemoryRetrievalMode,
    val invocations: Int,
    val candidateIds: List<String>,
    val selectedIds: List<String>,
    val promptChars: Int,
    val legacyPackEstimateTokens: Int = 0,
    val renderedEstimatedTokens: Int
) {
    fun tableRow(): String = listOf(
        "memory-recall-baseline",
        name,
        mode,
        invocations,
        candidateIds.joinToString(",").ifEmpty { "-" },
        selectedIds.joinToString(",").ifEmpty { "-" },
        promptChars,
        legacyPackEstimateTokens,
        renderedEstimatedTokens
    ).joinToString("|")
}

private class BaselineRecordingRetriever(
    private val snapshot: MemoryCorpusSnapshot
) : MemoryRetriever {
    private val snapshotSource = BaselineSnapshotSource(snapshot)
    private val lexicalRetriever = MarkdownLexicalRetriever(snapshotSource)
    private val hybridRetriever = HybridMemoryRetriever(
        snapshotSource = snapshotSource,
        lexicalRetriever = lexicalRetriever,
        vectorStore = BaselineUnavailableVectorStore,
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
                configuration: MemoryVectorIndexConfiguration
            ) = null
        },
        repairTrigger = object : MemoryVectorRecallRepairTrigger {
            override fun requestRepair() = Unit
        }
    )

    var invocations: Int = 0
        private set
    var candidateIds: List<String> = emptyList()
        private set
    var lastRequest: MemoryRetrievalRequest? = null
        private set
    var lastReport: MemoryRetrievalReport? = null
        private set

    override suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> =
        retrieveWithDiagnostics(request).map(MemoryRetrievalReport::results)

    override suspend fun retrieveWithDiagnostics(request: MemoryRetrievalRequest): Result<MemoryRetrievalReport> {
        invocations += 1
        lastRequest = request
        candidateIds = lexicalRetriever.rankCandidates(
            request = request.copy(strategy = MemoryRetrievalStrategy.LEXICAL),
            combinedQuery = request.lexicalQuery(),
            snapshots = listOf(snapshot)
        ).mapNotNull(MemoryRetrievalResult::entryId)
        return hybridRetriever.retrieveWithDiagnostics(request).onSuccess { report ->
            lastReport = report
        }
    }
}

private class BaselineSnapshotSource(
    private val snapshot: MemoryCorpusSnapshot
) : MemoryCorpusSnapshotSource {
    override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
        Result.success(listOf(snapshot))

    override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> = Result.success(true)
}

private object BaselineUnavailableVectorStore : MemoryVectorStore {
    override fun readManifest(): MemoryVectorManifest? = null

    override fun countChunks(): Long = 0L

    override fun verifySnapshot(expectation: MemoryVectorSnapshotExpectation): MemoryVectorSnapshotVerification =
        MemoryVectorSnapshotVerification.Missing

    override fun replaceSnapshot(snapshot: MemoryVectorSnapshot): MemoryVectorPublishResult =
        MemoryVectorPublishResult.PUBLISHED

    override fun query(request: MemoryVectorQuery): MemoryVectorQueryResult =
        MemoryVectorQueryResult.Unavailable(MemoryVectorUnavailableReason.MISSING_MANIFEST)

    override fun clearSnapshot() = Unit

    override fun deleteDerivedStore() = Unit

    override fun recoverFromCorruption(cause: Throwable): Boolean = false

    override fun close() = Unit
}
