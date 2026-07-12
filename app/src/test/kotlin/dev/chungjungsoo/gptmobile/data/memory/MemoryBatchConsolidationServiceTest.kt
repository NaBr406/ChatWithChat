package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBatchConsolidationServiceTest {
    @Test
    fun `valid five turn batch calls consolidation once and advances checkpoint`() = runBlocking {
        val proposal = MemoryBatchConsolidationProposal(
            operations = listOf(
                operation(
                    destination = MemoryBatchDestination.DAILY,
                    text = "The user is testing daily batch consolidation."
                ),
                operation(
                    destination = MemoryBatchDestination.LONG_TERM,
                    text = "The user prefers durable batch-based memory updates."
                )
            )
        )
        val fixture = fixture(proposal)
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertTrue(fixture.fileStore.readDailyMemory().getOrThrow().contains("testing daily batch consolidation"))
        assertTrue(fixture.fileStore.readLongTermMemory().getOrThrow().contains("durable batch-based memory updates"))
        assertTrue(fixture.turnDao.getPendingTurnsForChat(CHAT_ID).isEmpty())
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.jobDao.jobs.single().status)
        assertTrue(fixture.indexDao.chunks.isNotEmpty())
        assertEquals(MemoryActivityStatus.SUCCEEDED, fixture.activityLogger.lastStatus)
    }

    @Test
    fun `four turns consolidate once after persisted idle deadline`() = runBlocking {
        val fixture = fixture(MemoryBatchConsolidationProposal())
        fixture.createTurns(4)

        assertEquals(0, fixture.intelligence.consolidateCalls)
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertEquals(1, fixture.turnBatchScheduler.promoteDueIdleBatches(now = 1_804L))
        fixture.service.process(fixture.jobDao.jobs.single())

        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertEquals(4, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
    }

    @Test
    fun `ten turns run as two sequential consolidation calls`() = runBlocking {
        val fixture = fixture(MemoryBatchConsolidationProposal())
        fixture.createTurns(10)
        val firstJob = fixture.jobDao.jobs.single()

        fixture.service.process(firstJob)
        val secondJob = fixture.jobDao.jobs.single { it.jobId != firstJob.jobId }
        fixture.service.process(secondJob)

        assertEquals(2, fixture.intelligence.consolidateCalls)
        assertEquals(2, fixture.jobDao.jobs.size)
        assertTrue(fixture.jobDao.jobs.all { it.status == MemoryMaintenanceJobStatus.SUCCEEDED })
        assertTrue(fixture.turnDao.getPendingTurnsForChat(CHAT_ID).isEmpty())
        assertEquals(10, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
    }

    @Test
    fun `successful job replay performs zero additional calls`() = runBlocking {
        val fixture = fixture(MemoryBatchConsolidationProposal())
        val job = fixture.createFiveTurnBatch()
        fixture.service.process(job)

        val replay = fixture.service.process(fixture.jobDao.jobs.single())

        assertEquals(MemoryBatchProcessResult.STATUS_DUPLICATE, replay.status)
        assertEquals(1, fixture.intelligence.consolidateCalls)
    }

    @Test
    fun `recovery after markdown commit does not append the deterministic entry twice`() = runBlocking {
        val memoryText = "The user prefers crash-safe deterministic memory writes."
        val fixture = fixture(
            MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.DAILY,
                        text = memoryText
                    )
                )
            )
        )
        val job = fixture.createFiveTurnBatch()
        val committedEntryId = "day_${sha256("${job.idempotencyKey}|0|${MemoryBatchDestination.DAILY}").take(24)}"
        fixture.fileStore.appendDailyNote(
            MarkdownMemoryCodec().renderDailyAppend(
                listOf(
                    MarkdownMemoryEntry(
                        id = committedEntryId,
                        text = memoryText,
                        type = "stable_profile",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        chatId = CHAT_ID,
                        createdAt = 1_000L,
                        updatedAt = 1_000L
                    )
                )
            )
        ).getOrThrow()

        val result = fixture.service.process(job)
        val markdown = fixture.fileStore.readDailyMemory().getOrThrow()

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, markdown.split(memoryText).size - 1)
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
    }

    @Test
    fun `empty proposal advances checkpoint without writing memory`() = runBlocking {
        val fixture = fixture(MemoryBatchConsolidationProposal())
        val beforeLongTerm = fixture.fileStore.readLongTermMemory().getOrThrow()
        val beforeDaily = fixture.fileStore.readDailyMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(0, result.dailyWriteCount + result.longTermWriteCount)
        assertEquals(beforeLongTerm, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(beforeDaily, fixture.fileStore.readDailyMemory().getOrThrow())
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
    }

    @Test
    fun `invented replace target writes nothing and keeps claimed checkpoint pending`() = runBlocking {
        val fixture = fixture(
            MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = "invented-id",
                        text = "Invalid replacement"
                    )
                )
            )
        )
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(0, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(5, fixture.turnDao.getTurnsClaimedByJob(job.jobId).size)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, fixture.jobDao.jobs.single().status)
        assertEquals(MemoryActivityStatus.FAILED, fixture.activityLogger.lastStatus)
    }

    @Test
    fun `invalid json boundary writes nothing and does not advance checkpoint`() = runBlocking {
        val fixture = fixture(proposal = null)
        val beforeLongTerm = fixture.fileStore.readLongTermMemory().getOrThrow()
        val beforeDaily = fixture.fileStore.readDailyMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertEquals(beforeLongTerm, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(beforeDaily, fixture.fileStore.readDailyMemory().getOrThrow())
        assertEquals(0, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(5, fixture.turnDao.getTurnsClaimedByJob(job.jobId).size)
        assertEquals(MemoryActivityStatus.FAILED, fixture.activityLogger.lastStatus)
    }

    @Test
    fun `replace updates one supplied id without creating a duplicate`() = runBlocking {
        val existingEntry = MarkdownMemoryEntry(
            id = "mem_project",
            text = "Question project is at the first milestone.",
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 10L,
            updatedAt = 10L
        )
        val searchResult = MemoryIndexSearchResult(
            chunkId = "MEMORY.md#mem_project#0",
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            chunkIndex = 0,
            heading = "Projects",
            text = existingEntry.text,
            entryId = existingEntry.id,
            type = existingEntry.type,
            sensitivity = existingEntry.sensitivity,
            source = existingEntry.source,
            chatId = null,
            createdAt = existingEntry.createdAt,
            updatedAt = existingEntry.updatedAt,
            score = 10f
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = existingEntry.id,
                        text = "Question project has reached the second milestone.",
                        type = "project_context"
                    )
                )
            ),
            searchResults = listOf(searchResult)
        )
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(existingEntry))
        ).getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)
        val markdown = fixture.fileStore.readLongTermMemory().getOrThrow()

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertFalse(markdown.contains("first milestone"))
        assertEquals(1, markdown.split("second milestone").size - 1)
        assertEquals(1, MarkdownMemoryCodec().parse(markdown).entries.count { it.id == existingEntry.id })
    }

    @Test
    fun `index failure restores markdown and does not advance checkpoint`() = runBlocking {
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "This write must be rolled back."
                    )
                )
            ),
            failIndexRebuild = true
        )
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(0, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(5, fixture.turnDao.getTurnsClaimedByJob(job.jobId).size)
    }

    @Test
    fun `legacy learning and compaction jobs drain through the batch consolidation contract`() = runBlocking {
        val fixture = fixture(MemoryBatchConsolidationProposal())
        val jobs = listOf(
            legacyJob(
                type = MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
                suffix = "append",
                payloadJson = """
                    {
                      "chatId": 1,
                      "chatTitle": "Legacy chat",
                      "learningKey": "legacy-learning",
                      "recentMessages": [
                        {"role":"user","content":"Remember the durable legacy append."},
                        {"role":"assistant","content":"Acknowledged."}
                      ],
                      "createdAt": 100
                    }
                """.trimIndent()
            ),
            legacyJob(
                type = MemoryMaintenanceJobType.COMPACTION_FLUSH,
                suffix = "compaction",
                payloadJson = """
                    {
                      "chatId": 1,
                      "platformUid": "platform-1",
                      "omittedTurnCount": 1,
                      "messages": [{"role":"user","content":"Remember the durable legacy compaction."}],
                      "createdAt": 101
                    }
                """.trimIndent()
            )
        )

        jobs.forEach { job ->
            fixture.jobDao.insertIgnore(job)
            val turnKey = "legacy:${sha256(job.idempotencyKey).take(24)}"
            fixture.intelligence.batchProposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.DAILY,
                        text = "Recovered ${job.type} through batching.",
                        evidenceTurnKeys = listOf(turnKey)
                    )
                )
            )

            val result = fixture.service.processLegacy(job)

            assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
            assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.jobDao.getById(job.jobId)?.status)
            assertTrue(fixture.intelligence.lastBatchRequest!!.turns.single().userContent.contains("durable legacy"))
        }

        val callsAfterDrain = fixture.intelligence.consolidateCalls
        val replay = fixture.service.processLegacy(fixture.jobDao.getById(jobs.first().jobId)!!)
        val dailyMarkdown = fixture.fileStore.readDailyMemory().getOrThrow()

        assertEquals(MemoryBatchProcessResult.STATUS_DUPLICATE, replay.status)
        assertEquals(callsAfterDrain, fixture.intelligence.consolidateCalls)
        assertEquals(1, dailyMarkdown.split("Recovered append_daily_note through batching.").size - 1)
        assertEquals(1, dailyMarkdown.split("Recovered compaction_flush through batching.").size - 1)
    }

    private fun fixture(
        proposal: MemoryBatchConsolidationProposal?,
        searchResults: List<MemoryIndexSearchResult> = emptyList(),
        failIndexRebuild: Boolean = false
    ): Fixture {
        val turnDao = InMemoryMemoryTurnBatchDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val enqueuer = RecordingWorkEnqueuer()
        val settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = true)
        val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
        val turnBatchScheduler = MemoryTurnBatchScheduler(
            turnBatchDao = turnDao,
            maintenanceJobDao = jobDao,
            maintenanceScheduler = maintenanceScheduler,
            workEnqueuer = enqueuer,
            settingRepository = settingRepository,
            clock = FIXED_CLOCK
        )
        val fileStore = MemoryFileStore(
            MemoryFilePaths(Files.createTempDirectory("memory-batch-consolidation").toFile()),
            FIXED_CLOCK
        )
        fileStore.ensureStore().getOrThrow()
        val indexDao = InMemoryProcessorMemoryIndexDao()
        val indexRepository = MemoryIndexRepository(fileStore, indexDao, MemoryChunker(), FIXED_CLOCK)
        val intelligence = FakeMemoryIntelligence(batchProposal = proposal)
        val activityLogger = RecordingOrganizationActivityLogger()
        val maintenanceCorpusReader = object : MemoryMaintenanceCorpusReader {
            override suspend fun retrieveWorkingSet(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> =
                if (request.corpus == MemoryCorpus.MAINTENANCE_WORKING_SET) {
                    Result.success(searchResults.map { it.toRetrievalResult() })
                } else {
                    Result.failure(IllegalArgumentException("Expected maintenance working set"))
                }
        }
        val rebuilder = if (failIndexRebuild) {
            FailingMemoryIndexRebuilder()
        } else {
            indexRepository
        }
        return Fixture(
            turnDao = turnDao,
            jobDao = jobDao,
            fileStore = fileStore,
            indexDao = indexDao,
            intelligence = intelligence,
            activityLogger = activityLogger,
            coordinator = MemoryTurnBatchCoordinator(turnDao, turnBatchScheduler),
            turnBatchScheduler = turnBatchScheduler,
            service = MemoryBatchConsolidationService(
                turnBatchDao = turnDao,
                maintenanceScheduler = maintenanceScheduler,
                turnBatchScheduler = turnBatchScheduler,
                settingRepository = settingRepository,
                memoryIntelligence = intelligence,
                memoryFileStore = fileStore,
                markdownMemoryCodec = MarkdownMemoryCodec(),
                memoryMaintenanceCorpusReader = maintenanceCorpusReader,
                memoryIndexRebuilder = rebuilder,
                activityLogger = activityLogger,
                clock = FIXED_CLOCK
            )
        )
    }

    private fun operation(
        destination: String,
        action: String = MemoryBatchAction.CREATE,
        targetMemoryId: String? = null,
        text: String,
        type: String = "stable_profile",
        evidenceTurnKeys: List<String> = listOf("chat:$CHAT_ID:user:1")
    ): MemoryBatchOperation = MemoryBatchOperation(
        destination = destination,
        action = action,
        targetMemoryId = targetMemoryId,
        text = text,
        type = type,
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        evidenceTurnKeys = evidenceTurnKeys,
        reason = "Test operation"
    )

    private fun legacyJob(type: String, suffix: String, payloadJson: String) = MemoryMaintenanceJob(
        jobId = "legacy-job-$suffix",
        type = type,
        status = MemoryMaintenanceJobStatus.PENDING,
        idempotencyKey = "legacy-key-$suffix",
        payloadJson = payloadJson,
        attempts = 0,
        lastError = null,
        createdAt = 100L,
        startedAt = null,
        updatedAt = 100L,
        nextRunAt = null
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun MemoryIndexSearchResult.toRetrievalResult(): MemoryRetrievalResult = MemoryRetrievalResult(
        chunkId = chunkId,
        entryId = entryId,
        sourcePath = sourcePath,
        text = text,
        type = type,
        sensitivity = sensitivity,
        source = source,
        contentHash = "hash-$chunkId",
        lexicalScore = score,
        fusedScore = score,
        updatedAt = updatedAt
    )

    private data class Fixture(
        val turnDao: InMemoryMemoryTurnBatchDao,
        val jobDao: InMemoryMaintenanceJobDao,
        val fileStore: MemoryFileStore,
        val indexDao: InMemoryProcessorMemoryIndexDao,
        val intelligence: FakeMemoryIntelligence,
        val activityLogger: RecordingOrganizationActivityLogger,
        val coordinator: MemoryTurnBatchCoordinator,
        val turnBatchScheduler: MemoryTurnBatchScheduler,
        val service: MemoryBatchConsolidationService
    ) {
        suspend fun createTurns(count: Int) {
            (1..count).forEach { userMessageId ->
                coordinator.recordCompletedTurn(
                    MemoryCompletedTurnInput(
                        chatRoom = ChatRoomV2(
                            id = CHAT_ID,
                            title = "Batch test",
                            enabledPlatform = listOf("platform-1")
                        ),
                        userMessage = MessageV2(
                            id = userMessageId,
                            chatId = CHAT_ID,
                            content = "Question $userMessageId about durable preferences",
                            platformType = null,
                            createdAt = userMessageId.toLong()
                        ),
                        assistantMessages = listOf(
                            MessageV2(
                                id = userMessageId + 100,
                                chatId = CHAT_ID,
                                content = "Answer $userMessageId",
                                platformType = "platform-1"
                            )
                        ),
                        preferredPlatformUid = "platform-1",
                        stablePlatformOrder = listOf("platform-1"),
                        completedAt = userMessageId.toLong() + 10L
                    )
                )
            }
        }

        suspend fun createFiveTurnBatch() = run {
            createTurns(5)
            jobDao.jobs.single()
        }
    }

    companion object {
        private const val CHAT_ID = 7
        private val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(1_000L), ZoneOffset.UTC)
    }
}

private class RecordingOrganizationActivityLogger : MemoryActivityLogger {
    var lastStatus: String? = null

    override suspend fun start(
        batchId: String,
        category: String,
        platformName: String?,
        modelName: String?,
        attempt: Int?,
        turnCount: Int?
    ): String = category

    override suspend fun finish(logId: String, status: String, detail: String?, operationCount: Int?) {
        if (logId == MemoryActivityCategory.MEMORY_ORGANIZATION) lastStatus = status
    }
}

private class FailingMemoryIndexRebuilder : MemoryIndexRebuilder {
    override suspend fun rebuildFile(file: File): Result<MemoryIndexRebuildResult> =
        Result.failure(IllegalStateException("index failure"))
}
