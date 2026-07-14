package dev.chungjungsoo.gptmobile.data.memory.vector

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.memory.HybridMemoryRetriever
import dev.chungjungsoo.gptmobile.data.memory.MarkdownLexicalRetriever
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpus
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpusChunk
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpusSnapshot
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpusSnapshotSource
import dev.chungjungsoo.gptmobile.data.memory.MemoryFilePaths
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetrievalRequest
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetrievalStrategy
import dev.chungjungsoo.gptmobile.data.memory.MemoryVectorRecallRepairTrigger
import dev.chungjungsoo.gptmobile.data.memory.MemoryVectorRecallStateSource
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingAvailability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapabilitySource
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingDescriptor
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingPooling
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingProvider
import io.objectbox.BoxStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObjectBoxMemoryVectorStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var factory: MemoryVectorStoreFactory
    private val openedStores = mutableListOf<MemoryVectorStore>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(
            context.noBackupFilesDir,
            "memory_vector_index_test/store-${System.nanoTime()}"
        )
        BoxStore.deleteAllFiles(directory)
        factory = MemoryVectorStoreFactory(context)
    }

    @After
    fun tearDown() {
        openedStores.asReversed().forEach { store -> runCatching { store.close() } }
        runCatching { BoxStore.deleteAllFiles(directory) }
        directory.deleteRecursively()
    }

    @Test
    fun snapshotReplace_queryUpdateDeleteAndReopen_workOnDevice() {
        val firstIdentity = identity(generation = 1, sourceHash = hash('a'))
        val firstSnapshot = snapshot(
            identity = firstIdentity,
            chunks = listOf(
                embeddedChunk("target", "first target", axis = 0, hashCharacter = '1'),
                embeddedChunk("removed", "removed later", axis = 1, hashCharacter = '2')
            )
        )
        val firstStore = openStore()
        assertEquals(MemoryVectorPublishResult.PUBLISHED, firstStore.replaceSnapshot(firstSnapshot))

        val firstResult = firstStore.query(query(firstIdentity, axis = 0)) as MemoryVectorQueryResult.Ready
        assertEquals("target", firstResult.matches.first().chunk.chunkId)
        assertEquals(2L, firstStore.countChunks())

        val secondIdentity = identity(generation = 2, sourceHash = hash('b'))
        val secondSnapshot = snapshot(
            identity = secondIdentity,
            chunks = listOf(
                embeddedChunk("target", "updated target", axis = 0, hashCharacter = '3')
            )
        )
        assertEquals(MemoryVectorPublishResult.PUBLISHED, firstStore.replaceSnapshot(secondSnapshot))

        val staleResult = firstStore.query(query(firstIdentity, axis = 0)) as MemoryVectorQueryResult.Unavailable
        assertEquals(MemoryVectorUnavailableReason.STALE_MANIFEST, staleResult.reason)
        val secondResult = firstStore.query(query(secondIdentity, axis = 0)) as MemoryVectorQueryResult.Ready
        assertEquals("updated target", secondResult.matches.single().chunk.text)
        assertEquals(1L, firstStore.countChunks())

        firstStore.close()
        val reopenedStore = openStore()
        assertEquals(secondSnapshot.manifest, reopenedStore.readManifest())
        val reopenedResult = reopenedStore.query(query(secondIdentity, axis = 0)) as MemoryVectorQueryResult.Ready
        assertEquals("target", reopenedResult.matches.single().chunk.chunkId)

        reopenedStore.clearSnapshot()
        assertNull(reopenedStore.readManifest())
        assertEquals(0L, reopenedStore.countChunks())
    }

    @Test
    fun wrongVectorDimension_failsBeforeMutatingThePublishedSnapshot() {
        val identity = identity(generation = 1, sourceHash = hash('a'))
        val original = snapshot(
            identity = identity,
            chunks = listOf(embeddedChunk("target", "valid", axis = 0, hashCharacter = '1'))
        )
        val store = openStore()
        store.replaceSnapshot(original)

        val invalidSnapshot = original.copy(
            chunks = listOf(
                MemoryEmbeddedChunk(
                    chunk = original.chunks.single().chunk.copy(text = "must not publish"),
                    embedding = FloatArray(MEMORY_VECTOR_DIMENSION - 1)
                )
            )
        )
        val replaceFailure = assertThrows(IllegalArgumentException::class.java) {
            store.replaceSnapshot(invalidSnapshot)
        }
        assertTrue(replaceFailure.message.orEmpty().contains("exactly $MEMORY_VECTOR_DIMENSION"))

        val queryFailure = assertThrows(IllegalArgumentException::class.java) {
            store.query(
                MemoryVectorQuery(
                    expectedIdentity = identity,
                    embedding = FloatArray(MEMORY_VECTOR_DIMENSION - 1),
                    limit = 1
                )
            )
        }
        assertTrue(queryFailure.message.orEmpty().contains("exactly $MEMORY_VECTOR_DIMENSION"))
        assertEquals(original.manifest, store.readManifest())
        assertEquals(1L, store.countChunks())
    }

    @Test
    fun queryBeyondLegacyCandidateCap_isBoundedByPublishedSnapshot() = runBlocking {
        val identity = identity(generation = 1, sourceHash = hash('a'))
        val chunks = (0 until 601).map { index ->
            embeddedChunk(
                chunkId = "candidate-$index",
                text = if (index < 600) {
                    "Repeated historical memory."
                } else {
                    "Unique memory after historical duplicates."
                },
                axis = if (index < 600) 0 else 1,
                hashCharacter = (index % 10).digitToChar()
            ).let { chunk ->
                if (index == 0) {
                    chunk.copy(
                        embedding = embedding(axis = 0).apply { this[0] = 0.9995f }
                    )
                } else {
                    chunk
                }
            }
        }
        val store = openStore()
        assertEquals(
            MemoryVectorPublishResult.PUBLISHED,
            store.replaceSnapshot(snapshot(identity = identity, chunks = chunks))
        )

        val result = store.query(
            MemoryVectorQuery(
                expectedIdentity = identity,
                embedding = embedding(axis = 0),
                limit = chunks.size + 100
            )
        ) as MemoryVectorQueryResult.Ready

        assertEquals(chunks.size, result.matches.size)
        assertEquals(
            chunks.map { chunk -> chunk.chunk.chunkId }.toSet(),
            result.matches.map { match -> match.chunk.chunkId }.toSet()
        )
        assertEquals(
            result.matches
                .sortedWith(
                    compareBy<MemoryVectorMatch>(MemoryVectorMatch::cosineDistance)
                        .thenBy { match -> match.chunk.chunkId }
                )
                .map { match -> match.chunk.chunkId },
            result.matches.map { match -> match.chunk.chunkId }
        )
        assertEquals(
            0f,
            result.matches.single { match -> match.chunk.chunkId == "candidate-0" }.cosineDistance,
            1e-6f
        )
        assertEquals(
            1f,
            result.matches.single { match -> match.chunk.chunkId == "candidate-600" }.cosineDistance,
            1e-6f
        )

        val limitedResult = store.query(
            MemoryVectorQuery(
                expectedIdentity = identity,
                embedding = embedding(axis = 0),
                limit = 501
            )
        ) as MemoryVectorQueryResult.Ready
        assertEquals(501, limitedResult.matches.size)
        assertFalse(limitedResult.matches.any { match -> match.chunk.chunkId == "candidate-600" })

        assertThrows(IllegalArgumentException::class.java) {
            store.query(
                MemoryVectorQuery(
                    expectedIdentity = identity,
                    embedding = embedding(axis = 0),
                    limit = 0
                )
            )
        }

        val retrieved = hybridRetriever(identity, chunks, store).retrieve(vectorOnlyRequest()).getOrThrow()

        assertEquals(
            listOf("entry-candidate-0", "entry-candidate-600"),
            retrieved.map { result -> result.entryId }
        )
    }

    @Test
    fun fullMatchingSnapshotBelowHnswLimit_usesExactScanAndKeepsTailUnique() = runBlocking {
        val identity = identity(generation = 1, sourceHash = hash('a'))
        val chunks = (0 until 401).map { index ->
            embeddedChunk(
                chunkId = "candidate-$index",
                text = if (index < 400) {
                    "Repeated historical memory."
                } else {
                    "Unique memory after historical duplicates."
                },
                axis = if (index < 400) 0 else 1,
                hashCharacter = (index % 10).digitToChar()
            )
        }
        val store = openStore()
        assertEquals(
            MemoryVectorPublishResult.PUBLISHED,
            store.replaceSnapshot(snapshot(identity = identity, chunks = chunks))
        )
        assertFalse(shouldUseExactCosineScan(effectiveLimit = 400, matchingChunkCount = 401))
        assertTrue(shouldUseExactCosineScan(effectiveLimit = 401, matchingChunkCount = 401))

        val result = store.query(
            MemoryVectorQuery(
                expectedIdentity = identity,
                embedding = embedding(axis = 0),
                limit = chunks.size
            )
        ) as MemoryVectorQueryResult.Ready

        assertEquals(chunks.size, result.matches.size)
        assertEquals(
            result.matches
                .sortedWith(
                    compareBy<MemoryVectorMatch>(MemoryVectorMatch::cosineDistance)
                        .thenBy { match -> match.chunk.chunkId }
                )
                .map { match -> match.chunk.chunkId },
            result.matches.map { match -> match.chunk.chunkId }
        )
        assertEquals("candidate-0", result.matches.first().chunk.chunkId)
        assertEquals(0f, result.matches.first().cosineDistance, 1e-6f)
        assertEquals("candidate-400", result.matches.last().chunk.chunkId)
        assertEquals(1f, result.matches.last().cosineDistance, 1e-6f)

        val retrieved = hybridRetriever(identity, chunks, store).retrieve(vectorOnlyRequest()).getOrThrow()
        assertEquals(
            listOf("entry-candidate-0", "entry-candidate-400"),
            retrieved.map { item -> item.entryId }
        )
    }

    @Test
    fun sourceModelTokenizerAndChunkerMismatch_failClosed() {
        val identity = identity(generation = 1, sourceHash = hash('a'))
        val store = openStore()
        store.replaceSnapshot(
            snapshot(
                identity = identity,
                chunks = listOf(embeddedChunk("target", "valid", axis = 0, hashCharacter = '1'))
            )
        )
        val mismatchedIdentities = listOf(
            identity.copy(sourceHash = hash('b')),
            configuration(DESCRIPTOR.copy(modelVersion = "test-model-v2"))
                .identity(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, identity.sourceHash, identity.corpusGeneration),
            configuration(DESCRIPTOR.copy(tokenizerFingerprint = hash('c')))
                .identity(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, identity.sourceHash, identity.corpusGeneration),
            configuration(DESCRIPTOR, chunkerVersion = "memory-chunker-v2")
                .identity(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, identity.sourceHash, identity.corpusGeneration),
            identity.copy(indexFingerprint = hash('0'))
        )

        mismatchedIdentities.forEach { mismatchedIdentity ->
            val result = store.query(query(mismatchedIdentity, axis = 0)) as MemoryVectorQueryResult.Unavailable
            assertEquals(MemoryVectorUnavailableReason.STALE_MANIFEST, result.reason)
        }

        val incompatibleDimension = identity.copy(
            embeddingDescriptor = DESCRIPTOR.copy(dimension = MEMORY_VECTOR_DIMENSION - 1)
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.query(query(incompatibleDimension, axis = 0))
        }
        assertEquals(identity, store.readManifest()?.identity)
    }

    @Test
    fun staleNonMatchingChunk_rejectsTheWholeReadyManifestBeforeHnswQuery() {
        val identity = identity(generation = 1, sourceHash = hash('a'))
        val store = openStore()
        store.replaceSnapshot(
            snapshot(
                identity = identity,
                chunks = listOf(
                    embeddedChunk("target", "valid nearest target", axis = 0, hashCharacter = '1'),
                    embeddedChunk("stale", "must invalidate the generation", axis = 1, hashCharacter = '2')
                )
            )
        )
        store.close()
        MyObjectBox.builder()
            .androidContext(context)
            .directory(directory)
            .build()
            .use { rawStore ->
                val chunkBox = rawStore.boxFor(MemoryVectorChunkEntity::class.java)
                val stale = checkNotNull(
                    chunkBox.query(MemoryVectorChunkEntity_.chunkId.equal("stale"))
                        .build()
                        .use { query -> query.findUnique() }
                )
                stale.sourceHash = hash('b')
                chunkBox.put(stale)
            }

        val reopenedStore = openStore()
        val result = reopenedStore.query(query(identity, axis = 0)) as MemoryVectorQueryResult.Unavailable

        assertEquals(MemoryVectorUnavailableReason.CHUNK_COUNT_MISMATCH, result.reason)
    }

    @Test
    fun snapshotVerification_preservesStaleManifestAndRecoversCorruptIdentityChunks() {
        val identity = identity(generation = 2, sourceHash = hash('b'))
        val snapshot = snapshot(
            identity = identity,
            chunks = listOf(
                embeddedChunk("target", "valid target", axis = 0, hashCharacter = '1'),
                embeddedChunk("corrupt", "will be corrupted", axis = 1, hashCharacter = '2')
            )
        )
        val store = openStore()
        store.replaceSnapshot(snapshot)

        val staleVerification = store.verifySnapshot(
            expectation(
                identity = identity(generation = 1, sourceHash = hash('a')),
                chunks = snapshot.chunks.map(MemoryEmbeddedChunk::chunk)
            )
        )
        assertTrue(staleVerification is MemoryVectorSnapshotVerification.Stale)
        assertEquals(snapshot.manifest, store.readManifest())
        assertEquals(2L, store.countChunks())

        store.close()
        MyObjectBox.builder()
            .androidContext(context)
            .directory(directory)
            .build()
            .use { rawStore ->
                val chunkBox = rawStore.boxFor(MemoryVectorChunkEntity::class.java)
                val corrupt = checkNotNull(
                    chunkBox.query(MemoryVectorChunkEntity_.chunkId.equal("corrupt"))
                        .build()
                        .use { query -> query.findUnique() }
                )
                corrupt.text = "tampered without updating contentHash"
                chunkBox.put(corrupt)
            }

        val reopenedStore = openStore()
        assertEquals(
            MemoryVectorSnapshotVerification.RecoveredCorruption,
            reopenedStore.verifySnapshot(
                expectation(
                    identity = identity,
                    chunks = snapshot.chunks.map(MemoryEmbeddedChunk::chunk)
                )
            )
        )
        assertNull(reopenedStore.readManifest())
        assertEquals(0L, reopenedStore.countChunks())
    }

    @Test
    fun failedManifestPublish_rollsBackChunksAndManifestTogether() {
        val firstIdentity = identity(generation = 1, sourceHash = hash('a'))
        val firstSnapshot = snapshot(
            identity = firstIdentity,
            chunks = listOf(embeddedChunk("old", "old snapshot", axis = 0, hashCharacter = '1'))
        )
        val initialStore = openStore()
        initialStore.replaceSnapshot(firstSnapshot)
        initialStore.close()

        val failedStore = openStore(beforeManifestPublished = { error("injected publish failure") })
        val replacementIdentity = identity(generation = 2, sourceHash = hash('b'))
        val replacement = snapshot(
            identity = replacementIdentity,
            chunks = listOf(embeddedChunk("new", "new snapshot", axis = 1, hashCharacter = '2'))
        )
        assertThrows(IllegalStateException::class.java) {
            failedStore.replaceSnapshot(replacement)
        }

        assertEquals(firstSnapshot.manifest, failedStore.readManifest())
        assertEquals(1L, failedStore.countChunks())
        val result = failedStore.query(query(firstIdentity, axis = 0)) as MemoryVectorQueryResult.Ready
        assertEquals("old", result.matches.single().chunk.chunkId)
    }

    @Test
    fun sameReadyIdentity_isIdempotentWithoutRepublishing() {
        var publishCount = 0
        val store = openStore(beforeManifestPublished = { publishCount += 1 })
        val identity = identity(generation = 1, sourceHash = hash('a'))
        val snapshot = snapshot(
            identity = identity,
            chunks = listOf(embeddedChunk("target", "stable snapshot", axis = 0, hashCharacter = '1'))
        )

        assertEquals(MemoryVectorPublishResult.PUBLISHED, store.replaceSnapshot(snapshot))
        assertEquals(MemoryVectorPublishResult.ALREADY_READY, store.replaceSnapshot(snapshot))

        assertEquals(1, publishCount)
        assertEquals(snapshot.manifest, store.readManifest())
        assertEquals(1L, store.countChunks())
    }

    @Test
    fun olderGeneration_afterNewerPublication_isRejectedWithoutOverwriting() {
        val store = openStore()
        val newerIdentity = identity(generation = 2, sourceHash = hash('b'))
        val newerSnapshot = snapshot(
            identity = newerIdentity,
            chunks = listOf(embeddedChunk("new", "newer snapshot", axis = 1, hashCharacter = '2'))
        )
        assertEquals(MemoryVectorPublishResult.PUBLISHED, store.replaceSnapshot(newerSnapshot))

        val olderIdentity = identity(generation = 1, sourceHash = hash('a'))
        val olderSnapshot = snapshot(
            identity = olderIdentity,
            chunks = listOf(embeddedChunk("old", "older snapshot", axis = 0, hashCharacter = '1'))
        )
        assertEquals(MemoryVectorPublishResult.SUPERSEDED, store.replaceSnapshot(olderSnapshot))

        assertEquals(newerSnapshot.manifest, store.readManifest())
        assertEquals(1L, store.countChunks())
        val result = store.query(query(newerIdentity, axis = 1)) as MemoryVectorQueryResult.Ready
        assertEquals("new", result.matches.single().chunk.chunkId)
    }

    @Test
    fun sameGenerationWithDifferentIdentity_isAnExplicitConflict() {
        val store = openStore()
        val publishedIdentity = identity(generation = 1, sourceHash = hash('a'))
        val publishedSnapshot = snapshot(
            identity = publishedIdentity,
            chunks = listOf(embeddedChunk("published", "published snapshot", axis = 0, hashCharacter = '1'))
        )
        store.replaceSnapshot(publishedSnapshot)
        val conflictingSnapshot = snapshot(
            identity = identity(generation = 1, sourceHash = hash('b')),
            chunks = listOf(embeddedChunk("conflict", "conflicting snapshot", axis = 1, hashCharacter = '2'))
        )

        assertThrows(MemoryVectorStoreConflictException::class.java) {
            store.replaceSnapshot(conflictingSnapshot)
        }

        assertEquals(publishedSnapshot.manifest, store.readManifest())
        assertEquals(1L, store.countChunks())
        val result = store.query(query(publishedIdentity, axis = 0)) as MemoryVectorQueryResult.Ready
        assertEquals("published", result.matches.single().chunk.chunkId)
    }

    @Test
    fun corruptionRecovery_deletesOnlyTheDerivedStoreAndReopensEmpty() {
        val identity = identity(generation = 1, sourceHash = hash('a'))
        val store = openStore()
        store.replaceSnapshot(
            snapshot(
                identity = identity,
                chunks = listOf(embeddedChunk("target", "valid", axis = 0, hashCharacter = '1'))
            )
        )
        val outsideSentinel = File(context.filesDir, "vector-store-sentinel-${System.nanoTime()}")
        outsideSentinel.writeText("outside derived vector store")

        try {
            assertFalse(store.recoverFromCorruption(IllegalArgumentException("not corruption")))
            assertEquals(1L, store.countChunks())

            assertTrue(store.recoverFromCorruption(MemoryVectorStoreCorruptionException("test corruption")))
            assertTrue(outsideSentinel.exists())
            assertNull(store.readManifest())
            assertEquals(0L, store.countChunks())

            store.deleteDerivedStore()
            assertTrue(outsideSentinel.exists())
        } finally {
            outsideSentinel.delete()
        }
    }

    @Test
    fun emptyReadySnapshot_queriesAsAnEmptyReadyResult() {
        val identity = identity(generation = 1, sourceHash = hash('a'))
        val store = openStore()
        store.replaceSnapshot(snapshot(identity, emptyList()))

        val result = store.query(query(identity, axis = 0)) as MemoryVectorQueryResult.Ready
        assertTrue(result.matches.isEmpty())
        assertEquals(0L, store.countChunks())
    }

    private fun openStore(
        beforeManifestPublished: () -> Unit = {}
    ): MemoryVectorStore = factory.createForTesting(
        directory = directory,
        beforeManifestPublished = beforeManifestPublished
    ).also(openedStores::add)

    private fun hybridRetriever(
        identity: MemoryVectorIndexIdentity,
        chunks: List<MemoryEmbeddedChunk>,
        store: MemoryVectorStore
    ): HybridMemoryRetriever {
        val corpusSnapshot = MemoryCorpusSnapshot(
            corpus = identity.corpus,
            sourcePath = identity.sourcePath,
            sourceHash = identity.sourceHash,
            generation = identity.corpusGeneration,
            chunks = chunks.map(MemoryEmbeddedChunk::chunk)
        )
        val snapshotSource = object : MemoryCorpusSnapshotSource {
            override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
                Result.success(listOf(corpusSnapshot))

            override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> =
                Result.success(snapshots.single() == corpusSnapshot)
        }
        val provider = object : MemoryEmbeddingProvider {
            override val descriptor: MemoryEmbeddingDescriptor = DESCRIPTOR

            override suspend fun availability(): MemoryEmbeddingAvailability = MemoryEmbeddingAvailability.Available

            override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> =
                Result.success(texts.map { embedding(axis = 0) })

            override suspend fun embedQuery(text: String): Result<FloatArray> =
                Result.success(embedding(axis = 0))
        }
        return HybridMemoryRetriever(
            snapshotSource = snapshotSource,
            lexicalRetriever = MarkdownLexicalRetriever(snapshotSource),
            vectorStore = store,
            embeddingCapabilitySource = MemoryEmbeddingCapabilitySource {
                MemoryEmbeddingCapability.Ready(provider, INDEX_CONFIGURATION)
            },
            vectorRecallStateSource = object : MemoryVectorRecallStateSource {
                override suspend fun expectedIdentity(
                    snapshot: MemoryCorpusSnapshot,
                    configuration: MemoryVectorIndexConfiguration
                ): MemoryVectorIndexIdentity? = identity
            },
            repairTrigger = object : MemoryVectorRecallRepairTrigger {
                override fun requestRepair() = Unit
            }
        )
    }

    private fun vectorOnlyRequest(): MemoryRetrievalRequest = MemoryRetrievalRequest(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        query = "unmatched vector query",
        limit = 2,
        candidateLimit = 2,
        tokenBudget = 1_000,
        strategy = MemoryRetrievalStrategy.VECTOR
    )

    private fun identity(
        generation: Long,
        sourceHash: String
    ): MemoryVectorIndexIdentity = INDEX_CONFIGURATION.identity(
        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
        sourceHash = sourceHash,
        corpusGeneration = generation
    )

    private fun expectation(
        identity: MemoryVectorIndexIdentity,
        chunks: List<MemoryCorpusChunk>
    ): MemoryVectorSnapshotExpectation =
        MemoryVectorSnapshotExpectation(
            corpus = identity.corpus,
            sourcePath = identity.sourcePath,
            sourceHash = identity.sourceHash,
            corpusGeneration = identity.corpusGeneration,
            indexFingerprint = identity.indexFingerprint,
            chunks = chunks
        )

    private fun configuration(
        descriptor: MemoryEmbeddingDescriptor,
        chunkerVersion: String = "memory-chunker-v1"
    ): MemoryVectorIndexConfiguration = MemoryVectorIndexConfiguration(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        indexSchemaVersion = MEMORY_VECTOR_INDEX_SCHEMA_VERSION,
        chunkerVersion = chunkerVersion,
        maxChunkChars = 1200,
        chunkOverlapChars = 0,
        markdownCodecVersion = "markdown-memory-v1",
        embeddingDescriptor = descriptor,
        queryTextNormalization = "trim-collapse-whitespace",
        documentTextNormalization = "trim-collapse-whitespace",
        distanceMetric = MemoryVectorDistanceMetric.COSINE
    )

    private fun snapshot(
        identity: MemoryVectorIndexIdentity,
        chunks: List<MemoryEmbeddedChunk>
    ): MemoryVectorSnapshot = MemoryVectorSnapshot(
        manifest = MemoryVectorManifest(
            identity = identity,
            expectedChunkCount = chunks.size.toLong(),
            completedAt = 1_000L + identity.corpusGeneration,
            state = MemoryVectorManifestState.READY
        ),
        chunks = chunks
    )

    private fun embeddedChunk(
        chunkId: String,
        text: String,
        axis: Int,
        hashCharacter: Char
    ): MemoryEmbeddedChunk = MemoryEmbeddedChunk(
        chunk = MemoryCorpusChunk(
            chunkId = chunkId,
            entryId = "entry-$chunkId",
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            chunkIndex = axis,
            heading = "Test",
            text = text,
            type = "fact",
            sensitivity = null,
            source = "instrumentation",
            chatId = 7,
            createdAt = 100,
            updatedAt = 200,
            contentHash = hash(hashCharacter)
        ),
        embedding = embedding(axis)
    )

    private fun query(
        identity: MemoryVectorIndexIdentity,
        axis: Int
    ): MemoryVectorQuery = MemoryVectorQuery(
        expectedIdentity = identity,
        embedding = embedding(axis),
        limit = 4
    )

    private fun embedding(axis: Int): FloatArray =
        FloatArray(MEMORY_VECTOR_DIMENSION).apply { this[axis] = 1f }

    private fun hash(character: Char): String = character.toString().repeat(64)

    private companion object {
        val DESCRIPTOR = MemoryEmbeddingDescriptor(
            providerId = "instrumentation-fake",
            runtimeVersion = "test-runtime-v1",
            modelId = "test-model",
            modelVersion = "test-model-v1",
            modelSha256 = "d".repeat(64),
            dimension = MEMORY_VECTOR_DIMENSION,
            normalized = true,
            tokenizerVersion = "test-tokenizer-v1",
            tokenizerFingerprint = "e".repeat(64),
            maxInputTokens = 256,
            pooling = MemoryEmbeddingPooling.CLS,
            queryPrefix = "query: ",
            documentPrefix = ""
        )
        val INDEX_CONFIGURATION = MemoryVectorIndexConfiguration(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            indexSchemaVersion = MEMORY_VECTOR_INDEX_SCHEMA_VERSION,
            chunkerVersion = "memory-chunker-v1",
            maxChunkChars = 1200,
            chunkOverlapChars = 0,
            markdownCodecVersion = "markdown-memory-v1",
            embeddingDescriptor = DESCRIPTOR,
            queryTextNormalization = "trim-collapse-whitespace",
            documentTextNormalization = "trim-collapse-whitespace",
            distanceMetric = MemoryVectorDistanceMetric.COSINE
        )
    }
}
