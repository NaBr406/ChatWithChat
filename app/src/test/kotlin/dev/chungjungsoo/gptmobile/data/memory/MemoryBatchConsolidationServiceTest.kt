package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.jobDao.getById(job.jobId)?.status)
        assertEquals(
            MemoryCorpusIndexStatus.PENDING,
            fixture.recoveryDao.getCorpusState(MemoryCorpus.CHAT_RECALL_LONG_TERM.name.lowercase())?.indexStatus
        )
        assertTrue(fixture.jobDao.jobs.any { it.family == MemoryMaintenanceJobFamily.INDEX })
        assertEquals(MemoryActivityStatus.SUCCEEDED, fixture.activityLogger.lastStatus)
    }

    @Test
    fun `four turns consolidate once after persisted idle deadline`() = runBlocking {
        val fixture = fixture(MemoryBatchConsolidationProposal())
        fixture.createTurns(4)

        assertEquals(0, fixture.intelligence.consolidateCalls)
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertEquals(1, fixture.turnBatchScheduler.promoteDueIdleBatches(now = 1_804L))
        fixture.service.process(fixture.claim(fixture.jobDao.jobs.single()))

        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertEquals(4, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
    }

    @Test
    fun `ten turns run as two sequential consolidation calls`() = runBlocking {
        val fixture = fixture(MemoryBatchConsolidationProposal())
        fixture.createTurns(10)
        val firstJob = fixture.claim(fixture.jobDao.jobs.single())

        fixture.service.process(firstJob)
        val secondJob = fixture.claim(fixture.jobDao.jobs.single { it.jobId != firstJob.jobId })
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
    fun `stale semantic response cannot write after repair reclaims its lease`() = runBlocking {
        val clock = MutableBatchConsolidationClock(1_000L)
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "A stale worker must never commit this memory."
                    )
                )
            ),
            clock = clock
        )
        val beforeLongTerm = fixture.fileStore.readLongTermMemory().getOrThrow()
        val beforeDaily = fixture.fileStore.readDailyMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()
        fixture.intelligence.onConsolidate = {
            clock.setEpochSecond(checkNotNull(job.leaseExpiresAt) + 1L)
            assertEquals(1, fixture.maintenanceScheduler.resetExpiredRunningJobs())
        }

        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()

        assertTrue(failure is MemoryMaintenanceLeaseLostException)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertEquals(beforeLongTerm, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(beforeDaily, fixture.fileStore.readDailyMemory().getOrThrow())
        assertEquals(5, fixture.turnDao.getTurnsClaimedByJob(job.jobId).size)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, fixture.jobDao.getById(job.jobId)?.status)
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
    fun `index scheduling failure keeps committed markdown and advances checkpoint`() = runBlocking {
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "This canonical write survives index scheduling failure."
                    )
                )
            ),
            failIndexScheduling = true
        )
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertTrue(fixture.fileStore.readLongTermMemory().getOrThrow().contains("survives index scheduling failure"))
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertTrue(fixture.turnDao.getTurnsClaimedByJob(job.jobId).isEmpty())
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.jobDao.getById(job.jobId)?.status)
        assertTrue(fixture.jobDao.jobs.any { it.family == MemoryMaintenanceJobFamily.INDEX })
    }

    @Test
    fun `process death after prepared receipt resumes without another semantic call`() = runBlocking {
        val clock = MutableBatchConsolidationClock(1_000L)
        val observer = OneShotCommitObserver(CommitInterruptionPoint.AFTER_PREPARED)
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "Prepared targets resume without a second semantic call."
                    )
                )
            ),
            clock = clock,
            commitObserver = observer
        )
        val job = fixture.createFiveTurnBatch()

        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()

        assertTrue(failure is MemoryBatchCommitInterruptedException)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertFalse(fixture.fileStore.readLongTermMemory().getOrThrow().contains("Prepared targets resume"))

        val replay = fixture.reclaim(job, clock)
        val result = fixture.service.process(replay)

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertTrue(fixture.fileStore.readLongTermMemory().getOrThrow().contains("Prepared targets resume"))
    }

    @Test
    fun `process death after canonical commit resumes without another semantic call`() = runBlocking {
        val clock = MutableBatchConsolidationClock(1_000L)
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "Canonical commit is durable across process death."
                    )
                )
            ),
            clock = clock,
            commitObserver = OneShotCommitObserver(CommitInterruptionPoint.AFTER_CANONICAL_COMMIT)
        )
        val job = fixture.createFiveTurnBatch()

        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()

        assertTrue(failure is MemoryBatchCommitInterruptedException)
        assertTrue(fixture.fileStore.readLongTermMemory().getOrThrow().contains("durable across process death"))
        assertEquals(1, fixture.intelligence.consolidateCalls)

        val result = fixture.service.process(fixture.reclaim(job, clock))

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
    }

    @Test
    fun `empty batch replay after completion uses checkpoint evidence without another semantic call`() = runBlocking {
        val clock = MutableBatchConsolidationClock(1_000L)
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(),
            clock = clock,
            commitObserver = OneShotCommitObserver(CommitInterruptionPoint.AFTER_BATCH_COMPLETION)
        )
        val job = fixture.createFiveTurnBatch()

        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()

        assertTrue(failure is MemoryBatchCommitInterruptedException)
        assertTrue(fixture.turnDao.getTurnsClaimedByJob(job.jobId).isEmpty())
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(1, fixture.intelligence.consolidateCalls)

        val result = fixture.service.process(fixture.reclaim(job, clock))

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertEquals(0, result.dailyWriteCount + result.longTermWriteCount)
    }

    @Test
    fun `empty semantic marker survives process death before batch completion`() = runBlocking {
        val clock = MutableBatchConsolidationClock(1_000L)
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(),
            clock = clock,
            commitObserver = OneShotCommitObserver(CommitInterruptionPoint.AFTER_CANONICAL_COMMIT)
        )
        val job = fixture.createFiveTurnBatch()

        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()

        assertTrue(failure is MemoryBatchCommitInterruptedException)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertEquals(
            MemoryMutationState.SEMANTIC_ACK_PENDING,
            fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId)?.state
        )

        val result = fixture.service.process(fixture.reclaim(job, clock))

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertEquals(
            MemoryMutationState.INDEXED,
            fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId)?.state
        )
    }

    @Test
    fun `local recovery releases a terminal semantic batch after prepared`() = runBlocking {
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "Terminal semantic work is finalized by local recovery."
                    )
                )
            ),
            commitObserver = OneShotCommitObserver(CommitInterruptionPoint.AFTER_PREPARED)
        )
        val job = fixture.createFiveTurnBatch()
        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()
        assertTrue(failure is MemoryBatchCommitInterruptedException)
        fixture.maintenanceScheduler.markFailedTerminal(job, "simulated_semantic_exhaustion")

        val recovery = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.mutationCoordinator,
            turnBatchDao = fixture.turnDao,
            maintenanceScheduler = fixture.maintenanceScheduler,
            clock = FIXED_CLOCK
        ).recoverIncomplete()

        assertEquals(1, recovery.recoveredSemanticCount)
        assertEquals(0, recovery.failedCount)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertTrue(fixture.fileStore.readLongTermMemory().getOrThrow().contains("finalized by local recovery"))
        assertTrue(fixture.turnDao.getTurnsClaimedByJob(job.jobId).isEmpty())
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)?.lastProcessedUserMessageId)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.jobDao.getById(job.jobId)?.status)
        assertEquals(
            MemoryMutationState.INDEX_PENDING,
            fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId)?.state
        )
    }

    @Test
    fun `local recovery releases terminal batch when canonical content conflicts`() = runBlocking {
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "This stale target must not overwrite newer canonical content."
                    )
                )
            ),
            commitObserver = OneShotCommitObserver(CommitInterruptionPoint.AFTER_PREPARED)
        )
        val job = fixture.createFiveTurnBatch()
        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()
        assertTrue(failure is MemoryBatchCommitInterruptedException)
        fixture.fileStore.replaceLongTermMemory(
            "# ChatWithChat Memory\n\n- Newer canonical content wins\n"
        ).getOrThrow()
        fixture.maintenanceScheduler.markFailedTerminal(job, "simulated_semantic_exhaustion")

        val recovery = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.mutationCoordinator,
            turnBatchDao = fixture.turnDao,
            maintenanceScheduler = fixture.maintenanceScheduler,
            clock = FIXED_CLOCK
        ).recoverIncomplete()

        assertEquals(1, recovery.conflictCount)
        assertEquals(1, recovery.recoveredSemanticCount)
        assertTrue(fixture.turnDao.getTurnsClaimedByJob(job.jobId).isEmpty())
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, fixture.jobDao.getById(job.jobId)?.status)
        assertEquals(
            MemoryMutationState.CONFLICT,
            fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId)?.state
        )
        assertTrue(fixture.fileStore.readLongTermMemory().getOrThrow().contains("Newer canonical content wins"))
    }

    @Test
    fun `local recovery finalizes terminal batch superseded by a newer generation`() = runBlocking {
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "Older canonical generation."
                    )
                )
            ),
            commitObserver = OneShotCommitObserver(CommitInterruptionPoint.AFTER_CANONICAL_COMMIT)
        )
        val job = fixture.createFiveTurnBatch()
        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()
        assertTrue(failure is MemoryBatchCommitInterruptedException)
        fixture.maintenanceScheduler.markFailedTerminal(job, "simulated_semantic_exhaustion")
        val olderGroup = checkNotNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId))
        val currentContent = fixture.fileStore.readLongTermMemory().getOrThrow()
        val newerMutation = fixture.mutationCoordinator.prepare(
            semanticJobId = "newer-semantic-without-source-job",
            semanticBatchId = "newer-batch",
            targets = listOf(
                MemoryMutationTarget(
                    sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                    baseContent = currentContent,
                    targetContent = "# ChatWithChat Memory\n\n- Newer canonical generation.\n",
                    targetIndexFingerprint = "newer-fingerprint"
                )
            )
        )
        fixture.mutationCoordinator.reconcile(newerMutation)
        fixture.mutationCoordinator.acknowledgeSemanticCompletion(newerMutation.group.groupId)

        val recovery = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.mutationCoordinator,
            turnBatchDao = fixture.turnDao,
            maintenanceScheduler = fixture.maintenanceScheduler,
            clock = FIXED_CLOCK
        ).recoverIncomplete()

        assertEquals(1, recovery.recoveredSemanticCount)
        assertTrue(fixture.turnDao.getTurnsClaimedByJob(job.jobId).isEmpty())
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.jobDao.getById(job.jobId)?.status)
        assertEquals(MemoryMutationState.SUPERSEDED, fixture.recoveryDao.getMutationGroup(olderGroup.groupId)?.state)
        assertTrue(fixture.fileStore.readLongTermMemory().getOrThrow().contains("Newer canonical generation"))
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

            val result = fixture.service.processLegacy(fixture.claim(job))

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
        failIndexScheduling: Boolean = false,
        clock: Clock = FIXED_CLOCK,
        commitObserver: MemoryBatchCommitObserver = MemoryBatchCommitObserver.None
    ): Fixture {
        val turnDao = InMemoryMemoryTurnBatchDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val enqueuer: MemoryMaintenanceWorkEnqueuer = if (failIndexScheduling) {
            IndexFailingWorkEnqueuer()
        } else {
            RecordingWorkEnqueuer()
        }
        val settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = true)
        val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, clock)
        val turnBatchScheduler = MemoryTurnBatchScheduler(
            turnBatchDao = turnDao,
            maintenanceJobDao = jobDao,
            maintenanceScheduler = maintenanceScheduler,
            workEnqueuer = enqueuer,
            settingRepository = settingRepository,
            clock = clock
        )
        val fileStore = MemoryFileStore(
            MemoryFilePaths(Files.createTempDirectory("memory-batch-consolidation").toFile()),
            clock
        )
        fileStore.ensureStore().getOrThrow()
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val mutationCoordinator = MemoryMutationCoordinator(
            recoveryDao = recoveryDao,
            memoryFileStore = fileStore,
            maintenanceScheduler = maintenanceScheduler,
            workEnqueuer = enqueuer,
            clock = clock
        )
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
        return Fixture(
            turnDao = turnDao,
            jobDao = jobDao,
            fileStore = fileStore,
            recoveryDao = recoveryDao,
            intelligence = intelligence,
            activityLogger = activityLogger,
            maintenanceScheduler = maintenanceScheduler,
            mutationCoordinator = mutationCoordinator,
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
                memoryMutationCoordinator = mutationCoordinator,
                activityLogger = activityLogger,
                commitObserver = commitObserver,
                clock = clock
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
        nextRunAt = null,
        family = MemoryMaintenanceJobFamily.forType(type)
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
        val recoveryDao: InMemoryMemoryRecoveryDao,
        val intelligence: FakeMemoryIntelligence,
        val activityLogger: RecordingOrganizationActivityLogger,
        val maintenanceScheduler: MemoryMaintenanceScheduler,
        val mutationCoordinator: MemoryMutationCoordinator,
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
            claim(jobDao.jobs.single())
        }

        suspend fun claim(job: MemoryMaintenanceJob): MemoryMaintenanceJob = checkNotNull(
            maintenanceScheduler.claimNextRunnable(
                family = job.family,
                leaseOwner = "test-owner:${job.jobId}"
            )
        )

        suspend fun reclaim(
            job: MemoryMaintenanceJob,
            clock: MutableBatchConsolidationClock
        ): MemoryMaintenanceJob {
            clock.setEpochSecond(checkNotNull(job.leaseExpiresAt) + 1L)
            check(maintenanceScheduler.resetExpiredRunningJobs() == 1)
            return claim(checkNotNull(jobDao.getById(job.jobId)))
        }
    }

    companion object {
        private const val CHAT_ID = 7
        private val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(1_000L), ZoneOffset.UTC)
    }
}

