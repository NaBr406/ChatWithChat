package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryCorpusState
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingAvailability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapabilitySource
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingDescriptor
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingPooling
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingProvider
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorDistanceMetric
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexConfiguration
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
import dev.chungjungsoo.gptmobile.di.MemoryRepositoryModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridMemoryRetrieverTest {

    @Test
    fun `semantic paraphrase retrieves current visible memory with vector provenance`() = runBlocking {
        val target = chunk("target", "mem_target", "用户偏好简洁直接的回答。")
        val distractor = chunk("distractor", "mem_distractor", "用户常用 Kotlin 开发 Android 应用。")
        val fixture = fixture(snapshot(chunks = listOf(target, distractor)))
        fixture.vectorStore.matches = listOf(match(target, TARGET_VECTOR, 0.05f))

        val results = fixture.retriever.retrieve(request("别绕弯子，尽快说重点")).getOrThrow()

        assertEquals(listOf("mem_target"), results.map { result -> result.entryId })
        assertNull(results.single().lexicalScore)
        assertTrue(results.single().vectorScore!! > 0f)
        assertTrue(results.single().fusedScore > 0f)
        assertEquals(1, fixture.provider.embedQueryCalls)
        assertEquals(1, fixture.vectorStore.queryCalls)
    }

    @Test
    fun `hybrid uses deterministic RRF and keeps lexical and vector scores`() = runBlocking {
        val shared = chunk("shared", "mem_shared", "Concrete implementation steps are preferred.")
        val lexicalOnly = chunk("lexical", "mem_lexical", "Concrete implementation checklist.")
        val fixture = fixture(snapshot(chunks = listOf(shared, lexicalOnly)))
        fixture.vectorStore.matches = listOf(match(shared, TARGET_VECTOR, 0.01f))

        val results = fixture.retriever.retrieve(request("concrete implementation steps")).getOrThrow()
        val result = results.first { item -> item.entryId == "mem_shared" }

        assertTrue(result.lexicalScore!! > 0f)
        assertTrue(result.vectorScore!! > 0f)
        assertEquals(2f / 61f, result.fusedScore, 0.000001f)
    }

    @Test
    fun `stale vector snapshot is never queried and deleted text cannot leak`() = runBlocking {
        val current = chunk("current", "mem_current", "Current blue preference.")
        val deleted = chunk("deleted", "mem_deleted", "Deleted red preference.")
        val fixture = fixture(snapshot(sourceHash = "a".repeat(64), chunks = listOf(current)))
        fixture.vectorStore.verification = MemoryVectorSnapshotVerification.Stale(
            readyManifest(
                fixture.configuration.identity(
                    sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                    sourceHash = "b".repeat(64),
                    corpusGeneration = DURABLE_GENERATION
                ),
                1
            )
        )
        fixture.vectorStore.matches = listOf(match(deleted, TARGET_VECTOR, 0f))

        val results = fixture.retriever.retrieve(request("deleted red")).getOrThrow()

        assertTrue(results.isEmpty())
        assertEquals(1, fixture.vectorStore.verifyCalls)
        assertEquals(0, fixture.vectorStore.queryCalls)
        assertEquals(0, fixture.provider.embedQueryCalls)
        assertEquals(1, fixture.repairTrigger.requestCalls)
    }

    @Test
    fun `unavailable model returns current Chinese and English lexical results`() = runBlocking {
        val chinese = chunk("zh", "mem_zh", "用户喜欢直接具体的回答方式。")
        val english = chunk("en", "mem_en", "The user prefers concrete implementation steps.")
        val fixture = fixture(
            snapshot(chunks = listOf(chinese, english)),
            capability = unavailableCapability()
        )

        val chineseResults = fixture.retriever.retrieve(request("请直接具体回答")).getOrThrow()
        val englishResults = fixture.retriever.retrieve(request("implementation steps")).getOrThrow()

        assertEquals("mem_zh", chineseResults.first().entryId)
        assertEquals("mem_en", englishResults.first().entryId)
        assertEquals(0, fixture.provider.embedQueryCalls)
        assertEquals(0, fixture.vectorStore.verifyCalls)
        assertEquals(0, fixture.vectorStore.queryCalls)
        assertEquals(2, fixture.repairTrigger.requestCalls)
    }

    @Test
    fun `fusion deduplicates entry ids and fallback content hashes`() = runBlocking {
        val firstPart = chunk("entry-1", "mem_same", "Cross branch preference phrase.")
        val secondPart = chunk("entry-2", "mem_same", "Second part of a preference.")
        val fallbackOne = chunk("fallback-1", null, "Fallback duplicate.", contentHash = "c".repeat(64))
        val fallbackTwo = chunk("fallback-2", null, "Fallback duplicate.", contentHash = "c".repeat(64))
        val fixture = fixture(snapshot(chunks = listOf(firstPart, secondPart, fallbackOne, fallbackTwo)))
        fixture.vectorStore.matches = listOf(
            match(firstPart, TARGET_VECTOR, 0f),
            match(secondPart, NEAR_VECTOR, 0.01f),
            match(fallbackOne, DIVERSE_VECTOR, 0.02f),
            match(fallbackTwo, OTHER_VECTOR, 0.03f)
        )

        val results = fixture.retriever.retrieve(request("cross branch preference")).getOrThrow()

        assertEquals(2, results.size)
        assertEquals(1, results.count { result -> result.entryId == "mem_same" })
        val merged = results.single { result -> result.entryId == "mem_same" }
        assertTrue(merged.lexicalScore != null)
        assertTrue(merged.vectorScore != null)
        assertEquals(1, results.count { result -> result.entryId == null && result.contentHash == "c".repeat(64) })
    }

    @Test
    fun `vector exact duplicates trigger bounded overfetch without consuming candidate limit`() = runBlocking {
        val first = chunk("a", "mem_duplicate_a", "Same semantic memory.")
        val duplicate = chunk("b", "mem_duplicate_b", "  SAME   SEMANTIC MEMORY.  ")
        val unique = chunk("c", "mem_unique", "Different semantic memory.")
        val fixture = fixture(snapshot(chunks = listOf(first, duplicate, unique)))
        fixture.vectorStore.matches = listOf(
            match(first, TARGET_VECTOR, 0f),
            match(duplicate, NEAR_VECTOR, 0.01f),
            match(unique, DIVERSE_VECTOR, 0.02f)
        )

        val results = fixture.retriever.retrieve(
            request("vector only").copy(limit = 2, candidateLimit = 2)
        ).getOrThrow()

        assertEquals(listOf("mem_duplicate_a", "mem_unique"), results.map { result -> result.entryId })
        assertEquals(listOf(2, 3), fixture.vectorStore.queryLimits)
    }

    @Test
    fun `vector overfetch reaches unique candidates beyond five hundred exact duplicates`() = runBlocking {
        val duplicates = (0 until 600).map { index ->
            chunk("duplicate-$index", "mem_duplicate_$index", "Repeated historical memory.")
        }
        val unique = chunk("unique", "mem_unique", "Unique memory after historical duplicates.")
        val chunks = duplicates + unique
        val fixture = fixture(snapshot(chunks = chunks))
        fixture.vectorStore.approximateReturnCap = 153
        fixture.vectorStore.matches = duplicates.mapIndexed { index, chunk ->
            match(chunk, TARGET_VECTOR, index / 10_000f)
        } + match(unique, DIVERSE_VECTOR, 0.1f)

        val results = fixture.retriever.retrieve(
            request("vector only").copy(limit = 2, candidateLimit = 2)
        ).getOrThrow()

        assertEquals(listOf("mem_duplicate_0", "mem_unique"), results.map { result -> result.entryId })
        assertEquals(601, fixture.vectorStore.queryLimits.last())
        assertTrue(fixture.vectorStore.queryLimits.any { limit -> limit > 500 })
        assertTrue(fixture.vectorStore.queryLimits.size > 2)
    }

    @Test
    fun `hybrid keeps the highest ranked representative of cross branch exact text`() = runBlocking {
        val lexical = chunk("a", "mem_lexical", "Shared exact memory.")
        val vectorDuplicate = chunk("b", "mem_vector", "  SHARED   EXACT MEMORY.  ")
        val vectorUnique = chunk("c", "mem_unique", "Independent vector context.")
        val fixture = fixture(snapshot(chunks = listOf(lexical, vectorDuplicate, vectorUnique)))
        fixture.vectorStore.matches = listOf(
            match(vectorDuplicate, TARGET_VECTOR, 0f),
            match(vectorUnique, DIVERSE_VECTOR, 0.01f)
        )

        val results = fixture.retriever.retrieve(
            request("shared exact memory").copy(limit = 3, candidateLimit = 3)
        ).getOrThrow()

        assertEquals(listOf("mem_lexical", "mem_unique"), results.map { result -> result.entryId })
        assertEquals(1, results.count { result -> normalizeExactMemoryText(result.text) == "shared exact memory." })
    }

    @Test
    fun `unavailable vector fallback keeps exact duplicates from consuming lexical candidates`() = runBlocking {
        val first = chunk("a", "mem_duplicate_a", "Shared fallback memory.")
        val duplicate = chunk("b", "mem_duplicate_b", "  SHARED   FALLBACK MEMORY.  ")
        val unique = chunk("c", "mem_unique", "Shared unique context.")
        val fixture = fixture(
            snapshot(chunks = listOf(first, duplicate, unique)),
            capability = unavailableCapability()
        )

        val results = fixture.retriever.retrieve(
            request("shared").copy(limit = 2, candidateLimit = 2)
        ).getOrThrow()

        assertEquals(listOf("mem_duplicate_a", "mem_unique"), results.map { result -> result.entryId })
        assertEquals(0, fixture.vectorStore.queryCalls)
        assertEquals(1, fixture.repairTrigger.requestCalls)
    }

    @Test
    fun `MMR selects a diverse vector result before a near duplicate`() = runBlocking {
        val first = chunk("first", "mem_first", "Primary response style.")
        val nearDuplicate = chunk("near", "mem_near", "Very similar response style.")
        val diverse = chunk("diverse", "mem_diverse", "Independent project context.")
        val fixture = fixture(snapshot(chunks = listOf(first, nearDuplicate, diverse)))
        fixture.vectorStore.matches = listOf(
            match(first, TARGET_VECTOR, 0f),
            match(nearDuplicate, TARGET_VECTOR, 0.01f),
            match(diverse, DIVERSE_VECTOR, 0.02f)
        )

        val results = fixture.retriever.retrieve(request("semantic only")).getOrThrow()

        assertEquals(listOf("mem_first", "mem_diverse", "mem_near"), results.map { result -> result.entryId })
    }

    @Test
    fun `token budget is applied after fusion`() = runBlocking {
        val first = chunk("first", "mem_first", "Short preference.")
        val second = chunk("second", "mem_second", "Other preference.")
        val fixture = fixture(snapshot(chunks = listOf(first, second)))
        fixture.vectorStore.matches = listOf(
            match(first, TARGET_VECTOR, 0f),
            match(second, DIVERSE_VECTOR, 0.01f)
        )

        val results = fixture.retriever.retrieve(
            request("semantic only").copy(tokenBudget = 30)
        ).getOrThrow()

        assertEquals(1, results.size)
    }

    @Test
    fun `lexical similarity provides deterministic MMR when vectors are unavailable`() = runBlocking {
        val first = chunk("a", "mem_a", "Topic alpha shared duplicate.")
        val nearDuplicate = chunk("b", "mem_b", "Topic alpha shared duplicate extra.")
        val diverse = chunk("c", "mem_c", "Topic independent project context.")
        val fixture = fixture(
            snapshot(chunks = listOf(first, nearDuplicate, diverse)),
            capability = unavailableCapability()
        )

        val firstRun = fixture.retriever.retrieve(request("topic")).getOrThrow()
        val secondRun = fixture.retriever.retrieve(request("topic")).getOrThrow()

        assertEquals(listOf("mem_a", "mem_c", "mem_b"), firstRun.map { result -> result.entryId })
        assertEquals(firstRun.map { result -> result.entryId }, secondRun.map { result -> result.entryId })
    }

    @Test
    fun `query time manifest mismatch discards vector results and requests repair`() = runBlocking {
        val current = chunk("current", "mem_current", "Current lexical phrase.")
        val fixture = fixture(snapshot(chunks = listOf(current)))
        fixture.vectorStore.queryResult = MemoryVectorQueryResult.Unavailable(
            dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorUnavailableReason.STALE_MANIFEST
        )

        val results = fixture.retriever.retrieve(request("current lexical")).getOrThrow()

        assertEquals(listOf("mem_current"), results.map { result -> result.entryId })
        assertEquals(1, fixture.vectorStore.queryCalls)
        assertEquals(1, fixture.repairTrigger.requestCalls)
    }

    @Test
    fun `vector match cannot replace current Markdown metadata or text`() = runBlocking {
        val current = chunk("current", "mem_current", "Current authoritative text.")
        val staleMetadata = current.copy(
            text = "Stale vector text.",
            sensitivity = MemorySensitivity.PRIVATE,
            source = "stale_vector_metadata"
        )
        val fixture = fixture(snapshot(chunks = listOf(current)))
        fixture.vectorStore.matches = listOf(match(staleMetadata, TARGET_VECTOR, 0f))

        val result = fixture.retriever.retrieve(request("semantic only")).getOrThrow().single()

        assertEquals(current.text, result.text)
        assertEquals(current.sensitivity, result.sensitivity)
        assertEquals(current.source, result.source)
    }

    @Test
    fun `source revision change retries from a new exact snapshot`() = runBlocking {
        val old = snapshot(
            sourceHash = "d".repeat(64),
            generation = 1,
            chunks = listOf(chunk("old", "mem_old", "Old current phrase."))
        )
        val current = snapshot(
            sourceHash = "e".repeat(64),
            generation = 2,
            chunks = listOf(chunk("new", "mem_new", "New current phrase."))
        )
        val source = SequencedSnapshotSource(listOf(old, current), currentGeneration = 2)
        val fixture = fixture(current, snapshotSource = source, capability = unavailableCapability())

        val results = fixture.retriever.retrieve(request("current phrase")).getOrThrow()

        assertEquals(2, source.snapshotCalls)
        assertEquals(listOf("mem_new"), results.map { result -> result.entryId })
    }

    @Test
    fun `Room generation rather than process local snapshot revision identifies vectors`() = runBlocking {
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val configuration = configuration()
        val snapshot = snapshot(generation = 3)
        recoveryDao.insertCorpusStateIgnore(
            MemoryCorpusState(
                corpus = CHAT_RECALL_CORPUS_KEY,
                sourcePath = snapshot.sourcePath,
                sourceHash = snapshot.sourceHash,
                generation = DURABLE_GENERATION,
                targetIndexFingerprint = configuration.fingerprint(),
                indexStatus = MemoryCorpusIndexStatus.READY,
                indexedGeneration = DURABLE_GENERATION,
                indexedSourceHash = snapshot.sourceHash,
                indexedFingerprint = configuration.fingerprint(),
                latestReceiptId = "receipt",
                lastError = null,
                createdAt = 1,
                updatedAt = 2
            )
        )

        val identity = RoomMemoryVectorRecallStateSource(recoveryDao).expectedIdentity(snapshot, configuration)

        assertEquals(DURABLE_GENERATION, identity?.corpusGeneration)
        assertTrue(identity?.corpusGeneration != snapshot.generation)
    }

    @Test
    fun `Room freshness gate rejects a mismatched indexed fingerprint`() = runBlocking {
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val configuration = configuration()
        val snapshot = snapshot()
        recoveryDao.insertCorpusStateIgnore(
            MemoryCorpusState(
                corpus = CHAT_RECALL_CORPUS_KEY,
                sourcePath = snapshot.sourcePath,
                sourceHash = snapshot.sourceHash,
                generation = DURABLE_GENERATION,
                targetIndexFingerprint = configuration.fingerprint(),
                indexStatus = MemoryCorpusIndexStatus.READY,
                indexedGeneration = DURABLE_GENERATION,
                indexedSourceHash = snapshot.sourceHash,
                indexedFingerprint = "f".repeat(64),
                latestReceiptId = "receipt",
                lastError = null,
                createdAt = 1,
                updatedAt = 2
            )
        )

        assertNull(RoomMemoryVectorRecallStateSource(recoveryDao).expectedIdentity(snapshot, configuration))
    }

    @Test
    fun `repair trigger coalesces repeated recall failures`() {
        val enqueuer = RecordingWorkEnqueuer()
        val trigger = WorkEnqueuingMemoryVectorRecallRepairTrigger(
            workEnqueuer = enqueuer,
            clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC),
            minimumIntervalSeconds = 60
        )

        trigger.requestRepair()
        trigger.requestRepair()

        assertEquals(listOf(MemoryMaintenanceJobFamily.REPAIR), enqueuer.families)
    }

    @Test
    fun `production DI uses hybrid while maintenance remains lexical after shadow gate passes`() {
        val source = StaticSnapshotSource(snapshot())
        val lexical = MarkdownLexicalRetriever(source)
        val hybrid = fixture(snapshot(), snapshotSource = source).retriever

        assertTrue(MemoryRepositoryModule.provideMemoryRetriever(hybrid) === hybrid)
        assertTrue(MemoryRepositoryModule.provideMemoryMaintenanceCorpusReader(lexical) === lexical)
    }

    private fun fixture(
        snapshot: MemoryCorpusSnapshot,
        snapshotSource: MemoryCorpusSnapshotSource = StaticSnapshotSource(snapshot),
        capability: MemoryEmbeddingCapability? = null
    ): Fixture {
        val configuration = configuration()
        val provider = FakeEmbeddingProvider()
        val resolvedCapability = capability ?: MemoryEmbeddingCapability.Ready(provider, configuration)
        val identity = configuration.identity(
            sourcePath = snapshot.sourcePath,
            sourceHash = snapshot.sourceHash,
            corpusGeneration = DURABLE_GENERATION
        )
        val vectorStore = FakeVectorStore(identity)
        val repairTrigger = FakeRepairTrigger()
        val lexicalRetriever = MarkdownLexicalRetriever(snapshotSource)
        val retriever = HybridMemoryRetriever(
            snapshotSource = snapshotSource,
            lexicalRetriever = lexicalRetriever,
            vectorStore = vectorStore,
            embeddingCapabilitySource = MemoryEmbeddingCapabilitySource { resolvedCapability },
            vectorRecallStateSource = FixedVectorRecallStateSource(DURABLE_GENERATION),
            repairTrigger = repairTrigger
        )
        return Fixture(retriever, provider, vectorStore, repairTrigger, configuration)
    }

    private fun request(query: String) = MemoryRetrievalRequest(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        query = query,
        strategy = MemoryRetrievalStrategy.HYBRID,
        limit = 8,
        candidateLimit = 20,
        tokenBudget = 300
    )

    private fun snapshot(
        sourceHash: String = "a".repeat(64),
        generation: Long = 7,
        chunks: List<MemoryCorpusChunk> = listOf(chunk("default", "mem_default", "Default memory."))
    ) = MemoryCorpusSnapshot(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
        sourceHash = sourceHash,
        generation = generation,
        chunks = chunks
    )

    private fun chunk(
        chunkId: String,
        entryId: String?,
        text: String,
        contentHash: String = text.sha256Utf8()
    ) = MemoryCorpusChunk(
        chunkId = chunkId,
        entryId = entryId,
        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
        chunkIndex = 0,
        heading = "Stable Preferences",
        text = text,
        type = "communication_style",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        chatId = null,
        createdAt = 1,
        updatedAt = 2,
        contentHash = contentHash
    )

    private fun match(
        chunk: MemoryCorpusChunk,
        embedding: FloatArray,
        distance: Float
    ) = MemoryVectorMatch(chunk, embedding, distance)

    private fun configuration() = MemoryVectorIndexConfiguration(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        indexSchemaVersion = 1,
        chunkerVersion = "test-chunker-v1",
        maxChunkChars = 1_200,
        chunkOverlapChars = 0,
        markdownCodecVersion = "test-markdown-v1",
        embeddingDescriptor = TEST_DESCRIPTOR,
        queryTextNormalization = "test-query-v1",
        documentTextNormalization = "test-document-v1",
        distanceMetric = MemoryVectorDistanceMetric.COSINE
    )

    private fun unavailableCapability() = MemoryEmbeddingCapability.Unavailable(
        MemoryEmbeddingAvailability.Unavailable(MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED)
    )

    private data class Fixture(
        val retriever: HybridMemoryRetriever,
        val provider: FakeEmbeddingProvider,
        val vectorStore: FakeVectorStore,
        val repairTrigger: FakeRepairTrigger,
        val configuration: MemoryVectorIndexConfiguration
    )

    private class FakeRepairTrigger : MemoryVectorRecallRepairTrigger {
        var requestCalls = 0

        override fun requestRepair() {
            requestCalls += 1
        }
    }

    private class FixedVectorRecallStateSource(
        private val generation: Long
    ) : MemoryVectorRecallStateSource {
        override suspend fun expectedIdentity(
            snapshot: MemoryCorpusSnapshot,
            configuration: MemoryVectorIndexConfiguration
        ): MemoryVectorIndexIdentity = configuration.identity(
            sourcePath = snapshot.sourcePath,
            sourceHash = snapshot.sourceHash,
            corpusGeneration = generation
        )
    }

    private class FakeEmbeddingProvider : MemoryEmbeddingProvider {
        override val descriptor = TEST_DESCRIPTOR
        var embedQueryCalls = 0

        override suspend fun availability(): MemoryEmbeddingAvailability = MemoryEmbeddingAvailability.Available

        override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> =
            Result.success(texts.map { TARGET_VECTOR.copyOf() })

        override suspend fun embedQuery(text: String): Result<FloatArray> {
            embedQueryCalls += 1
            return Result.success(TARGET_VECTOR.copyOf())
        }
    }

    private class FakeVectorStore(
        private val identity: MemoryVectorIndexIdentity
    ) : MemoryVectorStore {
        var matches = emptyList<MemoryVectorMatch>()
        var verification: MemoryVectorSnapshotVerification? = null
        var queryResult: MemoryVectorQueryResult? = null
        var approximateReturnCap: Int? = null
        var verifyCalls = 0
        var queryCalls = 0
        val queryLimits = mutableListOf<Int>()

        override fun readManifest(): MemoryVectorManifest? = readyManifest(identity, matches.size)

        override fun countChunks(): Long = matches.size.toLong()

        override fun verifySnapshot(
            expectation: MemoryVectorSnapshotExpectation
        ): MemoryVectorSnapshotVerification {
            verifyCalls += 1
            return verification ?: MemoryVectorSnapshotVerification.Ready(
                readyManifest(identity.copy(sourceHash = expectation.sourceHash), expectation.chunks.size)
            )
        }

        override fun replaceSnapshot(snapshot: MemoryVectorSnapshot): MemoryVectorPublishResult =
            MemoryVectorPublishResult.PUBLISHED

        override fun query(request: MemoryVectorQuery): MemoryVectorQueryResult {
            queryCalls += 1
            queryLimits += request.limit
            val requestedMatches = matches.take(request.limit)
            val returnedMatches = if (request.limit <= 500) {
                requestedMatches.take(approximateReturnCap ?: requestedMatches.size)
            } else {
                requestedMatches
            }
            return queryResult ?: MemoryVectorQueryResult.Ready(
                manifest = readyManifest(request.expectedIdentity, matches.size),
                matches = returnedMatches
            )
        }

        override fun clearSnapshot() = Unit

        override fun deleteDerivedStore() = Unit

        override fun recoverFromCorruption(cause: Throwable): Boolean = false

        override fun close() = Unit
    }

    private class RecordingWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
        val families = mutableListOf<String>()

        override fun enqueueWork(family: String, delaySeconds: Long) {
            families += family
        }
    }

    private class StaticSnapshotSource(
        private val snapshot: MemoryCorpusSnapshot
    ) : MemoryCorpusSnapshotSource {
        override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
            Result.success(listOf(snapshot))

        override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> = Result.success(true)
    }

    private class SequencedSnapshotSource(
        private val snapshots: List<MemoryCorpusSnapshot>,
        private val currentGeneration: Long
    ) : MemoryCorpusSnapshotSource {
        var snapshotCalls = 0

        override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> {
            val snapshot = snapshots[snapshotCalls.coerceAtMost(snapshots.lastIndex)]
            snapshotCalls += 1
            return Result.success(listOf(snapshot))
        }

        override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> =
            Result.success(snapshots.single().generation == currentGeneration)
    }

    companion object {
        private const val CHAT_RECALL_CORPUS_KEY = "chat_recall_long_term"
        private const val DURABLE_GENERATION = 42L
        private val TARGET_VECTOR = floatArrayOf(1f, 0f, 0f, 0f)
        private val NEAR_VECTOR = floatArrayOf(1f, 0f, 0f, 0f)
        private val DIVERSE_VECTOR = floatArrayOf(0f, 1f, 0f, 0f)
        private val OTHER_VECTOR = floatArrayOf(0f, 0f, 1f, 0f)
        private val TEST_DESCRIPTOR = MemoryEmbeddingDescriptor(
            providerId = "test-provider",
            runtimeVersion = "test-runtime-v1",
            modelId = "test-model",
            modelVersion = "test-model-v1",
            modelSha256 = "1".repeat(64),
            dimension = TARGET_VECTOR.size,
            normalized = true,
            tokenizerVersion = "test-tokenizer-v1",
            tokenizerFingerprint = "2".repeat(64),
            maxInputTokens = 256,
            pooling = MemoryEmbeddingPooling.CLS,
            queryPrefix = "query: ",
            documentPrefix = ""
        )

        private fun readyManifest(
            identity: MemoryVectorIndexIdentity,
            chunkCount: Int
        ) = MemoryVectorManifest(
            identity = identity,
            expectedChunkCount = chunkCount.toLong(),
            completedAt = 1,
            state = MemoryVectorManifestState.READY
        )
    }
}
