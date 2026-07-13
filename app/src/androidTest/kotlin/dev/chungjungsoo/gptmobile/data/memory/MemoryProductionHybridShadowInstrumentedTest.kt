package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.objectbox.BoxStore
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingArtifactInstallResult
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingArtifactInstaller
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingArtifactSource
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingAvailability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapabilitySource
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingProvider
import dev.chungjungsoo.gptmobile.data.memory.embedding.MutableMemoryEmbeddingCapabilitySource
import dev.chungjungsoo.gptmobile.data.memory.embedding.OnnxMemoryEmbeddingProvider
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryEmbeddedChunk
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexConfiguration
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexDefaults
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexIdentity
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorManifest
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorManifestState
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorMatch
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorPublishResult
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorQuery
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorQueryResult
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorSnapshot
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorSnapshotExpectation
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorSnapshotVerification
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStore
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStoreFactory
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryProductionHybridShadowInstrumentedTest {
    private lateinit var context: Context
    private lateinit var rootDirectory: File
    private lateinit var vectorDirectory: File
    private lateinit var memoryFileStore: MemoryFileStore
    private lateinit var snapshotter: MemoryCorpusSnapshotter
    private var vectorStore: MemoryVectorStore? = null
    private var embeddingProvider: OnnxMemoryEmbeddingProvider? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        rootDirectory = File(
            context.noBackupFilesDir,
            "memory_production_hybrid_shadow_test/run-${System.nanoTime()}"
        )
        vectorDirectory = File(rootDirectory, "objectbox")
        assertTrue(rootDirectory.mkdirs())
        memoryFileStore = MemoryFileStore(
            paths = MemoryFilePaths(File(rootDirectory, "markdown")),
            clock = Clock.fixed(Instant.parse("2026-07-13T08:00:00Z"), ZoneOffset.UTC)
        )
        memoryFileStore.ensureStore().getOrThrow()
        snapshotter = MemoryCorpusSnapshotter(memoryFileStore, MemoryChunker())
    }

    @After
    fun tearDown() {
        runCatching { vectorStore?.close() }
        runCatching { embeddingProvider?.close() }
        runCatching { BoxStore.deleteAllFiles(vectorDirectory) }
        rootDirectory.deleteRecursively()
    }

    @Test
    fun productionHybridShadow_realArtifacts_failClosedAndPreserveLexicalFallback() = runBlocking<Unit> {
        val installedArtifacts = when (
            val installResult = MemoryEmbeddingArtifactInstaller(
                artifactSource = MemoryEmbeddingArtifactSource { path -> context.assets.open(path) },
                noBackupFilesDir = File(rootDirectory, "installed-artifacts")
            ).install()
        ) {
            is MemoryEmbeddingArtifactInstallResult.Success -> installResult.artifacts
            is MemoryEmbeddingArtifactInstallResult.NotProvisioned -> {
                error("Production artifact provisioning failed: ${installResult.availability.reason}")
            }
        }
        assertTrue(installedArtifacts.rootDirectory.startsWith(rootDirectory))

        val onnxProvider = OnnxMemoryEmbeddingProvider.create(installedArtifacts).getOrThrow()
        embeddingProvider = onnxProvider
        val recordingProvider = RecordingEmbeddingProvider(onnxProvider)
        val configuration = MemoryVectorIndexDefaults.configuration
        val capabilitySource = MutableMemoryEmbeddingCapabilitySource()
        capabilitySource.setReady(recordingProvider, configuration)
        assertTrue(capabilitySource.current() is MemoryEmbeddingCapability.Ready)

        val objectBoxStore = MemoryVectorStoreFactory(context).createForTesting(vectorDirectory)
        vectorStore = objectBoxStore
        val recordingVectorStore = RecordingMemoryVectorStore(objectBoxStore)
        val lexicalRetriever = MarkdownLexicalRetriever(snapshotter)
        val recallStateSource = CurrentSnapshotIdentitySource()
        val codec = MarkdownMemoryCodec()

        val entriesA = productionEntries()
        val dailyEntry = memoryEntry(
            id = DAILY_ID,
            text = DAILY_TEXT,
            type = "daily_context",
            updatedAt = 90L
        )
        memoryFileStore.replaceLongTermMemory(codec.renderLongTerm(entriesA)).getOrThrow()
        memoryFileStore.appendDailyNote(codec.renderDailyAppend(listOf(dailyEntry))).getOrThrow()

        val snapshotA = currentLongTermSnapshot()
        val maintenanceSnapshots = snapshotter.snapshots(MemoryCorpus.MAINTENANCE_WORKING_SET).getOrThrow()
        val dailyChunk = maintenanceSnapshots
            .flatMap(MemoryCorpusSnapshot::chunks)
            .single { chunk -> chunk.entryId == DAILY_ID }
        assertFalse(snapshotA.chunks.any { chunk -> chunk.entryId == DAILY_ID })
        assertFalse(snapshotA.chunks.any { chunk -> chunk.sourcePath != MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME })

        val identityA = publishSnapshot(
            snapshot = snapshotA,
            configuration = configuration,
            provider = recordingProvider,
            store = objectBoxStore
        )
        assertFalse(recordingProvider.documentInputHashes.contains(DAILY_TEXT.sha256Utf8()))
        assertEquals(snapshotA.chunks.size, recordingProvider.documentInputCount)
        assertEquals(snapshotA.chunks.size.toLong(), objectBoxStore.countChunks())
        logSnapshot("A", snapshotA, identityA)

        val readyRepair = RecordingRepairTrigger()
        val readyRetriever = hybridRetriever(
            lexicalRetriever = lexicalRetriever,
            vectorStore = recordingVectorStore,
            capabilitySource = capabilitySource,
            recallStateSource = recallStateSource,
            repairTrigger = readyRepair
        )

        val ordinaryResults = readyRetriever.retrieve(hybridRequest(DAILY_QUERY)).getOrThrow()
        assertFalse(ordinaryResults.any { result -> result.entryId == DAILY_ID })
        assertFalse(ordinaryResults.any { result -> result.contentHash == dailyChunk.contentHash })
        assertTrue(ordinaryResults.all { result -> result.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME })
        assertFalse(recordingProvider.allInputHashes.contains(DAILY_TEXT.sha256Utf8()))

        val lexicalParaphraseResults = lexicalRetriever.retrieve(
            hybridRequest(PARAPHRASE_QUERY).copy(strategy = MemoryRetrievalStrategy.LEXICAL)
        ).getOrThrow()
        assertFalse(lexicalParaphraseResults.any { result -> result.entryId == TARGET_ID })

        recordingProvider.reset()
        recordingVectorStore.resetReadCounts()
        readyRepair.reset()
        val paraphraseResults = readyRetriever.retrieve(hybridRequest(PARAPHRASE_QUERY, limit = 5)).getOrThrow()
        val paraphraseTarget = paraphraseResults.firstOrNull { result -> result.entryId == TARGET_ID }
        assertTrue(paraphraseTarget != null)
        assertTrue(paraphraseTarget?.vectorScore != null)
        assertTrue(paraphraseResults.indexOfFirst { result -> result.entryId == TARGET_ID } in 0..4)
        assertEquals(1, recordingProvider.embedQueryCalls)
        assertEquals(1, recordingVectorStore.queryCalls)

        val deletedEmbedding = recordingProvider.embedQuery(DELETED_QUERY).getOrThrow()
        val directOldQuery = objectBoxStore.query(
            MemoryVectorQuery(
                expectedIdentity = identityA,
                embedding = deletedEmbedding,
                limit = snapshotA.chunks.size
            )
        ) as MemoryVectorQueryResult.Ready
        assertTrue(directOldQuery.matches.any { match -> match.chunk.entryId == DELETED_ID })
        Log.i(
            LOG_TAG,
            "phase=A_DIRECT generation=${identityA.corpusGeneration} " +
                "sourceHash=${identityA.sourceHash} fingerprint=${identityA.indexFingerprint} " +
                "count=${directOldQuery.matches.size} ids=${directOldQuery.matches.vectorMatchIds()}"
        )

        val entriesB = entriesA.filterNot { entry -> entry.id == DELETED_ID }
        memoryFileStore.replaceLongTermMemory(codec.renderLongTerm(entriesB)).getOrThrow()
        val snapshotB = currentLongTermSnapshot()
        assertFalse(snapshotB.chunks.any { chunk -> chunk.entryId == DELETED_ID })

        recordingProvider.reset()
        recordingVectorStore.resetReadCounts()
        readyRepair.reset()
        val staleResults = readyRetriever.retrieve(hybridRequest(DELETED_QUERY)).getOrThrow()
        assertEquals(1, recordingVectorStore.verifyCalls)
        assertEquals(0, recordingVectorStore.queryCalls)
        assertEquals(0, recordingProvider.embedQueryCalls)
        assertEquals(1, readyRepair.requestCalls)
        assertFalse(staleResults.any { result -> result.entryId == DELETED_ID })
        assertEquals(snapshotA.chunks.size.toLong(), objectBoxStore.countChunks())
        Log.i(
            LOG_TAG,
            "phase=B_STALE generation=${snapshotB.generation} sourceHash=${snapshotB.sourceHash} " +
                "fingerprint=${configuration.fingerprint()} count=${objectBoxStore.countChunks()} " +
                "ids=${staleResults.retrievalIds()}"
        )

        recordingProvider.reset()
        val identityB = publishSnapshot(
            snapshot = snapshotB,
            configuration = configuration,
            provider = recordingProvider,
            store = objectBoxStore
        )
        assertEquals(snapshotB.chunks.size.toLong(), objectBoxStore.countChunks())
        assertFalse(recordingProvider.documentInputHashes.contains(DAILY_TEXT.sha256Utf8()))

        val directRebuiltQuery = objectBoxStore.query(
            MemoryVectorQuery(
                expectedIdentity = identityB,
                embedding = recordingProvider.embedQuery(DELETED_QUERY).getOrThrow(),
                limit = snapshotB.chunks.size
            )
        ) as MemoryVectorQueryResult.Ready
        assertFalse(directRebuiltQuery.matches.any { match -> match.chunk.entryId == DELETED_ID })

        recordingProvider.reset()
        recordingVectorStore.resetReadCounts()
        readyRepair.reset()
        val rebuiltResults = readyRetriever.retrieve(hybridRequest(DELETED_QUERY)).getOrThrow()
        assertFalse(rebuiltResults.any { result -> result.entryId == DELETED_ID })
        assertEquals(1, recordingVectorStore.queryCalls)
        assertEquals(0, readyRepair.requestCalls)
        logSnapshot("B_READY", snapshotB, identityB)

        capabilitySource.setUnavailable(
            MemoryEmbeddingAvailability.Unavailable(MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED)
        )
        val unavailable = capabilitySource.current() as MemoryEmbeddingCapability.Unavailable
        assertEquals(
            MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED,
            (unavailable.availability as MemoryEmbeddingAvailability.Unavailable).reason
        )

        val fallbackRequest = hybridRequest(LEXICAL_FALLBACK_QUERY)
        val lexicalFallback = lexicalRetriever.retrieve(
            fallbackRequest.copy(strategy = MemoryRetrievalStrategy.LEXICAL)
        ).getOrThrow()
        recordingProvider.reset()
        recordingVectorStore.resetReadCounts()
        val fallbackRepair = RecordingRepairTrigger()
        val unavailableRetriever = hybridRetriever(
            lexicalRetriever = lexicalRetriever,
            vectorStore = recordingVectorStore,
            capabilitySource = capabilitySource,
            recallStateSource = recallStateSource,
            repairTrigger = fallbackRepair
        )
        val hybridFallback = unavailableRetriever.retrieve(fallbackRequest).getOrThrow()
        assertEquals(lexicalFallback.idAndHashProjection(), hybridFallback.idAndHashProjection())
        assertEquals(0, recordingProvider.embedDocumentCalls)
        assertEquals(0, recordingProvider.embedQueryCalls)
        assertEquals(0, recordingVectorStore.verifyCalls)
        assertEquals(0, recordingVectorStore.queryCalls)
        assertEquals(1, fallbackRepair.requestCalls)

        Log.i(
            LOG_TAG,
            "$SUCCESS_CHECKPOINT generation=${identityB.corpusGeneration} sourceHash=${identityB.sourceHash} " +
                "fingerprint=${identityB.indexFingerprint} count=${objectBoxStore.countChunks()} " +
                "ids=${hybridFallback.retrievalIds()}"
        )
    }

    private suspend fun publishSnapshot(
        snapshot: MemoryCorpusSnapshot,
        configuration: MemoryVectorIndexConfiguration,
        provider: MemoryEmbeddingProvider,
        store: MemoryVectorStore
    ): MemoryVectorIndexIdentity {
        val identity = configuration.identity(
            sourcePath = snapshot.sourcePath,
            sourceHash = snapshot.sourceHash,
            corpusGeneration = snapshot.generation
        )
        val embeddings = provider.embedDocuments(snapshot.chunks.map(MemoryCorpusChunk::text)).getOrThrow()
        val result = store.replaceSnapshot(
            MemoryVectorSnapshot(
                manifest = MemoryVectorManifest(
                    identity = identity,
                    expectedChunkCount = snapshot.chunks.size.toLong(),
                    completedAt = snapshot.generation.coerceAtLeast(1L),
                    state = MemoryVectorManifestState.READY
                ),
                chunks = snapshot.chunks.zip(embeddings) { chunk, embedding ->
                    MemoryEmbeddedChunk(chunk, embedding)
                }
            )
        )
        assertTrue(result == MemoryVectorPublishResult.PUBLISHED || result == MemoryVectorPublishResult.ALREADY_READY)
        return identity
    }

    private suspend fun currentLongTermSnapshot(): MemoryCorpusSnapshot = snapshotter
        .snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM)
        .getOrThrow()
        .single()

    private fun hybridRetriever(
        lexicalRetriever: MarkdownLexicalRetriever,
        vectorStore: MemoryVectorStore,
        capabilitySource: MemoryEmbeddingCapabilitySource,
        recallStateSource: MemoryVectorRecallStateSource,
        repairTrigger: MemoryVectorRecallRepairTrigger
    ) = HybridMemoryRetriever(
        snapshotSource = snapshotter,
        lexicalRetriever = lexicalRetriever,
        vectorStore = vectorStore,
        embeddingCapabilitySource = capabilitySource,
        vectorRecallStateSource = recallStateSource,
        repairTrigger = repairTrigger
    )

    private fun hybridRequest(query: String, limit: Int = 8) = MemoryRetrievalRequest(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        query = query,
        strategy = MemoryRetrievalStrategy.HYBRID,
        limit = limit,
        candidateLimit = 20,
        tokenBudget = 1_200
    )

    private fun productionEntries(): List<MarkdownMemoryEntry> = listOf(
        memoryEntry(TARGET_ID, TARGET_TEXT, "communication_style", updatedAt = 100L),
        memoryEntry(DELETED_ID, DELETED_TEXT, "communication_style", updatedAt = 95L),
        memoryEntry("mem_android_shadow", "用户经常使用 Kotlin 编写 Android 应用。", "project_context", 80L),
        memoryEntry("mem_travel_shadow", "用户正在规划夏季旅行路线和酒店预订。", "project_context", 70L),
        memoryEntry("mem_health_shadow", "用户偏爱清淡饮食并记录每日步数。", "stable_profile", 60L),
        memoryEntry("mem_server_shadow", "项目部署依赖远程 Linux 服务器。", "project_context", 50L)
    )

    private fun memoryEntry(
        id: String,
        text: String,
        type: String,
        updatedAt: Long
    ) = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = type,
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = updatedAt - 1,
        updatedAt = updatedAt,
        section = "Shadow Gate"
    )

    private fun logSnapshot(
        phase: String,
        snapshot: MemoryCorpusSnapshot,
        identity: MemoryVectorIndexIdentity
    ) {
        Log.i(
            LOG_TAG,
            "phase=$phase generation=${identity.corpusGeneration} sourceHash=${snapshot.sourceHash} " +
                "fingerprint=${identity.indexFingerprint} count=${snapshot.chunks.size} ids=${snapshot.chunks.chunkIds()}"
        )
    }

    private fun List<MemoryVectorMatch>.vectorMatchIds(): String =
        joinToString(",") { match -> match.chunk.entryId ?: match.chunk.chunkId }

    private fun List<MemoryRetrievalResult>.retrievalIds(): String =
        joinToString(",") { result -> result.entryId ?: result.chunkId }

    private fun List<MemoryCorpusChunk>.chunkIds(): String =
        joinToString(",") { chunk -> chunk.entryId ?: chunk.chunkId }

    private fun List<MemoryRetrievalResult>.idAndHashProjection(): List<Pair<String?, String>> =
        map { result -> result.entryId to result.contentHash }

    private class CurrentSnapshotIdentitySource : MemoryVectorRecallStateSource {
        override suspend fun expectedIdentity(
            snapshot: MemoryCorpusSnapshot,
            configuration: MemoryVectorIndexConfiguration
        ): MemoryVectorIndexIdentity = configuration.identity(
            sourcePath = snapshot.sourcePath,
            sourceHash = snapshot.sourceHash,
            corpusGeneration = snapshot.generation
        )
    }

    private class RecordingRepairTrigger : MemoryVectorRecallRepairTrigger {
        var requestCalls = 0
            private set

        override fun requestRepair() {
            requestCalls += 1
        }

        fun reset() {
            requestCalls = 0
        }
    }

    private class RecordingEmbeddingProvider(
        private val delegate: MemoryEmbeddingProvider
    ) : MemoryEmbeddingProvider {
        override val descriptor = delegate.descriptor
        var embedDocumentCalls = 0
            private set
        var embedQueryCalls = 0
            private set
        var documentInputCount = 0
            private set
        val documentInputHashes = mutableListOf<String>()
        val allInputHashes = mutableListOf<String>()

        override suspend fun availability(): MemoryEmbeddingAvailability = delegate.availability()

        override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> {
            embedDocumentCalls += 1
            documentInputCount += texts.size
            val hashes = texts.map(String::sha256Utf8)
            documentInputHashes += hashes
            allInputHashes += hashes
            return delegate.embedDocuments(texts)
        }

        override suspend fun embedQuery(text: String): Result<FloatArray> {
            embedQueryCalls += 1
            allInputHashes += text.sha256Utf8()
            return delegate.embedQuery(text)
        }

        fun reset() {
            embedDocumentCalls = 0
            embedQueryCalls = 0
            documentInputCount = 0
            documentInputHashes.clear()
            allInputHashes.clear()
        }
    }

    private class RecordingMemoryVectorStore(
        private val delegate: MemoryVectorStore
    ) : MemoryVectorStore by delegate {
        var verifyCalls = 0
            private set
        var queryCalls = 0
            private set

        override fun verifySnapshot(
            expectation: MemoryVectorSnapshotExpectation
        ): MemoryVectorSnapshotVerification {
            verifyCalls += 1
            return delegate.verifySnapshot(expectation)
        }

        override fun query(request: MemoryVectorQuery): MemoryVectorQueryResult {
            queryCalls += 1
            return delegate.query(request)
        }

        fun resetReadCounts() {
            verifyCalls = 0
            queryCalls = 0
        }
    }

    private companion object {
        const val LOG_TAG = "MemoryHybridShadow"
        const val SUCCESS_CHECKPOINT = "MEMORY_HYBRID_SHADOW_OK"
        const val TARGET_ID = "mem_concise_shadow"
        const val TARGET_TEXT = "用户希望回复简洁直达重点，避免冗长铺垫。"
        const val DELETED_ID = "mem_deleted_shadow"
        const val DELETED_TEXT = "用户曾经要求所有界面采用鲜红色主题。"
        const val DAILY_ID = "mem_daily_shadow"
        const val DAILY_TEXT = "仅在今天处理一次性的紫藤凭证轮换事项。"
        const val DAILY_QUERY = "查找今天临时记录的一次性事项"
        const val PARAPHRASE_QUERY = "答复别绕弯子，短些即可"
        const val DELETED_QUERY = "鲜红配色界面主题"
        const val LEXICAL_FALLBACK_QUERY = "Kotlin Android"
    }
}
