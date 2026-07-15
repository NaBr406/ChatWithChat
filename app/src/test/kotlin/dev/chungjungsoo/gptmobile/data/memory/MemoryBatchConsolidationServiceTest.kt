package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
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
    fun `same batch exact duplicate creates fail closed without advancing checkpoint`() = runBlocking {
        val fixture = fixture(
            MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "The user prefers CAFÉ answers."
                    ),
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "\u00a0THE user prefers\u3000café\nanswers.  "
                    )
                )
            )
        )
        val beforeLongTerm = fixture.fileStore.readLongTermMemory().getOrThrow()
        val beforeDaily = fixture.fileStore.readDailyMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals(0, result.dailyWriteCount + result.longTermWriteCount)
        assertEquals(beforeLongTerm, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(beforeDaily, fixture.fileStore.readDailyMemory().getOrThrow())
        assertEquals(0, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(5, fixture.turnDao.getTurnsClaimedByJob(job.jobId).size)
        assertEquals(null, fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId))
    }

    @Test
    fun `create and replace with exact text in one target fail before rendering`() = runBlocking {
        val existingEntry = longTermEntry(
            id = "mem_mixed_target",
            text = "The user originally preferred concise answers."
        )
        val duplicateText = "The user now prefers detailed answers."
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = duplicateText
                    ),
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = existingEntry.id,
                        text = "  THE user now prefers\u3000detailed answers.  "
                    )
                )
            ),
            retrievalResults = listOf(retrievalResult(existingEntry))
        )
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(existingEntry))
        ).getOrThrow()
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(0, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(null, fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId))
    }

    @Test
    fun `multiple replacements with exact text in one target fail before rendering`() = runBlocking {
        val firstEntry = longTermEntry("mem_first_target", "The first project is active.")
        val secondEntry = longTermEntry("mem_second_target", "The second project is active.")
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = firstEntry.id,
                        text = "Both projects share the same status."
                    ),
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = secondEntry.id,
                        text = "\u00a0BOTH projects share the same\nstatus.  "
                    )
                )
            ),
            retrievalResults = listOf(retrievalResult(firstEntry), retrievalResult(secondEntry))
        )
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(firstEntry, secondEntry))
        ).getOrThrow()
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(0, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(null, fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId))
    }

    @Test
    fun `replace matching an unscoped canonical entry fails before prepare`() = runBlocking {
        val replacementTarget = longTermEntry("mem_replacement_target", "An obsolete project detail.")
        val canonicalOnlyEntry = longTermEntry("mem_canonical_only", "The canonical project detail.")
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = replacementTarget.id,
                        text = "  THE canonical\u3000project detail.  "
                    )
                )
            ),
            retrievalResults = listOf(retrievalResult(replacementTarget))
        )
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(replacementTarget, canonicalOnlyEntry))
        ).getOrThrow()
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(0, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(null, fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId))
    }

    @Test
    fun `same proposal exact text writes across destinations fail closed`() = runBlocking {
        val fixture = fixture(
            MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.DAILY,
                        text = "The user is tracking a cross-target fact."
                    ),
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "\u00a0THE user is tracking a\u3000cross-target fact.  "
                    )
                )
            )
        )
        val beforeDaily = fixture.fileStore.readDailyMemory().getOrThrow()
        val beforeLongTerm = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals(0, result.dailyWriteCount + result.longTermWriteCount)
        assertEquals(beforeDaily, fixture.fileStore.readDailyMemory().getOrThrow())
        assertEquals(beforeLongTerm, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(0, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(null, fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId))
    }

    @Test
    fun `unique write does not expand historical canonical duplicates`() = runBlocking {
        val firstDuplicate = longTermEntry("mem_historical_duplicate_one", "A historical duplicate remains visible.")
        val secondDuplicate = longTermEntry(
            "mem_historical_duplicate_two",
            "\u00a0A historical\u3000duplicate remains visible.  "
        )
        val fixture = fixture(
            MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "A new unique canonical fact."
                    )
                )
            )
        )
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(firstDuplicate, secondDuplicate))
        ).getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)
        val entries = MarkdownMemoryCodec().parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, result.longTermWriteCount)
        assertEquals(
            2,
            entries.count { entry ->
                normalizeExactMemoryText(entry.text) == normalizeExactMemoryText(firstDuplicate.text)
            }
        )
        assertEquals(1, entries.count { entry -> entry.text == "A new unique canonical fact." })
    }

    @Test
    fun `replace cannot expand historical canonical duplicate count`() = runBlocking {
        val firstDuplicate = longTermEntry("mem_duplicate_one", "A historical duplicate remains visible.")
        val secondDuplicate = longTermEntry(
            "mem_duplicate_two",
            "\u00a0A historical\u3000duplicate remains visible.  "
        )
        val replacementTarget = longTermEntry("mem_unique_target", "A unique target before replacement.")
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = replacementTarget.id,
                        text = "  A HISTORICAL duplicate\nremains visible.  "
                    )
                )
            ),
            retrievalResults = listOf(retrievalResult(replacementTarget))
        )
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(firstDuplicate, secondDuplicate, replacementTarget))
        ).getOrThrow()
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(0, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(null, fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId))
    }

    @Test
    fun `create matching canonical text is a replayable byte identical no-op`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val existingEntry = MarkdownMemoryEntry(
            id = "mem_existing_exact",
            text = "The user prefers CAFÉ answers.",
            type = "communication_style",
            sensitivity = MemorySensitivity.PRIVATE,
            source = MemorySource.USER_CONFIRMED,
            chatId = 3,
            createdAt = 20L,
            updatedAt = 30L,
            section = "Stable Preferences"
        )
        val fixture = fixture(
            MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "\u00a0THE user prefers\u3000café\nanswers.  ",
                        type = "stable_profile"
                    )
                )
            )
        )
        fixture.fileStore.replaceLongTermMemory(codec.renderLongTerm(listOf(existingEntry))).getOrThrow()
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)
        val group = checkNotNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId))

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, result.operationCount)
        assertEquals(0, result.dailyWriteCount + result.longTermWriteCount)
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(existingEntry, codec.parse(before).entries.single())
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(0, group.expectedReceiptCount)
        assertTrue(fixture.recoveryDao.getMutationReceipts(group.groupId).isEmpty())

        val replay = fixture.service.process(checkNotNull(fixture.jobDao.getById(job.jobId)))

        assertEquals(MemoryBatchProcessResult.STATUS_DUPLICATE, replay.status)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(group.groupId, fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId)?.groupId)
        assertTrue(fixture.recoveryDao.getMutationReceipts(group.groupId).isEmpty())
    }

    @Test
    fun `create before replace relocates canonical text with original operation id`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val original = longTermEntry("mem_create_before_replace", "A canonical fact must survive relocation.")
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "  A CANONICAL fact must survive\nrelocation.  "
                    ),
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = original.id,
                        text = "The original entry now records a replacement fact."
                    )
                )
            ),
            retrievalResults = listOf(retrievalResult(original))
        )
        fixture.fileStore.replaceLongTermMemory(codec.renderLongTerm(listOf(original))).getOrThrow()
        val job = fixture.createFiveTurnBatch()
        val expectedCreatedId = "mem_${sha256("${job.idempotencyKey}|0|${MemoryBatchDestination.LONG_TERM}").take(24)}"

        val result = fixture.service.process(job)
        val entries = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries
        val relocated = entries.single { entry ->
            normalizeExactMemoryText(entry.text) == normalizeExactMemoryText(original.text)
        }

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.longTermWriteCount)
        assertEquals(expectedCreatedId, relocated.id)
        assertEquals(
            "The original entry now records a replacement fact.",
            entries.single { entry -> entry.id == original.id }.text
        )
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)?.lastProcessedUserMessageId)
    }

    @Test
    fun `replace before create preserves reverse order operation id`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val original = longTermEntry("mem_replace_before_create", "A reverse-order fact must survive relocation.")
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = original.id,
                        text = "The reverse-order target now records a replacement fact."
                    ),
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "  A REVERSE-ORDER fact must survive\nrelocation.  "
                    )
                )
            ),
            retrievalResults = listOf(retrievalResult(original))
        )
        fixture.fileStore.replaceLongTermMemory(codec.renderLongTerm(listOf(original))).getOrThrow()
        val job = fixture.createFiveTurnBatch()
        val expectedCreatedId = "mem_${sha256("${job.idempotencyKey}|1|${MemoryBatchDestination.LONG_TERM}").take(24)}"

        val result = fixture.service.process(job)
        val entries = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries
        val relocated = entries.single { entry ->
            normalizeExactMemoryText(entry.text) == normalizeExactMemoryText(original.text)
        }

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.longTermWriteCount)
        assertEquals(expectedCreatedId, relocated.id)
        assertEquals(
            "The reverse-order target now records a replacement fact.",
            entries.single { entry -> entry.id == original.id }.text
        )
    }

    @Test
    fun `create relocates one historical duplicate without reducing multiplicity`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val first = longTermEntry("mem_historical_relocation_first", "A historical canonical duplicate.")
        val second = longTermEntry(
            "mem_historical_relocation_second",
            "\u00a0A HISTORICAL canonical\u3000duplicate.  "
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "A historical canonical duplicate."
                    ),
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = first.id,
                        text = "The relocated historical entry now has unique content."
                    )
                )
            ),
            retrievalResults = listOf(retrievalResult(first))
        )
        fixture.fileStore.replaceLongTermMemory(codec.renderLongTerm(listOf(first, second))).getOrThrow()
        val job = fixture.createFiveTurnBatch()
        val expectedCreatedId = "mem_${sha256("${job.idempotencyKey}|0|${MemoryBatchDestination.LONG_TERM}").take(24)}"

        val result = fixture.service.process(job)
        val entries = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries
        val duplicateEntries = entries.filter { entry ->
            normalizeExactMemoryText(entry.text) == normalizeExactMemoryText(first.text)
        }

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.longTermWriteCount)
        assertEquals(2, duplicateEntries.size)
        assertTrue(duplicateEntries.any { entry -> entry.id == second.id })
        assertTrue(duplicateEntries.any { entry -> entry.id == expectedCreatedId })
        assertEquals(
            "The relocated historical entry now has unique content.",
            entries.single { entry -> entry.id == first.id }.text
        )
    }

    @Test
    fun `replacement swap preserves both canonical texts and ids`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val first = longTermEntry("mem_swap_first", "The first canonical fact.")
        val second = longTermEntry("mem_swap_second", "The second canonical fact.")
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = first.id,
                        text = second.text
                    ),
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = second.id,
                        text = first.text
                    )
                )
            ),
            retrievalResults = listOf(retrievalResult(first), retrievalResult(second))
        )
        fixture.fileStore.replaceLongTermMemory(codec.renderLongTerm(listOf(first, second))).getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)
        val entriesById = codec
            .parse(fixture.fileStore.readLongTermMemory().getOrThrow())
            .entries
            .associateBy { entry -> entry.id }

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.longTermWriteCount)
        assertEquals(second.text, entriesById.getValue(first.id).text)
        assertEquals(first.text, entriesById.getValue(second.id).text)
    }

    @Test
    fun `create and remove relocate canonical text instead of deleting it`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val original = longTermEntry("mem_create_remove", "A removed entry must be recreated by the paired create.")
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "  A REMOVED entry must be recreated\nby the paired create.  "
                    ),
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REMOVE,
                        targetMemoryId = original.id,
                        text = ""
                    )
                )
            ),
            retrievalResults = listOf(retrievalResult(original))
        )
        fixture.fileStore.replaceLongTermMemory(codec.renderLongTerm(listOf(original))).getOrThrow()
        val job = fixture.createFiveTurnBatch()
        val expectedCreatedId = "mem_${sha256("${job.idempotencyKey}|0|${MemoryBatchDestination.LONG_TERM}").take(24)}"

        val result = fixture.service.process(job)
        val entries = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.longTermWriteCount)
        assertEquals(1, entries.size)
        assertEquals(expectedCreatedId, entries.single().id)
        assertEquals(normalizeExactMemoryText(original.text), normalizeExactMemoryText(entries.single().text))
        assertFalse(entries.any { entry -> entry.id == original.id })
    }

    @Test
    fun `daily exact text does not block a long term create`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val dailyEntry = MarkdownMemoryEntry(
            id = "day_existing_exact",
            text = "The user prefers CAFÉ answers.",
            type = "communication_style",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 20L,
            updatedAt = 30L
        )
        val fixture = fixture(
            MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "  THE user prefers   café\nanswers.  ",
                        type = "communication_style"
                    )
                )
            )
        )
        fixture.fileStore.appendDailyNote(codec.renderDailyAppend(listOf(dailyEntry))).getOrThrow()
        val beforeDaily = fixture.fileStore.readDailyMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(0, result.dailyWriteCount)
        assertEquals(1, result.longTermWriteCount)
        assertEquals(beforeDaily, fixture.fileStore.readDailyMemory().getOrThrow())
        assertEquals(1, codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries.size)
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
        val retrievalResult = MemoryRetrievalResult(
            chunkId = "MEMORY.md#mem_project#0",
            entryId = existingEntry.id,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            text = existingEntry.text,
            type = existingEntry.type,
            sensitivity = existingEntry.sensitivity,
            source = existingEntry.source,
            contentHash = "hash-MEMORY.md#mem_project#0",
            lexicalScore = 10f,
            fusedScore = 10f,
            updatedAt = existingEntry.updatedAt
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
            retrievalResults = listOf(retrievalResult)
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
    fun `missing staged batch target preserves terminal reason without semantic replay`() = runBlocking {
        val clock = MutableBatchConsolidationClock(1_000L)
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "Missing staged targets are terminal."
                    )
                )
            ),
            clock = clock,
            commitObserver = OneShotCommitObserver(CommitInterruptionPoint.AFTER_PREPARED)
        )
        val job = fixture.createFiveTurnBatch()
        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()
        assertTrue(failure is MemoryBatchCommitInterruptedException)
        val mutation = checkNotNull(fixture.mutationCoordinator.findBySemanticJobId(job.jobId))
        Files.delete(fixture.fileStoreRoot().resolve(mutation.receipts.single().stagedTargetPath).toPath())

        val result = fixture.service.process(fixture.reclaim(job, clock))

        assertEquals(MemoryBatchProcessResult.STATUS_TERMINAL, result.status)
        assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, result.reason)
        assertEquals(1, fixture.intelligence.consolidateCalls)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, fixture.jobDao.getById(job.jobId)?.status)
        assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, fixture.jobDao.getById(job.jobId)?.lastError)
        assertEquals(MemoryMutationState.CONFLICT, fixture.recoveryDao.getMutationGroup(mutation.group.groupId)?.state)
        assertFalse(fixture.fileStore.readLongTermMemory().getOrThrow().contains("Missing staged targets"))
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
    fun `process death after source job completion leaves semantic acknowledgement recoverable`() = runBlocking {
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "Source completion remains recoverable before semantic acknowledgement."
                    )
                )
            ),
            commitObserver = OneShotCommitObserver(CommitInterruptionPoint.AFTER_SOURCE_JOB_COMPLETION)
        )
        val job = fixture.createFiveTurnBatch()

        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()
        val mutation = checkNotNull(fixture.mutationCoordinator.findBySemanticJobId(job.jobId))

        assertTrue(failure is MemoryBatchCommitInterruptedException)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.jobDao.getById(job.jobId)?.status)
        assertEquals(MemoryMutationState.SEMANTIC_ACK_PENDING, mutation.group.state)
        assertTrue(fixture.turnDao.getTurnsClaimedByJob(job.jobId).isEmpty())
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)

        val recovery = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.mutationCoordinator,
            turnBatchDao = fixture.turnDao,
            maintenanceScheduler = fixture.maintenanceScheduler
        ).recoverIncomplete(scheduleRetry = false)

        assertEquals(1, recovery.recoveredSemanticCount)
        assertEquals(0, recovery.activeSourceJobCount)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.recoveryDao.getMutationGroup(mutation.group.groupId)?.state)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.jobDao.getById(job.jobId)?.status)
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
        retrievalResults: List<MemoryRetrievalResult> = emptyList(),
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
                    Result.success(retrievalResults)
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

    private fun longTermEntry(id: String, text: String): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = "project_context",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = 10L,
        updatedAt = 10L,
        section = "Project Context"
    )

    private fun retrievalResult(entry: MarkdownMemoryEntry): MemoryRetrievalResult = MemoryRetrievalResult(
        chunkId = "${MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME}#${entry.id}#0",
        entryId = entry.id,
        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
        text = entry.text,
        type = entry.type,
        sensitivity = entry.sensitivity,
        source = entry.source,
        contentHash = "hash-${entry.id}",
        lexicalScore = 10f,
        fusedScore = 10f,
        updatedAt = entry.updatedAt
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
        fun fileStoreRoot() = fileStore.ensureStore().getOrThrow().rootDirectory

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
    AFTER_BATCH_COMPLETION,
    AFTER_SOURCE_JOB_COMPLETION
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

    override suspend fun afterSourceJobCompletion(jobId: String) {
        interruptAt(CommitInterruptionPoint.AFTER_SOURCE_JOB_COMPLETION)
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
