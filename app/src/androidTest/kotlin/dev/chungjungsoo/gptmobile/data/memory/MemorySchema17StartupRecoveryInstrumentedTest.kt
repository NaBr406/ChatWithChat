package dev.chungjungsoo.gptmobile.data.memory

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingAvailability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapabilitySource
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingDescriptor
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingProvider
import dev.chungjungsoo.gptmobile.data.memory.vector.MEMORY_VECTOR_DIMENSION
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexDefaults
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorManifestState
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorQuery
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorQueryResult
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStore
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStoreFactory
import io.objectbox.BoxStore
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemorySchema17StartupRecoveryInstrumentedTest {

    @Test
    fun freshSchema17Startup_rebuildsMissingObjectBoxFromCurrentMarkdown() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val databaseName = "schema17-startup-$suffix.db"
        val memoryRoot = File(context.filesDir, "memory_store_schema17_test/$suffix")
        val vectorDirectory = File(context.noBackupFilesDir, "memory_vector_index_test/schema17-startup-$suffix")
        context.deleteDatabase(databaseName)
        memoryRoot.deleteRecursively()
        BoxStore.deleteAllFiles(vectorDirectory)
        vectorDirectory.deleteRecursively()

        val database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).build()
        var vectorStore: MemoryVectorStore? = null
        var reopenedVectorStore: MemoryVectorStore? = null
        try {
            val sqliteDatabase = database.openHelper.writableDatabase
            assertEquals(17, sqliteDatabase.version)
            sqliteDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('memory_chunk', 'memory_document')"
            ).use { cursor -> assertEquals(0, cursor.count) }

            val memoryFileStore = MemoryFileStore(MemoryFilePaths(memoryRoot), FIXED_CLOCK)
            memoryFileStore.ensureStore().getOrThrow()
            val markdown = MarkdownMemoryCodec().renderLongTerm(
                listOf(
                    MarkdownMemoryEntry(
                        id = SENTINEL_ID,
                        text = SENTINEL_TEXT,
                        type = "project_context",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        createdAt = FIXED_TIME - 1,
                        updatedAt = FIXED_TIME,
                        section = "Schema 17 Startup"
                    )
                )
            )
            memoryFileStore.replaceLongTermMemory(markdown).getOrThrow()
            val memoryBytesBefore = File(memoryRoot, MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).readBytes()
            val memoryHashBefore = memoryFileStore.currentMemoryFileHash(
                MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
            ).getOrThrow()
            assertTrue(String(memoryBytesBefore, Charsets.UTF_8).contains(SENTINEL_TEXT))
            assertFalse(vectorDirectory.exists())

            val recoveryDao = database.memoryRecoveryDao()
            val jobDao = database.memoryMaintenanceJobDao()
            val workEnqueuer = RecordingWorkEnqueuer()
            val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
            val mutationCoordinator = MemoryMutationCoordinator(
                recoveryDao = recoveryDao,
                memoryFileStore = memoryFileStore,
                maintenanceScheduler = maintenanceScheduler,
                workEnqueuer = workEnqueuer,
                clock = FIXED_CLOCK
            )
            val configuration = MemoryVectorIndexDefaults.configuration
            val snapshotter = MemoryCorpusSnapshotter(memoryFileStore, MemoryChunker())
            val embeddingProvider = DeterministicEmbeddingProvider(configuration.embeddingDescriptor)
            val capabilitySource = MemoryEmbeddingCapabilitySource {
                MemoryEmbeddingCapability.Ready(embeddingProvider, configuration)
            }
            val factory = MemoryVectorStoreFactory(context)
            val activeVectorStore = factory.createForTesting(vectorDirectory)
            vectorStore = activeVectorStore
            val bootstrapService = MemoryVectorIndexBootstrapService(
                recoveryDao = recoveryDao,
                memoryFileStore = memoryFileStore,
                mutationCoordinator = mutationCoordinator,
                targetIndexFingerprint = configuration.fingerprint()
            )
            val vectorRecoveryService = MemoryVectorIndexRecoveryService(
                recoveryDao = recoveryDao,
                snapshotSource = snapshotter,
                memoryFileStore = memoryFileStore,
                vectorStore = activeVectorStore,
                embeddingCapabilitySource = capabilitySource,
                maintenanceScheduler = maintenanceScheduler,
                clock = FIXED_CLOCK
            )
            val repairer = MemoryMaintenanceRepairer(
                maintenanceScheduler = maintenanceScheduler,
                workScheduler = workEnqueuer,
                memoryVectorIndexRecoveryService = vectorRecoveryService
            )

            var bootstrapResult: MemoryVectorIndexBootstrapResult? = null
            var objectBoxWasAbsentAfterBootstrap = false
            runMemoryStartupTasks(
                enqueueRepair = {
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.REPAIR, STARTUP_REPAIR_DELAY_SECONDS)
                },
                provision = {},
                bootstrap = {
                    bootstrapResult = bootstrapService.bootstrap()
                    objectBoxWasAbsentAfterBootstrap = !vectorDirectory.exists()
                },
                repair = { repairer.repairAndEnqueue(reopenWaitingRepair = true) }
            )

            val scheduled = bootstrapResult as MemoryVectorIndexBootstrapResult.Scheduled
            assertEquals(1L, scheduled.generation)
            assertEquals(memoryHashBefore, scheduled.sourceHash)
            assertEquals(configuration.fingerprint(), scheduled.indexFingerprint)
            assertEquals(MemoryCorpusIndexStatus.PENDING, scheduled.indexStatus)
            assertTrue(objectBoxWasAbsentAfterBootstrap)
            assertTrue(vectorDirectory.isDirectory)
            assertArrayEquals(
                memoryBytesBefore,
                File(memoryRoot, MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).readBytes()
            )
            assertEquals(
                memoryHashBefore,
                memoryFileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).getOrThrow()
            )

            val pendingJobs = jobDao.getByTypeAndStatuses(
                MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
                listOf(MemoryMaintenanceJobStatus.PENDING)
            )
            assertEquals(1, pendingJobs.size)
            assertTrue(workEnqueuer.calls.any { call -> call.family == MemoryMaintenanceJobFamily.INDEX })

            val claimedJob = checkNotNull(
                maintenanceScheduler.claimNextRunnable(
                    family = MemoryMaintenanceJobFamily.INDEX,
                    leaseOwner = "schema17-startup-test"
                )
            )
            val synchronizer = MemoryIndexSynchronizer(
                recoveryDao = recoveryDao,
                snapshotSource = snapshotter,
                memoryFileStore = memoryFileStore,
                vectorStore = activeVectorStore,
                embeddingCapabilitySource = capabilitySource,
                clock = FIXED_CLOCK
            )
            assertEquals(MemoryIndexSyncResult.Succeeded, synchronizer.synchronize(claimedJob))
            maintenanceScheduler.markSucceeded(claimedJob)

            val corpus = checkNotNull(recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY))
            assertEquals(MemoryCorpusIndexStatus.READY, corpus.indexStatus)
            assertEquals(scheduled.generation, corpus.indexedGeneration)
            assertEquals(memoryHashBefore, corpus.indexedSourceHash)
            assertEquals(configuration.fingerprint(), corpus.indexedFingerprint)
            assertEquals(
                MemoryMaintenanceJobStatus.SUCCEEDED,
                checkNotNull(jobDao.getById(claimedJob.jobId)).status
            )
            assertArrayEquals(
                memoryBytesBefore,
                File(memoryRoot, MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).readBytes()
            )
            assertEquals(
                memoryHashBefore,
                memoryFileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).getOrThrow()
            )

            val snapshot = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().single()
            val firstManifest = checkNotNull(activeVectorStore.readManifest())
            assertEquals(MemoryVectorManifestState.READY, firstManifest.state)
            assertEquals(memoryHashBefore, firstManifest.identity.sourceHash)
            assertEquals(scheduled.generation, firstManifest.identity.corpusGeneration)
            assertEquals(configuration.fingerprint(), firstManifest.identity.indexFingerprint)
            assertEquals(snapshot.chunks.size.toLong(), activeVectorStore.countChunks())
            assertTrue(snapshot.chunks.any { chunk -> chunk.text.contains(SENTINEL_TEXT) })

            activeVectorStore.close()
            vectorStore = null
            val reopenedStore = factory.createForTesting(vectorDirectory)
            reopenedVectorStore = reopenedStore
            val reopenedManifest = checkNotNull(reopenedStore.readManifest())
            assertEquals(firstManifest, reopenedManifest)
            assertEquals(snapshot.chunks.size.toLong(), reopenedStore.countChunks())
            val reopenedQuery = reopenedStore.query(
                MemoryVectorQuery(
                    expectedIdentity = reopenedManifest.identity,
                    embedding = embeddingProvider.vectorFor(SENTINEL_TEXT),
                    limit = 8
                )
            ) as MemoryVectorQueryResult.Ready
            assertTrue(reopenedQuery.matches.any { match -> match.chunk.text.contains(SENTINEL_TEXT) })
        } finally {
            runCatching { reopenedVectorStore?.close() }
            runCatching { vectorStore?.close() }
            database.close()
            runCatching { BoxStore.deleteAllFiles(vectorDirectory) }
            vectorDirectory.deleteRecursively()
            memoryRoot.deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

    private class RecordingWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
        val calls = mutableListOf<EnqueuedWork>()

        override fun enqueueWork(family: String, delaySeconds: Long) {
            calls += EnqueuedWork(family, delaySeconds)
        }
    }

    private data class EnqueuedWork(
        val family: String,
        val delaySeconds: Long
    )

    private class DeterministicEmbeddingProvider(
        override val descriptor: MemoryEmbeddingDescriptor
    ) : MemoryEmbeddingProvider {

        override suspend fun availability(): MemoryEmbeddingAvailability = MemoryEmbeddingAvailability.Available

        override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> =
            Result.success(texts.map(::vectorFor))

        override suspend fun embedQuery(text: String): Result<FloatArray> = Result.success(vectorFor(text))

        fun vectorFor(text: String): FloatArray {
            val vector = FloatArray(MEMORY_VECTOR_DIMENSION)
            val axis = (text.hashCode() and Int.MAX_VALUE) % vector.size
            vector[axis] = 1f
            return vector
        }
    }

    private companion object {
        const val CHAT_RECALL_CORPUS_KEY = "chat_recall_long_term"
        const val SENTINEL_ID = "mem_schema17_startup"
        const val SENTINEL_TEXT = "Current schema17 startup sentinel comes only from MEMORY.md."
        const val FIXED_TIME = 1_784_000_000L
        const val STARTUP_REPAIR_DELAY_SECONDS = 30L
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(FIXED_TIME), ZoneOffset.UTC)
    }
}
