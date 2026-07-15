package cn.nabr.chatwithchat.data.memory

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MemoryChatCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryPendingTurn
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingAvailability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapabilitySource
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingDescriptor
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingProvider
import cn.nabr.chatwithchat.data.memory.vector.MEMORY_VECTOR_DIMENSION
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexDefaults
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorManifestState
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorQuery
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorQueryResult
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorStore
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorStoreFactory
import io.objectbox.BoxStore
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
            val mutationRecoveryService = MemoryMutationRecoveryService(
                memoryMutationCoordinator = mutationCoordinator,
                turnBatchDao = database.memoryTurnBatchDao(),
                maintenanceScheduler = maintenanceScheduler,
                clock = FIXED_CLOCK
            )
            val repairer = MemoryMaintenanceRepairer(
                maintenanceScheduler = maintenanceScheduler,
                workScheduler = workEnqueuer,
                memoryMutationRecoveryService = mutationRecoveryService,
                memoryVectorIndexRecoveryService = vectorRecoveryService
            )

            var bootstrapResult: MemoryVectorIndexBootstrapResult? = null
            var objectBoxWasAbsentAfterBootstrap = false
            runMemoryStartupTasks(
                enqueueRepair = {
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.REPAIR, STARTUP_REPAIR_DELAY_SECONDS)
                },
                provision = {},
                recoverReceipts = { mutationRecoveryService.recoverIncomplete() },
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

    @Test
    fun schema17Startup_recoversCanonicalTargetReceiptBeforeBootstrap() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val databaseName = "schema17-receipt-startup-$suffix.db"
        val memoryRoot = File(context.filesDir, "memory_store_schema17_receipt_test/$suffix")
        context.deleteDatabase(databaseName)
        memoryRoot.deleteRecursively()

        var database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).build()
        try {
            val fileStore = MemoryFileStore(MemoryFilePaths(memoryRoot), FIXED_CLOCK)
            fileStore.ensureStore().getOrThrow()
            val initialScheduler = MemoryMaintenanceScheduler(
                database.memoryMaintenanceJobDao(),
                FIXED_CLOCK
            )
            val initialWorkEnqueuer = RecordingWorkEnqueuer()
            val initialCoordinator = MemoryMutationCoordinator(
                recoveryDao = database.memoryRecoveryDao(),
                memoryFileStore = fileStore,
                maintenanceScheduler = initialScheduler,
                workEnqueuer = initialWorkEnqueuer,
                clock = FIXED_CLOCK
            )
            val sourceJob = initialScheduler.enqueue(
                type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                idempotencyKey = "schema17-receipt-startup-source",
                payloadJson = "{}",
                jobId = "schema17-receipt-startup-source"
            )
            val canonicalBefore = fileStore.readLongTermMemory().getOrThrow()
            val target = "# ChatWithChat Memory\n\n- Canonical target survived process death"
            val prepared = initialCoordinator.prepare(
                semanticJobId = sourceJob.jobId,
                semanticBatchId = "schema17-receipt-startup-batch",
                targets = listOf(
                    MemoryMutationTarget(
                        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                        baseContent = canonicalBefore,
                        targetContent = target,
                        targetIndexFingerprint = MemoryVectorIndexDefaults.configuration.fingerprint()
                    )
                )
            )
            val preparedReceipt = prepared.receipts.single()
            val commitOutcome = fileStore.commitStagedMemoryFile(
                sourcePath = preparedReceipt.sourcePath,
                stagedTargetPath = preparedReceipt.stagedTargetPath,
                baseSourceHash = preparedReceipt.baseSourceHash,
                targetSourceHash = preparedReceipt.targetSourceHash
            ).getOrThrow()
            assertTrue(commitOutcome is MemoryFileCommitOutcome.Committed)
            fileStore.cleanupStagedTarget(preparedReceipt.stagedTargetPath).getOrThrow()

            database.close()
            database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).build()
            val restartedFileStore = MemoryFileStore(MemoryFilePaths(memoryRoot), FIXED_CLOCK)
            val recoveryDao = database.memoryRecoveryDao()
            val jobDao = database.memoryMaintenanceJobDao()
            val workEnqueuer = RecordingWorkEnqueuer()
            val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
            val mutationCoordinator = MemoryMutationCoordinator(
                recoveryDao = recoveryDao,
                memoryFileStore = restartedFileStore,
                maintenanceScheduler = maintenanceScheduler,
                workEnqueuer = workEnqueuer,
                clock = FIXED_CLOCK
            )
            val mutationRecoveryService = MemoryMutationRecoveryService(
                memoryMutationCoordinator = mutationCoordinator,
                turnBatchDao = database.memoryTurnBatchDao(),
                maintenanceScheduler = maintenanceScheduler,
                clock = FIXED_CLOCK
            )
            val bootstrapService = MemoryVectorIndexBootstrapService(
                recoveryDao = recoveryDao,
                memoryFileStore = restartedFileStore,
                mutationCoordinator = mutationCoordinator,
                targetIndexFingerprint = MemoryVectorIndexDefaults.configuration.fingerprint()
            )
            val repairer = MemoryMaintenanceRepairer(
                maintenanceScheduler = maintenanceScheduler,
                workScheduler = workEnqueuer,
                memoryMutationRecoveryService = mutationRecoveryService
            )

            val startupOrder = mutableListOf<String>()
            var startupRecovery: MemoryMutationRecoveryResult? = null
            var bootstrapResult: MemoryVectorIndexBootstrapResult? = null
            runMemoryStartupTasks(
                enqueueRepair = {
                    startupOrder += "enqueue"
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.REPAIR, STARTUP_REPAIR_DELAY_SECONDS)
                },
                provision = { startupOrder += "provision" },
                recoverReceipts = {
                    startupOrder += "recovery"
                    mutationRecoveryService.recoverIncomplete().also { startupRecovery = it }
                },
                bootstrap = {
                    startupOrder += "bootstrap"
                    bootstrapResult = bootstrapService.bootstrap()
                },
                repair = {
                    startupOrder += "repair"
                    repairer.repairAndEnqueue(reopenWaitingRepair = true)
                }
            )

            assertEquals(listOf("enqueue", "provision", "recovery", "bootstrap", "repair"), startupOrder)
            assertEquals(1, checkNotNull(startupRecovery).committedCount)
            assertEquals(0, checkNotNull(startupRecovery).conflictCount)
            assertEquals(0, checkNotNull(startupRecovery).failedCount)
            val alreadyCurrent = bootstrapResult as MemoryVectorIndexBootstrapResult.AlreadyCurrent
            assertEquals(prepared.group.generation, alreadyCurrent.generation)
            assertEquals(prepared.group.generation, recoveryDao.getLatestMutationGeneration())
            assertEquals(
                MemoryMutationState.INDEX_PENDING,
                checkNotNull(recoveryDao.getMutationReceipt(preparedReceipt.receiptId)).state
            )
            assertEquals(
                MemoryMutationState.INDEX_PENDING,
                checkNotNull(recoveryDao.getMutationGroup(prepared.group.groupId)).state
            )
            val recoveredSourceJob = checkNotNull(jobDao.getById(sourceJob.jobId))
            assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, recoveredSourceJob.status)
            assertEquals(null, recoveredSourceJob.lastError)
            assertEquals(target.trimEnd() + "\n", restartedFileStore.readLongTermMemory().getOrThrow())
        } finally {
            database.close()
            memoryRoot.deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun schema17Startup_activeClaimedReceiptSkipsBootstrapUntilLeaseRecovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val databaseName = "schema17-active-receipt-startup-$suffix.db"
        val memoryRoot = File(context.filesDir, "memory_store_schema17_active_receipt_test/$suffix")
        val sourceJobId = "schema17-active-receipt-startup-source"
        val activeClock = FIXED_CLOCK
        val expiredClock = Clock.fixed(Instant.ofEpochSecond(FIXED_TIME + SEMANTIC_LEASE_SECONDS + 1), ZoneOffset.UTC)
        context.deleteDatabase(databaseName)
        memoryRoot.deleteRecursively()

        var database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).build()
        try {
            val memoryPaths = MemoryFilePaths(memoryRoot)
            val fileStore = MemoryFileStore(memoryPaths, activeClock)
            fileStore.ensureStore().getOrThrow()
            val chatId = database.chatRoomDao().addChatRoom(
                ChatRoomV2(
                    title = "Schema 17 active recovery",
                    enabledPlatform = emptyList(),
                    createdAt = FIXED_TIME - 2,
                    updatedAt = FIXED_TIME - 1
                )
            ).toInt()
            val pendingTurn = MemoryPendingTurn(
                turnKey = "schema17-active-receipt-turn",
                chatId = chatId,
                userMessageId = 1,
                payloadJson = "{}",
                contentHash = "a".repeat(64),
                completedAt = FIXED_TIME - 1,
                claimedJobId = sourceJobId,
                createdAt = FIXED_TIME - 1,
                updatedAt = FIXED_TIME
            )
            val initialTurnBatchDao = database.memoryTurnBatchDao()
            initialTurnBatchDao.upsertCheckpoint(
                MemoryChatCheckpoint(
                    chatId = chatId,
                    lastProcessedUserMessageId = 0,
                    lastObservedUserMessageId = pendingTurn.userMessageId,
                    pendingSince = pendingTurn.completedAt,
                    lastUserActivityAt = pendingTurn.completedAt,
                    idleDueAt = FIXED_TIME + 60,
                    updatedAt = FIXED_TIME
                )
            )
            initialTurnBatchDao.upsertPendingTurn(pendingTurn)
            val initialScheduler = MemoryMaintenanceScheduler(database.memoryMaintenanceJobDao(), activeClock)
            val initialCoordinator = MemoryMutationCoordinator(
                recoveryDao = database.memoryRecoveryDao(),
                memoryFileStore = fileStore,
                maintenanceScheduler = initialScheduler,
                workEnqueuer = RecordingWorkEnqueuer(),
                clock = activeClock
            )
            val sourceJob = initialScheduler.enqueue(
                type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                idempotencyKey = sourceJobId,
                payloadJson = "{}",
                jobId = sourceJobId
            )
            val claimedSourceJob = checkNotNull(
                initialScheduler.claimNextRunnable(
                    family = MemoryMaintenanceJobFamily.SEMANTIC,
                    leaseOwner = "schema17-active-receipt-owner"
                )
            )
            initialScheduler.enqueue(
                type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                idempotencyKey = "schema17-active-receipt-waiting",
                payloadJson = "{}",
                jobId = "schema17-active-receipt-waiting"
            )
            val dailyDate = LocalDate.of(2026, 7, 14)
            val dailySourcePath = memoryPaths.relativePath(memoryPaths.dailyMemoryFile(dailyDate))
            val dailyBase = fileStore.readDailyMemory(dailyDate).getOrThrow()
            val target = dailyBase.trimEnd() + "\n\n- Claimed source survives process death\n"
            val prepared = initialCoordinator.prepare(
                semanticJobId = sourceJob.jobId,
                semanticBatchId = "schema17-active-receipt-startup-batch",
                targets = listOf(
                    MemoryMutationTarget(
                        sourcePath = dailySourcePath,
                        baseContent = dailyBase,
                        targetContent = target,
                        targetIndexFingerprint = null
                    )
                )
            )
            val preparedReceipt = prepared.receipts.single()
            assertTrue(
                fileStore.commitStagedMemoryFile(
                    sourcePath = preparedReceipt.sourcePath,
                    stagedTargetPath = preparedReceipt.stagedTargetPath,
                    baseSourceHash = preparedReceipt.baseSourceHash,
                    targetSourceHash = preparedReceipt.targetSourceHash
                ).getOrThrow() is MemoryFileCommitOutcome.Committed
            )
            fileStore.cleanupStagedTarget(preparedReceipt.stagedTargetPath).getOrThrow()

            database.close()
            database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).build()
            val restartedFileStore = MemoryFileStore(MemoryFilePaths(memoryRoot), activeClock)
            val recoveryDao = database.memoryRecoveryDao()
            val jobDao = database.memoryMaintenanceJobDao()
            val workEnqueuer = RecordingWorkEnqueuer()
            var finalizerCalls = 0
            var finalizerSucceeded: Boolean? = null
            val turnBatchDao = database.memoryTurnBatchDao()
            val countingTurnBatchDao = object : MemoryTurnBatchDao by turnBatchDao {
                override suspend fun completeClaimedBatch(jobId: String, updatedAt: Long): Boolean {
                    finalizerCalls += 1
                    return turnBatchDao.completeClaimedBatch(jobId, updatedAt).also { completed ->
                        finalizerSucceeded = completed
                    }
                }
            }
            val activeScheduler = MemoryMaintenanceScheduler(jobDao, activeClock)
            val activeCoordinator = MemoryMutationCoordinator(
                recoveryDao = recoveryDao,
                memoryFileStore = restartedFileStore,
                maintenanceScheduler = activeScheduler,
                workEnqueuer = workEnqueuer,
                clock = activeClock
            )
            val activeRecoveryService = MemoryMutationRecoveryService(
                memoryMutationCoordinator = activeCoordinator,
                turnBatchDao = countingTurnBatchDao,
                maintenanceScheduler = activeScheduler,
                clock = activeClock
            )
            val activeBootstrapService = MemoryVectorIndexBootstrapService(
                recoveryDao = recoveryDao,
                memoryFileStore = restartedFileStore,
                mutationCoordinator = activeCoordinator,
                targetIndexFingerprint = MemoryVectorIndexDefaults.configuration.fingerprint()
            )
            val activeRepairer = MemoryMaintenanceRepairer(
                maintenanceScheduler = activeScheduler,
                workScheduler = workEnqueuer,
                memoryMutationRecoveryService = activeRecoveryService,
                memoryVectorIndexBootstrapService = activeBootstrapService
            )

            val startupOrder = mutableListOf<String>()
            var startupRecovery: MemoryMutationRecoveryResult? = null
            var bootstrapCalls = 0
            runMemoryStartupTasks(
                enqueueRepair = {
                    startupOrder += "enqueue"
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.REPAIR, STARTUP_REPAIR_DELAY_SECONDS)
                },
                provision = { startupOrder += "provision" },
                recoverReceipts = {
                    startupOrder += "recovery"
                    activeRecoveryService.recoverIncomplete().also { startupRecovery = it }
                },
                bootstrap = {
                    startupOrder += "bootstrap"
                    bootstrapCalls += 1
                    activeBootstrapService.bootstrap()
                },
                repair = {
                    startupOrder += "repair"
                    activeRepairer.repairAndEnqueue(reopenWaitingRepair = true)
                }
            )

            val incompleteRecovery = checkNotNull(startupRecovery)
            assertEquals(listOf("enqueue", "provision", "recovery", "repair"), startupOrder)
            assertEquals(0, bootstrapCalls)
            assertEquals(1, incompleteRecovery.committedCount)
            assertEquals(0, incompleteRecovery.failedCount)
            assertEquals(0, incompleteRecovery.recoveredSemanticCount)
            assertTrue(incompleteRecovery.retryGenerations.isEmpty())
            assertFalse(incompleteRecovery.hasMore)
            assertEquals(1, incompleteRecovery.activeSourceJobCount)
            assertEquals(0, finalizerCalls)
            assertEquals(claimedSourceJob, jobDao.getById(sourceJob.jobId))
            assertFalse(activeScheduler.hasRunnableJob(MemoryMaintenanceJobFamily.SEMANTIC))
            assertEquals(
                MemoryMutationState.INDEXED,
                checkNotNull(recoveryDao.getMutationReceipt(preparedReceipt.receiptId)).state
            )
            assertEquals(
                MemoryMutationState.SEMANTIC_ACK_PENDING,
                checkNotNull(recoveryDao.getMutationGroup(prepared.group.groupId)).state
            )
            assertEquals(target.trimEnd() + "\n", restartedFileStore.readDailyMemory(dailyDate).getOrThrow())
            assertEquals(null, recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY))
            assertTrue(
                jobDao.getByTypeAndStatuses(
                    MemoryMaintenanceJobType.RECONCILE_MEMORY_MUTATIONS,
                    listOf(MemoryMaintenanceJobStatus.PENDING)
                ).isEmpty()
            )
            assertEquals(
                listOf(STARTUP_REPAIR_DELAY_SECONDS, SEMANTIC_LEASE_SECONDS),
                workEnqueuer.calls
                    .filter { call -> call.family == MemoryMaintenanceJobFamily.REPAIR }
                    .map(EnqueuedWork::delaySeconds)
            )

            val expiredFileStore = MemoryFileStore(MemoryFilePaths(memoryRoot), expiredClock)
            val expiredScheduler = MemoryMaintenanceScheduler(jobDao, expiredClock)
            val expiredCoordinator = MemoryMutationCoordinator(
                recoveryDao = recoveryDao,
                memoryFileStore = expiredFileStore,
                maintenanceScheduler = expiredScheduler,
                workEnqueuer = workEnqueuer,
                clock = expiredClock
            )
            val expiredRecoveryService = MemoryMutationRecoveryService(
                memoryMutationCoordinator = expiredCoordinator,
                turnBatchDao = countingTurnBatchDao,
                maintenanceScheduler = expiredScheduler,
                clock = expiredClock
            )
            val expiredBootstrapService = MemoryVectorIndexBootstrapService(
                recoveryDao = recoveryDao,
                memoryFileStore = expiredFileStore,
                mutationCoordinator = expiredCoordinator,
                targetIndexFingerprint = MemoryVectorIndexDefaults.configuration.fingerprint()
            )
            val expiredRepairer = MemoryMaintenanceRepairer(
                maintenanceScheduler = expiredScheduler,
                workScheduler = workEnqueuer,
                memoryMutationRecoveryService = expiredRecoveryService,
                memoryVectorIndexBootstrapService = expiredBootstrapService
            )

            val leaseRepair = expiredRepairer.repairAndEnqueue(reopenWaitingRepair = true)
            val recoveredSourceJob = checkNotNull(jobDao.getById(sourceJob.jobId))
            val drainedRecovery = expiredRecoveryService.recoverIncomplete(scheduleRetry = false)

            assertEquals(0, leaseRepair.resetCount)
            assertTrue(leaseRepair.schedulingSucceeded)
            assertEquals(1, finalizerCalls)
            assertEquals(true, finalizerSucceeded)
            assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, recoveredSourceJob.status)
            assertEquals(null, recoveredSourceJob.startedAt)
            assertEquals(null, recoveredSourceJob.leaseOwner)
            assertEquals(null, recoveredSourceJob.leaseExpiresAt)
            assertTrue(expiredScheduler.hasRunnableJob(MemoryMaintenanceJobFamily.SEMANTIC))
            assertEquals(
                MemoryMutationState.INDEXED,
                checkNotNull(recoveryDao.getMutationGroup(prepared.group.groupId)).state
            )
            assertTrue(turnBatchDao.getTurnsClaimedByJob(sourceJob.jobId).isEmpty())
            assertEquals(
                pendingTurn.userMessageId,
                checkNotNull(turnBatchDao.getCheckpoint(chatId)).lastProcessedUserMessageId
            )
            assertEquals(0, drainedRecovery.failedCount)
            assertEquals(0, drainedRecovery.recoveredSemanticCount)
            assertTrue(drainedRecovery.retryGenerations.isEmpty())
            assertFalse(drainedRecovery.hasMore)
            assertEquals(0, drainedRecovery.activeSourceJobCount)
            val bootstrappedCorpus = checkNotNull(recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY))
            assertEquals(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, bootstrappedCorpus.sourcePath)
            assertEquals(MemoryCorpusIndexStatus.PENDING, bootstrappedCorpus.indexStatus)
        } finally {
            database.close()
            memoryRoot.deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun schema17RepeatedStartup_doesNotReplayFinalizedClaimedConflict() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val databaseName = "schema17-terminal-startup-$suffix.db"
        val memoryRoot = File(context.filesDir, "memory_store_schema17_terminal_test/$suffix")
        val sourceJobId = "schema17-terminal-startup-source"
        context.deleteDatabase(databaseName)
        memoryRoot.deleteRecursively()

        var database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).build()
        val statusEvents = mutableListOf<MemoryMaintenanceStatusChangedEvent>()
        val eventSink = object : MemoryMaintenanceEventSink {
            override suspend fun onStatusChanged(event: MemoryMaintenanceStatusChangedEvent) {
                if (event.newJob.jobId == sourceJobId) statusEvents += event
            }
        }
        var finalizerCalls = 0
        try {
            val fileStore = MemoryFileStore(MemoryFilePaths(memoryRoot), FIXED_CLOCK)
            fileStore.ensureStore().getOrThrow()
            val initialWorkEnqueuer = RecordingWorkEnqueuer()
            val initialScheduler = MemoryMaintenanceScheduler(
                jobDao = database.memoryMaintenanceJobDao(),
                clock = FIXED_CLOCK,
                eventSink = eventSink
            )
            val initialCoordinator = MemoryMutationCoordinator(
                recoveryDao = database.memoryRecoveryDao(),
                memoryFileStore = fileStore,
                maintenanceScheduler = initialScheduler,
                workEnqueuer = initialWorkEnqueuer,
                clock = FIXED_CLOCK
            )
            val sourceJob = initialScheduler.enqueue(
                type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                idempotencyKey = "schema17-terminal-startup-source",
                payloadJson = "{}",
                jobId = sourceJobId
            )
            val claimed = checkNotNull(
                initialScheduler.claimNextRunnable(
                    family = MemoryMaintenanceJobFamily.SEMANTIC,
                    leaseOwner = "schema17-terminal-startup-owner"
                )
            )
            val prepared = initialCoordinator.prepare(
                semanticJobId = sourceJob.jobId,
                semanticBatchId = "schema17-terminal-startup-batch",
                targets = listOf(
                    MemoryMutationTarget(
                        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                        baseContent = fileStore.readLongTermMemory().getOrThrow(),
                        targetContent = "# ChatWithChat Memory\n\n- Unrecoverable staged target",
                        targetIndexFingerprint = MemoryVectorIndexDefaults.configuration.fingerprint()
                    )
                )
            )
            val preparedReceipt = prepared.receipts.single()
            assertTrue(File(memoryRoot, preparedReceipt.stagedTargetPath).delete())
            val conflict = initialCoordinator.reconcile(prepared) as MemoryMutationCommitResult.Conflict
            val terminalJob = initialScheduler.markFailedTerminal(claimed, conflict.reason)
            val terminalReceipt = checkNotNull(
                database.memoryRecoveryDao().getMutationReceipt(preparedReceipt.receiptId)
            )
            val terminalGroup = checkNotNull(
                database.memoryRecoveryDao().getMutationGroup(prepared.group.groupId)
            )
            val terminalEventCount = statusEvents.size
            assertEquals(null, terminalJob.startedAt)

            suspend fun runStartup() {
                val restartedFileStore = MemoryFileStore(MemoryFilePaths(memoryRoot), FIXED_CLOCK)
                val recoveryDao = database.memoryRecoveryDao()
                val jobDao = database.memoryMaintenanceJobDao()
                val workEnqueuer = RecordingWorkEnqueuer()
                val scheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK, eventSink)
                val coordinator = MemoryMutationCoordinator(
                    recoveryDao = recoveryDao,
                    memoryFileStore = restartedFileStore,
                    maintenanceScheduler = scheduler,
                    workEnqueuer = workEnqueuer,
                    clock = FIXED_CLOCK
                )
                val turnBatchDao = database.memoryTurnBatchDao()
                val countingTurnBatchDao = object : MemoryTurnBatchDao by turnBatchDao {
                    override suspend fun completeClaimedBatch(jobId: String, updatedAt: Long): Boolean {
                        finalizerCalls += 1
                        return turnBatchDao.completeClaimedBatch(jobId, updatedAt)
                    }
                }
                val recoveryService = MemoryMutationRecoveryService(
                    memoryMutationCoordinator = coordinator,
                    turnBatchDao = countingTurnBatchDao,
                    maintenanceScheduler = scheduler,
                    clock = FIXED_CLOCK
                )
                val bootstrapService = MemoryVectorIndexBootstrapService(
                    recoveryDao = recoveryDao,
                    memoryFileStore = restartedFileStore,
                    mutationCoordinator = coordinator,
                    targetIndexFingerprint = MemoryVectorIndexDefaults.configuration.fingerprint()
                )
                val repairer = MemoryMaintenanceRepairer(
                    maintenanceScheduler = scheduler,
                    workScheduler = workEnqueuer,
                    memoryMutationRecoveryService = recoveryService
                )
                runMemoryStartupTasks(
                    enqueueRepair = {
                        workEnqueuer.enqueueWork(
                            MemoryMaintenanceJobFamily.REPAIR,
                            STARTUP_REPAIR_DELAY_SECONDS
                        )
                    },
                    provision = {},
                    recoverReceipts = { recoveryService.recoverIncomplete() },
                    bootstrap = { bootstrapService.bootstrap() },
                    repair = { repairer.repairAndEnqueue(reopenWaitingRepair = true) }
                )
            }

            database.close()
            database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).build()
            runStartup()
            val jobAfterFirstStartup = checkNotNull(database.memoryMaintenanceJobDao().getById(sourceJob.jobId))
            val receiptAfterFirstStartup = checkNotNull(
                database.memoryRecoveryDao().getMutationReceipt(preparedReceipt.receiptId)
            )
            val groupAfterFirstStartup = checkNotNull(
                database.memoryRecoveryDao().getMutationGroup(prepared.group.groupId)
            )
            assertEquals(terminalJob, jobAfterFirstStartup)
            assertEquals(terminalReceipt, receiptAfterFirstStartup)
            assertEquals(terminalGroup, groupAfterFirstStartup)
            assertEquals(terminalEventCount, statusEvents.size)
            assertEquals(0, finalizerCalls)

            database.close()
            database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).build()
            runStartup()
            assertEquals(terminalJob, database.memoryMaintenanceJobDao().getById(sourceJob.jobId))
            assertEquals(
                terminalReceipt,
                database.memoryRecoveryDao().getMutationReceipt(preparedReceipt.receiptId)
            )
            assertEquals(
                terminalGroup,
                database.memoryRecoveryDao().getMutationGroup(prepared.group.groupId)
            )
            assertEquals(terminalEventCount, statusEvents.size)
            assertEquals(0, finalizerCalls)
        } finally {
            database.close()
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
        const val SEMANTIC_LEASE_SECONDS = 30 * 60L
        const val STARTUP_REPAIR_DELAY_SECONDS = 30L
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(FIXED_TIME), ZoneOffset.UTC)
    }
}
