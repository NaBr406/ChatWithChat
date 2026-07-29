package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.InMemoryMemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.InMemoryMemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.dao.MemoryActivityLogDao
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.openai.response.Choice
import cn.nabr.chatwithchat.data.dto.openai.response.Delta
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesStreamEvent
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.network.UploadedProviderFile
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBatchConsolidationServiceTest {
    @Test
    fun `semantic attempt activity baseline reports one unified run at its real terminal phase`() = runBlocking {
        val scenarios = listOf(
            ActivityBaselineScenario(
                name = "model_failure",
                response = null,
                expectedStatus = MemoryActivityStatus.FAILED,
                expectedPhase = MemoryActivityPhase.MODEL_CALL,
                expectedErrorCode = "model_call_failed",
                expectedOperationCount = null
            ),
            ActivityBaselineScenario(
                name = "success",
                response = "{\"operations\":[]}",
                expectedStatus = MemoryActivityStatus.NO_OP,
                expectedPhase = MemoryActivityPhase.ORGANIZATION,
                expectedErrorCode = null,
                expectedOperationCount = 0
            ),
            ActivityBaselineScenario(
                name = "invalid_json",
                response = "{\"operations\":[],\"unexpected\":true}",
                expectedStatus = MemoryActivityStatus.FAILED,
                expectedPhase = MemoryActivityPhase.GENERATION,
                expectedErrorCode = "invalid_model_json",
                expectedOperationCount = null
            ),
            ActivityBaselineScenario(
                name = "organization_failure",
                response =
                "{\"operations\":[{\"destination\":\"long_term\",\"action\":\"replace\"," +
                    "\"targetMemoryId\":\"missing-memory\",\"text\":\"Replacement\",\"type\":\"stable_profile\"," +
                    "\"sensitivity\":\"normal\",\"source\":\"explicit_user_statement\"," +
                    "\"evidenceTurnKeys\":[\"chat:7:user:1\"],\"reason\":\"baseline\"}]}",
                expectedStatus = MemoryActivityStatus.FAILED,
                expectedPhase = MemoryActivityPhase.ORGANIZATION,
                expectedErrorCode = "invalid_consolidation_operations",
                expectedOperationCount = 1
            )
        )

        println(
            "memory-activity-baseline|scenario|categories|statuses|platforms|models|" +
                "dao_rows|ui_source_rows"
        )
        scenarios.forEach { scenario ->
            val fixture = activityBaselineFixture(scenario.response)
            try {
                val job = fixture.createFiveTurnBatch()
                fixture.service.process(job)
                val rows = fixture.activityLogDao.rows.toList()
                val uiSourceRows = fixture.activityLogDao.observeLatest().first()

                println(
                    listOf(
                        "memory-activity-baseline",
                        scenario.name,
                        rows.joinToString(",") { row -> row.category },
                        rows.joinToString(",") { row -> row.status },
                        rows.joinToString(",") { row -> row.platformName.orEmpty() },
                        rows.joinToString(",") { row -> row.modelName.orEmpty() },
                        rows.size,
                        uiSourceRows.size
                    ).joinToString("|")
                )
                val row = rows.single()
                val providerRequest = checkNotNull(fixture.openAIAPI.lastChatCompletionRequest).toString()
                assertTrue("Baseline question 1" in providerRequest)
                assertTrue("Baseline answer 1" in providerRequest)
                assertTrue("Consolidate one immutable batch" in providerRequest)
                assertEquals("activity-baseline-secret-token", fixture.openAIAPI.configuredToken)
                assertEquals(MemoryActivityCategory.TURN_BATCH_CONSOLIDATION, row.category)
                assertEquals(job.jobId, row.jobId)
                assertEquals(job.type, row.jobType)
                assertEquals(
                    MemoryActivityRunKey(job.jobId, job.retryCycle, job.attempts).activityRunId,
                    row.logId
                )
                assertEquals(scenario.expectedStatus, row.status)
                assertEquals(scenario.expectedPhase, row.phase)
                assertEquals(scenario.expectedErrorCode, row.errorCode)
                assertEquals("memory-baseline-platform", row.platformUid)
                assertEquals("memory-baseline-model", row.modelId)
                assertEquals("Memory baseline", row.platformName)
                assertEquals("memory-baseline-model", row.modelName)
                assertEquals(5, row.inputCount)
                assertEquals(scenario.expectedOperationCount, row.operationCount)
                assertEquals(job.attempts, row.attempt)
                assertEquals(job.retryCycle, row.retryCycle)
                val phaseHistory = MemoryActivityPhaseHistory.decode(checkNotNull(row.phaseSummaryJson))
                assertEquals(
                    when (scenario.expectedPhase) {
                        MemoryActivityPhase.MODEL_CALL -> listOf(
                            MemoryActivityPhase.MODEL_RESOLUTION,
                            MemoryActivityPhase.MODEL_CALL
                        )
                        MemoryActivityPhase.GENERATION -> listOf(
                            MemoryActivityPhase.MODEL_RESOLUTION,
                            MemoryActivityPhase.MODEL_CALL,
                            MemoryActivityPhase.GENERATION
                        )
                        else -> listOf(
                            MemoryActivityPhase.MODEL_RESOLUTION,
                            MemoryActivityPhase.MODEL_CALL,
                            MemoryActivityPhase.GENERATION,
                            MemoryActivityPhase.ORGANIZATION
                        )
                    },
                    phaseHistory.phases.map { phase -> phase.phase }
                )
                assertEquals(scenario.expectedStatus, phaseHistory.phases.last().status)
                assertEquals(scenario.expectedErrorCode, phaseHistory.phases.last().errorCode)
                assertEquals(1, rows.size)
                assertEquals(rows.map(MemoryActivityLog::logId).toSet(), uiSourceRows.map(MemoryActivityLog::logId).toSet())
                assertEquals(1, uiSourceRows.size)
                val persistedRow = row.toString()
                listOfNotNull(
                    "Baseline question 1",
                    "Baseline answer 1",
                    "Consolidate one immutable batch",
                    "activity-baseline-secret-token",
                    scenario.response
                ).forEach { sensitiveBody ->
                    assertFalse("Activity row leaked fixture content: $sensitiveBody", sensitiveBody in persistedRow)
                }
            } finally {
                fixture.close()
            }
        }
    }

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

        assertEquals(result.reason, MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
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

        assertEquals(result.reason, MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
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
    fun `replace matching an unscoped hidden entry converges to one canonical survivor`() = runBlocking {
        val replacementTarget = longTermEntry("mem_replacement_target", "An obsolete project detail.")
        val canonicalOnlyEntry = longTermEntry("mem_canonical_only", "The canonical project detail.")
        val replacement = operation(
            destination = MemoryBatchDestination.LONG_TERM,
            action = MemoryBatchAction.REPLACE,
            targetMemoryId = replacementTarget.id,
            text = "  THE canonical\u3000project detail.  "
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(replacement)
            ),
            retrievalResults = listOf(retrievalResult(replacementTarget))
        )
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(replacementTarget, canonicalOnlyEntry))
        ).getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)
        val entries = MarkdownMemoryCodec().parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries
        val active = entries.single { entry -> entry.validity == MemoryValidity.CURRENT }
        val history = entries.single { entry -> entry.id == replacementTarget.id }

        assertEquals(result.reason, MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(canonicalOnlyEntry.id, active.id)
        assertEquals(replacement.canonicalKey, active.canonicalKey)
        assertEquals(replacement.scope, active.scope)
        assertEquals(MemoryValidity.OBSOLETE, history.validity)
        assertEquals(active.id, history.supersededBy)
        assertEquals(
            1,
            entries.count { entry ->
                normalizeExactMemoryText(entry.text) == normalizeExactMemoryText(canonicalOnlyEntry.text)
            }
        )
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
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
    fun `replace uses the full file to converge hidden historical duplicates`() = runBlocking {
        val firstDuplicate = longTermEntry("mem_duplicate_one", "A historical duplicate remains visible.")
        val secondDuplicate = longTermEntry(
            "mem_duplicate_two",
            "\u00a0A historical\u3000duplicate remains visible.  "
        )
        val replacementTarget = longTermEntry("mem_unique_target", "A unique target before replacement.")
        val replacement = operation(
            destination = MemoryBatchDestination.LONG_TERM,
            action = MemoryBatchAction.REPLACE,
            targetMemoryId = replacementTarget.id,
            text = "  A HISTORICAL duplicate\nremains visible.  "
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(replacement)
            ),
            retrievalResults = listOf(retrievalResult(replacementTarget))
        )
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(firstDuplicate, secondDuplicate, replacementTarget))
        ).getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)
        val entries = MarkdownMemoryCodec().parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries
        val active = entries.single { entry -> entry.validity == MemoryValidity.CURRENT }
        val replacedTarget = entries.single { entry -> entry.id == replacementTarget.id }

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, result.longTermWriteCount)
        assertEquals(
            1,
            entries.count { entry ->
                normalizeExactMemoryText(entry.text) == normalizeExactMemoryText(firstDuplicate.text)
            }
        )
        assertEquals(replacement.canonicalKey, active.canonicalKey)
        assertEquals(replacement.scope, active.scope)
        assertEquals(MemoryValidity.OBSOLETE, replacedTarget.validity)
        assertEquals(active.id, replacedTarget.supersededBy)
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertTrue(fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId) != null)
    }

    @Test
    fun `create matching canonical identity and text is a replayable byte identical no-op`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val proposed = operation(
            destination = MemoryBatchDestination.LONG_TERM,
            text = "\u00a0THE user prefers\u3000café\nanswers.  ",
            type = "communication_style"
        )
        val existingEntry = MarkdownMemoryEntry(
            id = "mem_existing_exact",
            text = "The user prefers CAFÉ answers.",
            type = "communication_style",
            sensitivity = MemorySensitivity.PRIVATE,
            source = MemorySource.USER_CONFIRMED,
            chatId = 3,
            createdAt = 20L,
            updatedAt = 30L,
            section = "Stable Preferences",
            canonicalKey = proposed.canonicalKey,
            scope = requireNotNull(proposed.scope),
            lastObservedAt = 30L,
            recallState = requireNotNull(proposed.recallState),
            evidenceRefs = proposed.evidenceTurnKeys
        )
        val fixture = fixture(
            MemoryBatchConsolidationProposal(
                operations = listOf(proposed)
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
    fun `same fact observation commits metadata without scheduling index work`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val existing = longTermEntry("mem_observed", "The user prefers concise project updates.").copy(
            canonicalKey = "communication.project_update_style",
            scope = MemoryScope.GENERAL,
            lastObservedAt = 10L,
            recallState = MemoryRecallState.QUERY,
            evidenceRefs = listOf("chat:$CHAT_ID:user:0")
        )
        val observation = operation(
            destination = MemoryBatchDestination.LONG_TERM,
            action = MemoryBatchAction.REPLACE,
            targetMemoryId = existing.id,
            text = existing.text,
            canonicalKey = checkNotNull(existing.canonicalKey),
            scope = existing.scope,
            evidenceAt = 11L
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(listOf(observation)),
            retrievalResults = listOf(retrievalResult(existing))
        )
        fixture.fileStore.replaceLongTermMemory(codec.renderLongTerm(listOf(existing))).getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)
        val observed = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries.single()
        val group = checkNotNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId))
        val receipt = fixture.recoveryDao.getMutationReceipts(group.groupId).single()

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, result.longTermWriteCount)
        assertEquals(existing.id, observed.id)
        assertEquals(existing.text, observed.text)
        assertEquals(existing.createdAt, observed.createdAt)
        assertEquals(existing.updatedAt, observed.updatedAt)
        assertEquals(11L, observed.lastObservedAt)
        assertEquals(listOf("chat:$CHAT_ID:user:0", "chat:$CHAT_ID:user:1"), observed.evidenceRefs)
        assertEquals(null, receipt.targetIndexFingerprint)
        assertEquals(MemoryMutationState.INDEXED, receipt.state)
        assertEquals(MemoryMutationState.INDEXED, group.state)
        assertEquals(
            null,
            fixture.recoveryDao.getCorpusState(MemoryCorpus.CHAT_RECALL_LONG_TERM.name.lowercase())
        )
        assertTrue(fixture.jobDao.jobs.none { queued -> queued.family == MemoryMaintenanceJobFamily.INDEX })
        val enqueuedWorks = (fixture.workEnqueuer as RecordingWorkEnqueuer).works
        assertTrue(enqueuedWorks.none { work -> work.family == MemoryMaintenanceJobFamily.INDEX })
    }

    @Test
    fun `newer assistant inferred batch candidate cannot replace user confirmed current fact`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val existing = longTermEntry("mem_confirmed", "The user prefers concise project updates.").copy(
            source = MemorySource.USER_CONFIRMED,
            canonicalKey = "communication.project_update_style",
            scope = MemoryScope.GENERAL,
            lastObservedAt = 10L,
            recallState = MemoryRecallState.QUERY
        )
        val weaker = operation(
            destination = MemoryBatchDestination.LONG_TERM,
            action = MemoryBatchAction.REPLACE,
            targetMemoryId = existing.id,
            text = "The user prefers detailed project updates.",
            source = MemorySource.ASSISTANT_INFERRED,
            evidenceTurnKeys = listOf("chat:$CHAT_ID:user:5"),
            canonicalKey = checkNotNull(existing.canonicalKey),
            scope = existing.scope,
            evidenceAt = 15L
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(listOf(weaker)),
            retrievalResults = listOf(retrievalResult(existing))
        )
        fixture.fileStore.replaceLongTermMemory(codec.renderLongTerm(listOf(existing))).getOrThrow()
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)
        val group = checkNotNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId))

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(0, result.longTermWriteCount)
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(0, group.expectedReceiptCount)
        assertTrue(fixture.recoveryDao.getMutationReceipts(group.groupId).isEmpty())
    }

    @Test
    fun `create before replace relocates canonical text with stable canonical id`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val original = longTermEntry("mem_create_before_replace", "A canonical fact must survive relocation.")
        val create = operation(
            destination = MemoryBatchDestination.LONG_TERM,
            text = "  A CANONICAL fact must survive\nrelocation.  "
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    create,
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

        val result = fixture.service.process(job)
        val entries = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries
        val relocated = entries.single { entry ->
            normalizeExactMemoryText(entry.text) == normalizeExactMemoryText(original.text)
        }

        assertEquals(result.reason, MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.longTermWriteCount)
        assertTrue(relocated.id.startsWith("mem_can_"))
        assertEquals(create.canonicalKey, relocated.canonicalKey)
        assertEquals(create.scope, relocated.scope)
        assertEquals(
            "The original entry now records a replacement fact.",
            entries.single { entry -> entry.id == original.id }.text
        )
        assertEquals(5, fixture.turnDao.getCheckpoint(CHAT_ID)?.lastProcessedUserMessageId)
    }

    @Test
    fun `replace before create produces the same stable canonical identity`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val original = longTermEntry("mem_replace_before_create", "A reverse-order fact must survive relocation.")
        val create = operation(
            destination = MemoryBatchDestination.LONG_TERM,
            text = "  A REVERSE-ORDER fact must survive\nrelocation.  "
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        action = MemoryBatchAction.REPLACE,
                        targetMemoryId = original.id,
                        text = "The reverse-order target now records a replacement fact."
                    ),
                    create
                )
            ),
            retrievalResults = listOf(retrievalResult(original))
        )
        fixture.fileStore.replaceLongTermMemory(codec.renderLongTerm(listOf(original))).getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)
        val entries = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries
        val relocated = entries.single { entry ->
            normalizeExactMemoryText(entry.text) == normalizeExactMemoryText(original.text)
        }

        assertEquals(result.reason, MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.longTermWriteCount)
        assertTrue(relocated.id.startsWith("mem_can_"))
        assertEquals(create.canonicalKey, relocated.canonicalKey)
        assertEquals(create.scope, relocated.scope)
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
        val create = operation(
            destination = MemoryBatchDestination.LONG_TERM,
            text = "A historical canonical duplicate."
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    create,
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

        val result = fixture.service.process(job)
        val entries = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries
        val duplicateEntries = entries.filter { entry ->
            normalizeExactMemoryText(entry.text) == normalizeExactMemoryText(first.text)
        }

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.longTermWriteCount)
        assertEquals(2, duplicateEntries.size)
        val relocatedActive = duplicateEntries.single { entry -> entry.validity == MemoryValidity.CURRENT }
        val preservedHistory = duplicateEntries.single { entry -> entry.validity == MemoryValidity.OBSOLETE }
        assertEquals(second.id, relocatedActive.id)
        assertEquals(create.canonicalKey, relocatedActive.canonicalKey)
        assertEquals(first.id, preservedHistory.supersededBy)
        assertEquals(MemoryRecallState.MAINTENANCE_ONLY, preservedHistory.recallState)
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

        assertEquals(result.reason, MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.longTermWriteCount)
        assertEquals(second.text, entriesById.getValue(first.id).text)
        assertEquals(first.text, entriesById.getValue(second.id).text)
    }

    @Test
    fun `create and remove relocate canonical text instead of deleting it`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val original = longTermEntry("mem_create_remove", "A removed entry must be recreated by the paired create.")
        val create = operation(
            destination = MemoryBatchDestination.LONG_TERM,
            text = "  A REMOVED entry must be recreated\nby the paired create.  "
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(
                    create,
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

        val result = fixture.service.process(job)
        val entries = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.longTermWriteCount)
        assertEquals(1, entries.size)
        assertTrue(entries.single().id.startsWith("mem_can_"))
        assertEquals(create.canonicalKey, entries.single().canonicalKey)
        assertEquals(create.scope, entries.single().scope)
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
    fun `canonical contract rejects model supplied evidence time`() = runBlocking {
        val fixture = fixture(
            MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "Evidence time must come from the cited turn.",
                        evidenceAt = 12L
                    )
                )
            )
        )
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertTrue(result.reason.orEmpty().startsWith("invalid_consolidation_operations:"))
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(0, fixture.turnDao.getCheckpoint(CHAT_ID)!!.lastProcessedUserMessageId)
        assertEquals(5, fixture.turnDao.getTurnsClaimedByJob(job.jobId).size)
    }

    @Test
    fun `malformed canonical base writes nothing and keeps claimed checkpoint pending`() = runBlocking {
        val fixture = fixture(
            MemoryBatchConsolidationProposal(
                operations = listOf(
                    operation(
                        destination = MemoryBatchDestination.LONG_TERM,
                        text = "A valid write must not bypass malformed canonical metadata."
                    )
                )
            )
        )
        val malformedMarkdown = """
            # ChatWithChat Memory

            ## Projects

            <!-- memory:id=malformed id=duplicate type=project_context sensitivity=normal source=explicit_user_statement -->
            - This malformed entry must not be rewritten.
        """.trimIndent() + "\n"
        fixture.fileStore.replaceLongTermMemory(malformedMarkdown).getOrThrow()
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)

        assertEquals(MemoryBatchProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals("memory_render_failed:unsafe_memory_metadata", result.reason)
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
    fun `replace through obsolete id updates the current survivor and preserves history`() = runBlocking {
        val successorEntry = MarkdownMemoryEntry(
            id = "mem_project_current",
            text = "Question project has a current canonical status.",
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 11L,
            updatedAt = 11L,
            section = "Project Context",
            canonicalKey = "project.question.status",
            scope = "project:question",
            lastObservedAt = 11L,
            recallState = MemoryRecallState.QUERY
        )
        val existingEntry = MarkdownMemoryEntry(
            id = "mem_project",
            text = "Question project is at the first milestone.",
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 10L,
            updatedAt = 10L,
            section = "Project Context",
            canonicalKey = successorEntry.canonicalKey,
            scope = successorEntry.scope,
            lastObservedAt = 12L,
            validity = MemoryValidity.OBSOLETE,
            supersededBy = successorEntry.id,
            recallState = MemoryRecallState.MAINTENANCE_ONLY,
            evidenceRefs = listOf("chat:7:user:1"),
            extraMetadata = mapOf("future_schema" to "v2")
        )
        val retrievalResult = MemoryRetrievalResult(
            chunkId = "MEMORY.md#mem_project#0",
            entryId = existingEntry.id,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            text = existingEntry.text,
            type = existingEntry.type,
            sensitivity = existingEntry.sensitivity,
            source = existingEntry.source,
            embeddingContentHash = "hash-MEMORY.md#mem_project#0",
            lexicalScore = 10f,
            fusedScore = 10f,
            updatedAt = existingEntry.updatedAt
        )
        val replacement = operation(
            destination = MemoryBatchDestination.LONG_TERM,
            action = MemoryBatchAction.REPLACE,
            targetMemoryId = existingEntry.id,
            text = "Question project has reached the second milestone.",
            type = "project_context",
            evidenceTurnKeys = listOf("chat:$CHAT_ID:user:2"),
            canonicalKey = "project.question.status",
            scope = "project:question",
            evidenceAt = 12L
        )
        val fixture = fixture(
            proposal = MemoryBatchConsolidationProposal(
                operations = listOf(replacement)
            ),
            retrievalResults = listOf(retrievalResult)
        )
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(existingEntry, successorEntry))
        ).getOrThrow()
        val job = fixture.createFiveTurnBatch()

        val result = fixture.service.process(job)
        val markdown = fixture.fileStore.readLongTermMemory().getOrThrow()
        val parsed = MarkdownMemoryCodec().parse(markdown)
        val active = parsed.entries.single { entry -> entry.validity == MemoryValidity.CURRENT }
        val preservedHistory = parsed.entries.single { entry -> entry.id == existingEntry.id }

        assertEquals(MemoryBatchProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, markdown.split("first milestone").size - 1)
        assertEquals(1, markdown.split("second milestone").size - 1)
        assertTrue(parsed.skippedEntries.isEmpty())
        assertEquals(
            successorEntry.copy(
                text = "Question project has reached the second milestone.",
                updatedAt = FIXED_CLOCK.instant().epochSecond,
                lastObservedAt = 12L,
                evidenceRefs = listOf("chat:$CHAT_ID:user:2")
            ),
            active
        )
        assertEquals(existingEntry, preservedHistory)
        assertEquals(1, parsed.entries.count { entry -> entry.text == successorEntry.text })
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
                        evidenceTurnKeys = listOf(turnKey),
                        evidenceAt = if (job.type == MemoryMaintenanceJobType.APPEND_DAILY_NOTE) 100L else 101L
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

    private fun activityBaselineFixture(response: String?): ActivityBaselineFixture {
        val turnDao = InMemoryMemoryTurnBatchDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val enqueuer = RecordingWorkEnqueuer()
        val platform = PlatformV2(
            uid = "memory-baseline-platform",
            name = "Memory baseline",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://memory-baseline.invalid",
            token = "activity-baseline-secret-token",
            model = "memory-baseline-model",
            enabled = true
        )
        val settingRepository = FakeMaintenanceSettingRepository(
            memoryEnabled = true,
            platforms = listOf(platform)
        )
        val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
        val turnBatchScheduler = MemoryTurnBatchScheduler(
            turnBatchDao = turnDao,
            maintenanceJobDao = jobDao,
            maintenanceScheduler = maintenanceScheduler,
            workEnqueuer = enqueuer,
            settingRepository = settingRepository,
            clock = FIXED_CLOCK
        )
        val tempRoot = Files.createTempDirectory("memory-activity-baseline").toFile()
        val fileStore = MemoryFileStore(MemoryFilePaths(tempRoot), FIXED_CLOCK)
        fileStore.ensureStore().getOrThrow()
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val mutationCoordinator = MemoryMutationCoordinator(
            recoveryDao = recoveryDao,
            memoryFileStore = fileStore,
            maintenanceScheduler = maintenanceScheduler,
            workEnqueuer = enqueuer,
            clock = FIXED_CLOCK
        )
        val activityLogDao = BaselineMemoryActivityLogDao()
        val activityLogger = RoomMemoryActivityLogger(activityLogDao, FIXED_CLOCK)
        val openAIAPI = BaselineMemoryOpenAIAPI(response)
        val intelligence = LlmMemoryIntelligence(
            openAIAPI = openAIAPI,
            anthropicAPI = testProxy<AnthropicAPI>(),
            googleAPI = testProxy<GoogleAPI>(),
            activityLogger = activityLogger
        )
        val maintenanceCorpusReader = object : MemoryMaintenanceCorpusReader {
            override suspend fun retrieveWorkingSet(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> =
                Result.success(emptyList())
        }
        val coordinator = MemoryTurnBatchCoordinator(turnDao, turnBatchScheduler)
        return ActivityBaselineFixture(
            turnDao = turnDao,
            jobDao = jobDao,
            maintenanceScheduler = maintenanceScheduler,
            coordinator = coordinator,
            activityLogDao = activityLogDao,
            openAIAPI = openAIAPI,
            tempRoot = tempRoot,
            service = MemoryBatchConsolidationService(
                turnBatchDao = turnDao,
                maintenanceScheduler = maintenanceScheduler,
                turnBatchScheduler = turnBatchScheduler,
                settingRepository = settingRepository,
                modelResolver = MemoryModelResolver(settingRepository),
                memoryIntelligence = intelligence,
                memoryFileStore = fileStore,
                markdownMemoryCodec = MarkdownMemoryCodec(),
                memoryMaintenanceCorpusReader = maintenanceCorpusReader,
                memoryMutationCoordinator = mutationCoordinator,
                activityLogger = activityLogger,
                clock = FIXED_CLOCK
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> testProxy(): T {
        val handler = InvocationHandler { _, method, _ ->
            when {
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java), handler) as T
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
        val platform = PlatformV2(
            uid = "memory-batch-platform",
            name = "Memory batch",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://memory-batch.invalid",
            token = "token",
            model = "memory-batch-model",
            enabled = true
        )
        val settingRepository = FakeMaintenanceSettingRepository(
            memoryEnabled = true,
            platforms = listOf(platform)
        )
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
            workEnqueuer = enqueuer,
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
                modelResolver = MemoryModelResolver(settingRepository),
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
        type: String = "project_context",
        source: String = MemorySource.EXPLICIT_USER_STATEMENT,
        evidenceTurnKeys: List<String> = listOf("chat:$CHAT_ID:user:1"),
        canonicalKey: String? = if (action in setOf(MemoryBatchAction.CREATE, MemoryBatchAction.REPLACE)) {
            "test.${sha256(text).take(16)}"
        } else {
            null
        },
        scope: String? = canonicalKey?.let { MemoryScope.GENERAL },
        evidenceAt: Long? = canonicalKey?.let { 11L },
        recallState: String? = canonicalKey?.let { MemoryRecallState.QUERY }
    ): MemoryBatchOperation = MemoryBatchOperation(
        destination = destination,
        action = action,
        targetMemoryId = targetMemoryId,
        text = text,
        type = type,
        sensitivity = MemorySensitivity.NORMAL,
        source = source,
        evidenceTurnKeys = evidenceTurnKeys,
        canonicalKey = canonicalKey,
        scope = scope,
        evidenceAt = evidenceAt,
        recallState = recallState,
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
        embeddingContentHash = "hash-${entry.id}",
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

    private data class ActivityBaselineScenario(
        val name: String,
        val response: String?,
        val expectedStatus: String,
        val expectedPhase: String,
        val expectedErrorCode: String?,
        val expectedOperationCount: Int?
    )

    private data class ActivityBaselineFixture(
        val turnDao: InMemoryMemoryTurnBatchDao,
        val jobDao: InMemoryMaintenanceJobDao,
        val maintenanceScheduler: MemoryMaintenanceScheduler,
        val coordinator: MemoryTurnBatchCoordinator,
        val activityLogDao: BaselineMemoryActivityLogDao,
        val openAIAPI: BaselineMemoryOpenAIAPI,
        val tempRoot: File,
        val service: MemoryBatchConsolidationService
    ) {
        suspend fun createFiveTurnBatch(): MemoryMaintenanceJob {
            (1..5).forEach { userMessageId ->
                coordinator.recordCompletedTurn(
                    MemoryCompletedTurnInput(
                        chatRoom = ChatRoomV2(
                            id = CHAT_ID,
                            title = "Activity baseline",
                            enabledPlatform = listOf("memory-baseline-platform")
                        ),
                        userMessage = MessageV2(
                            id = userMessageId,
                            chatId = CHAT_ID,
                            content = "Baseline question $userMessageId",
                            platformType = null,
                            createdAt = userMessageId.toLong()
                        ),
                        assistantMessages = listOf(
                            MessageV2(
                                id = userMessageId + 100,
                                chatId = CHAT_ID,
                                content = "Baseline answer $userMessageId",
                                platformType = "memory-baseline-platform"
                            )
                        ),
                        preferredPlatformUid = "memory-baseline-platform",
                        stablePlatformOrder = listOf("memory-baseline-platform"),
                        completedAt = userMessageId.toLong() + 10L
                    )
                )
            }
            return checkNotNull(
                maintenanceScheduler.claimNextRunnable(
                    family = jobDao.jobs.single().family,
                    leaseOwner = "activity-baseline"
                )
            )
        }

        fun close() {
            tempRoot.deleteRecursively()
        }
    }

    private data class Fixture(
        val turnDao: InMemoryMemoryTurnBatchDao,
        val jobDao: InMemoryMaintenanceJobDao,
        val fileStore: MemoryFileStore,
        val recoveryDao: InMemoryMemoryRecoveryDao,
        val workEnqueuer: MemoryMaintenanceWorkEnqueuer,
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
    private val phasesByRunId = mutableMapOf<String, String>()

    override suspend fun startRun(start: MemoryActivityRunStart): String = start.key.activityRunId.also { activityRunId ->
        phasesByRunId.putIfAbsent(activityRunId, start.initialPhase)
    }

    override suspend fun advancePhase(
        activityRunId: String,
        expectedPhase: String,
        nextPhase: String,
        data: MemoryActivityRunData
    ): Boolean {
        val recordedPhase = phasesByRunId[activityRunId]
        val fakeIntelligenceSkippedModelPhases =
            recordedPhase == MemoryActivityPhase.MODEL_RESOLUTION &&
                expectedPhase == MemoryActivityPhase.GENERATION &&
                nextPhase == MemoryActivityPhase.ORGANIZATION
        if (recordedPhase != expectedPhase && !fakeIntelligenceSkippedModelPhases) return false
        phasesByRunId[activityRunId] = nextPhase
        return true
    }

    override suspend fun finishRun(
        activityRunId: String,
        expectedPhase: String,
        status: String,
        data: MemoryActivityRunData
    ): Boolean {
        if (phasesByRunId[activityRunId] != expectedPhase) return false
        lastStatus = status
        return true
    }

    override suspend fun start(
        batchId: String,
        category: String,
        platformName: String?,
        modelName: String?,
        attempt: Int?,
        turnCount: Int?
    ): String = error("Legacy activity rows are not expected")

    override suspend fun finish(logId: String, status: String, detail: String?, operationCount: Int?) =
        error("Legacy activity rows are not expected")
}

private class BaselineMemoryActivityLogDao : MemoryActivityLogDao {
    val rows = mutableListOf<MemoryActivityLog>()

    override fun observeLatest(limit: Int): Flow<List<MemoryActivityLog>> = flowOf(
        rows.sortedWith(
            compareByDescending<MemoryActivityLog> { row -> row.startedAt }
                .thenByDescending { row -> row.logId }
        ).take(limit)
    )

    override suspend fun upsert(log: MemoryActivityLog) {
        val index = rows.indexOfFirst { row -> row.logId == log.logId }
        if (index >= 0) {
            rows[index] = log
        } else {
            rows += log
        }
    }

    override suspend fun insertRun(log: MemoryActivityLog): Long {
        val conflicts = rows.any { row ->
            row.logId == log.logId ||
                (
                    log.jobId != null &&
                        log.attempt != null &&
                        row.jobId == log.jobId &&
                        row.retryCycle == log.retryCycle &&
                        row.attempt == log.attempt
                    )
        }
        if (conflicts) return -1
        rows += log
        return rows.size.toLong()
    }

    override suspend fun getById(activityRunId: String): MemoryActivityLog? =
        rows.firstOrNull { row -> row.logId == activityRunId }

    override suspend fun getActiveJobRuns(limit: Int): List<MemoryActivityLog> = rows
        .filter { row ->
            row.jobId != null &&
                row.phase != null &&
                row.status in setOf(MemoryActivityStatus.SCHEDULED, MemoryActivityStatus.RUNNING)
        }
        .sortedWith(compareBy<MemoryActivityLog> { row -> row.startedAt }.thenBy { row -> row.logId })
        .take(limit)

    override suspend fun getRun(jobId: String, retryCycle: Int, attempt: Int): MemoryActivityLog? =
        rows.firstOrNull { row ->
            row.jobId == jobId && row.retryCycle == retryCycle && row.attempt == attempt
        }

    override suspend fun advanceRun(
        activityRunId: String,
        expectedRowVersion: Long,
        expectedPhase: String,
        nextPhase: String,
        platformUid: String?,
        modelId: String?,
        platformName: String?,
        modelName: String?,
        inputCount: Int?,
        operationCount: Int?,
        phaseSummaryJson: String,
        updatedAt: Long
    ): Int {
        val index = rows.indexOfFirst { row ->
            row.logId == activityRunId &&
                row.rowVersion == expectedRowVersion &&
                row.phase == expectedPhase &&
                row.status in setOf(
                    MemoryActivityStatus.SCHEDULED,
                    MemoryActivityStatus.RUNNING
                )
        }
        if (index < 0) return 0
        rows[index] = rows[index].copy(
            status = MemoryActivityStatus.RUNNING,
            phase = nextPhase,
            platformUid = platformUid,
            modelId = modelId,
            platformName = platformName,
            modelName = modelName,
            inputCount = inputCount,
            operationCount = operationCount,
            errorCode = null,
            phaseSummaryJson = phaseSummaryJson,
            updatedAt = updatedAt,
            rowVersion = rows[index].rowVersion + 1
        )
        return 1
    }

    override suspend fun finishRun(
        activityRunId: String,
        expectedRowVersion: Long,
        expectedPhase: String,
        status: String,
        platformUid: String?,
        modelId: String?,
        platformName: String?,
        modelName: String?,
        inputCount: Int?,
        operationCount: Int?,
        errorCode: String?,
        phaseSummaryJson: String,
        completedAt: Long,
        updatedAt: Long
    ): Int {
        val index = rows.indexOfFirst { row ->
            row.logId == activityRunId &&
                row.rowVersion == expectedRowVersion &&
                row.phase == expectedPhase &&
                row.status in setOf(
                    MemoryActivityStatus.SCHEDULED,
                    MemoryActivityStatus.RUNNING
                )
        }
        if (index < 0) return 0
        rows[index] = rows[index].copy(
            status = status,
            platformUid = platformUid,
            modelId = modelId,
            platformName = platformName,
            modelName = modelName,
            inputCount = inputCount,
            operationCount = operationCount,
            errorCode = errorCode,
            phaseSummaryJson = phaseSummaryJson,
            completedAt = completedAt,
            updatedAt = updatedAt,
            rowVersion = rows[index].rowVersion + 1
        )
        return 1
    }

    override suspend fun finish(
        logId: String,
        status: String,
        detail: String?,
        operationCount: Int?,
        completedAt: Long,
        updatedAt: Long
    ) {
        val index = rows.indexOfFirst { row -> row.logId == logId }
        if (index < 0) return
        rows[index] = rows[index].copy(
            status = status,
            detail = detail,
            operationCount = operationCount,
            completedAt = completedAt,
            updatedAt = updatedAt
        )
    }

    override suspend fun deleteOlderThan(before: Long): Int {
        val previousSize = rows.size
        rows.removeAll { row -> row.startedAt < before }
        return previousSize - rows.size
    }
}

private class BaselineMemoryOpenAIAPI(
    private val response: String?
) : OpenAIAPI {
    var configuredToken: String? = null
        private set
    var lastChatCompletionRequest: ChatCompletionRequest? = null
        private set

    override fun setToken(token: String?) {
        configuredToken = token
    }

    override fun setAPIUrl(url: String) = Unit

    override fun streamChatCompletion(
        request: ChatCompletionRequest,
        timeoutSeconds: Int
    ): Flow<ChatCompletionChunk> {
        lastChatCompletionRequest = request
        val content = response ?: return emptyFlow()
        return flowOf(
            ChatCompletionChunk(
                choices = listOf(Choice(index = 0, delta = Delta(content = content)))
            )
        )
    }

    override fun streamResponses(request: ResponsesRequest, timeoutSeconds: Int): Flow<ResponsesStreamEvent> =
        emptyFlow()

    override suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String
    ): UploadedProviderFile = error("Not used")

    override suspend fun isFileAvailable(fileId: String): Boolean = false
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