private class MutableBatchConsolidationClock(epochSecond: Long) : Clock() {
    private var currentInstant: Instant = Instant.ofEpochSecond(epochSecond)

    fun setEpochSecond(epochSecond: Long) {
        currentInstant = Instant.ofEpochSecond(epochSecond)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(currentInstant, zone)

    override fun instant(): Instant = currentInstant
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

private enum class CommitInterruptionPoint {
    AFTER_PREPARED,
    AFTER_CANONICAL_COMMIT,
    AFTER_BATCH_COMPLETION
}

private class OneShotCommitObserver(
    private val interruptionPoint: CommitInterruptionPoint
) : MemoryBatchCommitObserver {
    private var interrupted = false

    override suspend fun afterPrepared(mutation: MemoryPreparedMutation) {
        interruptAt(CommitInterruptionPoint.AFTER_PREPARED)
    }

    override suspend fun afterCanonicalFileCommit(mutation: MemoryPreparedMutation) {
        interruptAt(CommitInterruptionPoint.AFTER_CANONICAL_COMMIT)
    }

    override suspend fun afterBatchCompletion(jobId: String) {
        interruptAt(CommitInterruptionPoint.AFTER_BATCH_COMPLETION)
    }

    private fun interruptAt(point: CommitInterruptionPoint) {
        if (!interrupted && interruptionPoint == point) {
            interrupted = true
            throw MemoryBatchCommitInterruptedException("Simulated process death at $point")
        }
    }
}

private class IndexFailingWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
    override fun enqueueWork(family: String, delaySeconds: Long) {
        if (family == MemoryMaintenanceJobFamily.INDEX) {
            error("Simulated index scheduling failure")
        }
    }
}
