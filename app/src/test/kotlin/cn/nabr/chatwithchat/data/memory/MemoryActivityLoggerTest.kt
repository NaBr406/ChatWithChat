package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryActivityLogDao
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryActivityLoggerTest {
    @Test
    fun `start replay keeps one deterministic run without regressing it`() = runBlocking {
        val dao = InMemoryActivityLogDao()
        val logger = RoomMemoryActivityLogger(dao, MutableActivityClock(100))
        val start = semanticStart()

        val firstId = logger.startRun(start)
        val replayId = logger.startRun(start)

        assertEquals(start.key.activityRunId, firstId)
        assertEquals(firstId, replayId)
        assertEquals(1, dao.rows.size)
        assertEquals(MemoryActivityStatus.RUNNING, dao.rows.single().status)
        assertEquals(MemoryActivityPhase.MODEL_RESOLUTION, dao.rows.single().phase)
        assertEquals(0L, dao.rows.single().rowVersion)
    }

    @Test
    fun `scheduled planner claim advances the same attempt row without a model`() = runBlocking {
        val dao = InMemoryActivityLogDao()
        val clock = MutableActivityClock(100)
        val logger = RoomMemoryActivityLogger(dao, clock)
        val start = MemoryActivityRunStart(
            key = MemoryActivityRunKey(jobId = "planner-job", retryCycle = 2, attempt = 1),
            category = MemoryActivityCategory.MAINTENANCE_PLANNING,
            jobType = "plan_daily_distillation",
            initialPhase = MemoryActivityPhase.SCHEDULED,
            triggerReason = "startup"
        )
        val activityRunId = logger.startRun(start)

        clock.setEpochSecond(101)
        assertTrue(
            logger.advancePhase(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.SCHEDULED,
                nextPhase = MemoryActivityPhase.PLANNING
            )
        )

        val row = dao.rows.single()
        assertEquals(activityRunId, row.logId)
        assertEquals(MemoryActivityStatus.RUNNING, row.status)
        assertEquals(MemoryActivityPhase.PLANNING, row.phase)
        assertNull(row.platformUid)
        assertNull(row.modelId)
        assertEquals(1L, row.rowVersion)
    }

    @Test
    fun `successful semantic phases update one row and retain bounded history`() = runBlocking {
        val dao = InMemoryActivityLogDao()
        val clock = MutableActivityClock(100)
        val logger = RoomMemoryActivityLogger(dao, clock)
        val activityRunId = logger.startRun(semanticStart(inputCount = 5))

        clock.setEpochSecond(101)
        assertTrue(
            logger.advancePhase(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION,
                nextPhase = MemoryActivityPhase.MODEL_CALL,
                data = modelData(inputCount = 5)
            )
        )
        clock.setEpochSecond(102)
        assertTrue(
            logger.advancePhase(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.MODEL_CALL,
                nextPhase = MemoryActivityPhase.GENERATION
            )
        )
        clock.setEpochSecond(103)
        assertTrue(
            logger.advancePhase(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.GENERATION,
                nextPhase = MemoryActivityPhase.ORGANIZATION
            )
        )
        clock.setEpochSecond(104)
        assertTrue(
            logger.finishRun(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.ORGANIZATION,
                status = MemoryActivityStatus.SUCCEEDED,
                data = MemoryActivityRunData(operationCount = 2, hashPrefix = "abcdef12")
            )
        )

        val row = dao.rows.single()
        val history = MemoryActivityPhaseHistory.decode(checkNotNull(row.phaseSummaryJson))
        assertEquals(1, dao.rows.size)
        assertEquals(MemoryActivityStatus.SUCCEEDED, row.status)
        assertEquals(MemoryActivityPhase.ORGANIZATION, row.phase)
        assertEquals("platform-1", row.platformUid)
        assertEquals("model-1", row.modelId)
        assertEquals(5, row.inputCount)
        assertEquals(2, row.operationCount)
        assertEquals(4L, row.rowVersion)
        assertEquals(
            listOf(
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityPhase.MODEL_CALL,
                MemoryActivityPhase.GENERATION,
                MemoryActivityPhase.ORGANIZATION
            ),
            history.phases.map { phase -> phase.phase }
        )
        assertTrue(history.phases.all { phase -> phase.status == MemoryActivityStatus.SUCCEEDED })
        assertEquals("abcdef12", history.phases.last().hashPrefix)
    }

    @Test
    fun `same transition replay is idempotent and an older phase cannot regress`() = runBlocking {
        val dao = InMemoryActivityLogDao()
        val logger = RoomMemoryActivityLogger(dao, MutableActivityClock(100))
        val activityRunId = logger.startRun(semanticStart())

        assertTrue(
            logger.advancePhase(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityPhase.MODEL_CALL
            )
        )
        val afterFirstAdvance = dao.rows.single()
        assertTrue(
            logger.advancePhase(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityPhase.MODEL_CALL
            )
        )
        assertEquals(afterFirstAdvance, dao.rows.single())

        assertTrue(
            logger.advancePhase(
                activityRunId,
                MemoryActivityPhase.MODEL_CALL,
                MemoryActivityPhase.GENERATION
            )
        )
        assertFalse(
            logger.advancePhase(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityPhase.MODEL_CALL
            )
        )
        assertEquals(MemoryActivityPhase.GENERATION, dao.rows.single().phase)
        assertEquals(2L, dao.rows.single().rowVersion)
    }

    @Test
    fun `replay with different structured phase data is rejected`() = runBlocking {
        val dao = InMemoryActivityLogDao()
        val logger = RoomMemoryActivityLogger(dao, MutableActivityClock(100))
        val activityRunId = logger.startRun(semanticStart())

        assertTrue(
            logger.advancePhase(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION,
                nextPhase = MemoryActivityPhase.MODEL_CALL,
                data = MemoryActivityRunData(cursor = 4, hashPrefix = "abcdef12")
            )
        )
        assertTrue(
            logger.advancePhase(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION,
                nextPhase = MemoryActivityPhase.MODEL_CALL,
                data = MemoryActivityRunData(cursor = 4, hashPrefix = "abcdef12")
            )
        )
        assertFalse(
            logger.advancePhase(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION,
                nextPhase = MemoryActivityPhase.MODEL_CALL,
                data = MemoryActivityRunData(cursor = 5, hashPrefix = "abcdef12")
            )
        )
        assertFalse(
            logger.advancePhase(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION,
                nextPhase = MemoryActivityPhase.MODEL_CALL,
                data = MemoryActivityRunData(cursor = 4, hashPrefix = "12345678")
            )
        )

        assertEquals(1L, dao.rows.single().rowVersion)
    }

    @Test
    fun `terminal replay is idempotent and late updates cannot overwrite it`() = runBlocking {
        val dao = InMemoryActivityLogDao()
        val logger = RoomMemoryActivityLogger(dao, MutableActivityClock(100))
        val activityRunId = logger.startRun(semanticStart())
        val failure = MemoryActivityRunData(errorCode = "provider_timeout")

        assertTrue(
            logger.finishRun(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityStatus.FAILED,
                failure
            )
        )
        val terminal = dao.rows.single()
        assertTrue(
            logger.finishRun(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityStatus.FAILED,
                failure
            )
        )
        assertFalse(
            logger.finishRun(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityStatus.SUCCEEDED
            )
        )
        assertFalse(
            logger.advancePhase(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityPhase.MODEL_CALL
            )
        )
        assertEquals(terminal, dao.rows.single())
        assertEquals("provider_timeout", dao.rows.single().errorCode)
    }

    @Test
    fun `CAS conflict reloads the latest row version before advancing`() = runBlocking {
        val dao = InMemoryActivityLogDao().apply { failNextAdvanceCas = true }
        val logger = RoomMemoryActivityLogger(dao, MutableActivityClock(100))
        val activityRunId = logger.startRun(semanticStart())

        assertTrue(
            logger.advancePhase(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityPhase.MODEL_CALL
            )
        )

        assertEquals(MemoryActivityPhase.MODEL_CALL, dao.rows.single().phase)
        assertEquals(2L, dao.rows.single().rowVersion)
    }

    @Test
    fun `standalone planner diagnostic survives replay without inventing a job or model`() = runBlocking {
        val dao = InMemoryActivityLogDao()
        val logger = RoomMemoryActivityLogger(dao, MutableActivityClock(100))
        val start = MemoryActivityStandaloneRunStart(
            activityRunId = "startup-planning-1",
            batchId = "startup-planning",
            category = MemoryActivityCategory.MAINTENANCE_PLANNING,
            initialPhase = MemoryActivityPhase.PLANNING,
            triggerReason = "startup"
        )

        assertEquals(start.activityRunId, logger.startStandaloneRun(start))
        assertTrue(
            logger.finishRun(
                activityRunId = start.activityRunId,
                expectedPhase = MemoryActivityPhase.PLANNING,
                status = MemoryActivityStatus.FAILED,
                data = MemoryActivityRunData(errorCode = "planning_failed")
            )
        )
        assertEquals(start.activityRunId, logger.startStandaloneRun(start))

        val row = dao.rows.single()
        assertNull(row.jobId)
        assertNull(row.jobType)
        assertNull(row.attempt)
        assertNull(row.platformUid)
        assertNull(row.modelId)
        assertEquals(MemoryActivityStatus.FAILED, row.status)
        assertEquals(1L, row.rowVersion)
    }

    @Test
    fun `reconciliation closes an expired attempt but preserves its scheduled retry`() = runBlocking {
        val activityDao = InMemoryActivityLogDao()
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(
                maintenanceJob(
                    status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
                    attempts = 1
                )
            )
        )
        val logger = RoomMemoryActivityLogger(activityDao, MutableActivityClock(100), jobDao)
        logger.startRun(semanticStart())
        logger.startRun(
            semanticStart().copy(
                key = MemoryActivityRunKey(jobId = "semantic-job", retryCycle = 0, attempt = 2),
                initialPhase = MemoryActivityPhase.SCHEDULED
            )
        )

        assertEquals(1, logger.reconcileJobRuns())

        val expired = checkNotNull(activityDao.getRun("semantic-job", retryCycle = 0, attempt = 1))
        val retry = checkNotNull(activityDao.getRun("semantic-job", retryCycle = 0, attempt = 2))
        assertEquals(MemoryActivityStatus.FAILED, expired.status)
        assertEquals("job_retry_scheduled", expired.errorCode)
        assertEquals(MemoryActivityStatus.SCHEDULED, retry.status)
        assertNull(retry.completedAt)
    }

    @Test
    fun `run input count is immutable across partition phase updates`() = runBlocking {
        val dao = InMemoryActivityLogDao()
        val logger = RoomMemoryActivityLogger(dao, MutableActivityClock(100))
        val activityRunId = logger.startRun(semanticStart(inputCount = 24))

        assertTrue(
            logger.advancePhase(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION,
                nextPhase = MemoryActivityPhase.MODEL_CALL,
                data = modelData()
            )
        )
        val conflictingUpdate = runCatching {
            logger.advancePhase(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.MODEL_CALL,
                nextPhase = MemoryActivityPhase.GENERATION,
                data = modelData(inputCount = 6)
            )
        }

        assertTrue(conflictingUpdate.isFailure)
        assertEquals(24, dao.rows.single().inputCount)
        assertEquals(MemoryActivityPhase.MODEL_CALL, dao.rows.single().phase)
    }

    private fun semanticStart(inputCount: Int? = null): MemoryActivityRunStart = MemoryActivityRunStart(
        key = MemoryActivityRunKey(jobId = "semantic-job", retryCycle = 0, attempt = 1),
        category = MemoryActivityCategory.TURN_BATCH_CONSOLIDATION,
        jobType = "consolidate_turn_batch",
        initialPhase = MemoryActivityPhase.MODEL_RESOLUTION,
        triggerReason = "turn_batch_ready",
        data = MemoryActivityRunData(inputCount = inputCount)
    )

    private fun modelData(inputCount: Int? = null): MemoryActivityRunData = MemoryActivityRunData(
        platformUid = "platform-1",
        modelId = "model-1",
        platformName = "Platform One",
        modelName = "Model One",
        inputCount = inputCount
    )

    private fun maintenanceJob(
        status: String,
        attempts: Int
    ): MemoryMaintenanceJob = MemoryMaintenanceJob(
        jobId = "semantic-job",
        type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
        status = status,
        idempotencyKey = "semantic-job-key",
        payloadJson = "{}",
        attempts = attempts,
        lastError = null,
        createdAt = 1,
        startedAt = 2,
        updatedAt = 3,
        nextRunAt = 4,
        family = MemoryMaintenanceJobFamily.SEMANTIC,
        leaseOwner = null,
        leaseExpiresAt = null
    )
}

private class MutableActivityClock(epochSecond: Long) : Clock() {
    private var currentInstant = Instant.ofEpochSecond(epochSecond)

    fun setEpochSecond(epochSecond: Long) {
        currentInstant = Instant.ofEpochSecond(epochSecond)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(currentInstant, zone)

    override fun instant(): Instant = currentInstant
}

internal class InMemoryActivityLogDao : MemoryActivityLogDao {
    val rows = mutableListOf<MemoryActivityLog>()
    var failNextAdvanceCas: Boolean = false

    override fun observeLatest(limit: Int): Flow<List<MemoryActivityLog>> = flowOf(
        rows.sortedWith(
            compareByDescending<MemoryActivityLog> { row -> row.startedAt }
                .thenByDescending { row -> row.logId }
        ).take(limit)
    )

    override suspend fun upsert(log: MemoryActivityLog) {
        val index = rows.indexOfFirst { row -> row.logId == log.logId }
        if (index >= 0) rows[index] = log else rows += log
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
        val index = activeIndex(activityRunId, expectedRowVersion, expectedPhase)
        if (index < 0) return 0
        if (failNextAdvanceCas) {
            failNextAdvanceCas = false
            rows[index] = rows[index].copy(rowVersion = rows[index].rowVersion + 1)
            return 0
        }
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
        val index = activeIndex(activityRunId, expectedRowVersion, expectedPhase)
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
        val originalSize = rows.size
        rows.removeAll { row -> row.startedAt < before }
        return originalSize - rows.size
    }

    private fun activeIndex(activityRunId: String, expectedRowVersion: Long, expectedPhase: String): Int =
        rows.indexOfFirst { row ->
            row.logId == activityRunId &&
                row.rowVersion == expectedRowVersion &&
                row.phase == expectedPhase &&
                row.status in setOf(
                    MemoryActivityStatus.SCHEDULED,
                    MemoryActivityStatus.RUNNING
                )
        }
}
