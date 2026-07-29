package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryActivityLogDao
import cn.nabr.chatwithchat.data.database.dao.MemoryMaintenanceJobDao
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import java.time.Clock
import java.util.UUID

interface MemoryActivityLogger {
    suspend fun startRun(start: MemoryActivityRunStart): String = start.key.activityRunId

    suspend fun startStandaloneRun(start: MemoryActivityStandaloneRunStart): String = start.activityRunId

    suspend fun advancePhase(
        activityRunId: String,
        expectedPhase: String,
        nextPhase: String,
        data: MemoryActivityRunData = MemoryActivityRunData()
    ): Boolean = false

    suspend fun finishRun(
        activityRunId: String,
        expectedPhase: String,
        status: String,
        data: MemoryActivityRunData = MemoryActivityRunData()
    ): Boolean = false

    suspend fun reconcileJobRuns(): Int = 0

    suspend fun start(
        batchId: String,
        category: String,
        platformName: String? = null,
        modelName: String? = null,
        attempt: Int? = null,
        turnCount: Int? = null
    ): String

    suspend fun finish(
        logId: String,
        status: String,
        detail: String? = null,
        operationCount: Int? = null
    )

    data object None : MemoryActivityLogger {
        override suspend fun start(
            batchId: String,
            category: String,
            platformName: String?,
            modelName: String?,
            attempt: Int?,
            turnCount: Int?
        ): String = ""

        override suspend fun finish(logId: String, status: String, detail: String?, operationCount: Int?) = Unit
    }
}

class RoomMemoryActivityLogger(
    private val logDao: MemoryActivityLogDao,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val jobDao: MemoryMaintenanceJobDao? = null
) : MemoryActivityLogger {
    override suspend fun startRun(start: MemoryActivityRunStart): String {
        val now = now()
        val activityRunId = start.key.activityRunId
        logDao.insertRun(
            MemoryActivityLog(
                logId = activityRunId,
                batchId = start.key.jobId,
                category = start.category,
                status = start.initialStatus,
                platformName = start.data.platformName,
                modelName = start.data.modelName,
                attempt = start.key.attempt,
                retryCycle = start.key.retryCycle,
                turnCount = null,
                operationCount = start.data.operationCount,
                detail = null,
                startedAt = now,
                completedAt = null,
                updatedAt = now,
                jobId = start.key.jobId,
                jobType = start.jobType,
                phase = start.initialPhase,
                triggerReason = start.triggerReason,
                platformUid = start.data.platformUid,
                modelId = start.data.modelId,
                inputCount = start.data.inputCount,
                errorCode = null,
                phaseSummaryJson = MemoryActivityPhaseHistory.start(
                    phase = start.initialPhase,
                    status = start.initialStatus,
                    startedAt = now,
                    data = start.data
                ).encode(),
                rowVersion = 0
            )
        )
        val stored = checkNotNull(
            logDao.getRun(
                jobId = start.key.jobId,
                retryCycle = start.key.retryCycle,
                attempt = start.key.attempt
            )
        ) { "Memory activity run was not persisted" }
        check(stored.logId == activityRunId) { "Memory activity run identity is inconsistent" }
        check(stored.category == start.category && stored.jobType == start.jobType) {
            "Memory activity run key is already bound to another logical job"
        }
        return activityRunId
    }

    override suspend fun startStandaloneRun(start: MemoryActivityStandaloneRunStart): String {
        val now = now()
        logDao.insertRun(
            MemoryActivityLog(
                logId = start.activityRunId,
                batchId = start.batchId,
                category = start.category,
                status = start.initialStatus,
                platformName = null,
                modelName = null,
                attempt = null,
                retryCycle = 0,
                turnCount = null,
                operationCount = start.data.operationCount,
                detail = null,
                startedAt = now,
                completedAt = null,
                updatedAt = now,
                jobId = null,
                jobType = start.jobType,
                phase = start.initialPhase,
                triggerReason = start.triggerReason,
                platformUid = null,
                modelId = null,
                inputCount = start.data.inputCount,
                errorCode = null,
                phaseSummaryJson = MemoryActivityPhaseHistory.start(
                    phase = start.initialPhase,
                    status = start.initialStatus,
                    startedAt = now,
                    data = start.data
                ).encode(),
                rowVersion = 0
            )
        )
        val stored = checkNotNull(logDao.getById(start.activityRunId)) {
            "Standalone memory activity run was not persisted"
        }
        check(
            stored.isRun &&
                stored.category == start.category &&
                stored.jobType == start.jobType &&
                stored.batchId == start.batchId &&
                stored.jobId == null &&
                stored.attempt == null &&
                stored.retryCycle == 0 &&
                stored.triggerReason == start.triggerReason &&
                stored.platformUid == null &&
                stored.modelId == null &&
                stored.platformName == null &&
                stored.modelName == null
        ) {
            "Standalone memory activity run ID is already bound to another activity"
        }
        return start.activityRunId
    }

    override suspend fun advancePhase(
        activityRunId: String,
        expectedPhase: String,
        nextPhase: String,
        data: MemoryActivityRunData
    ): Boolean {
        if (activityRunId.isBlank()) return false
        require(MemoryActivityPhase.canAdvance(expectedPhase, nextPhase)) {
            "Memory activity phase cannot advance"
        }
        repeat(MAX_CAS_ATTEMPTS) {
            val current = logDao.getById(activityRunId) ?: return false
            if (!current.isRun || current.status in MemoryActivityStatus.TERMINAL) return false
            if (current.phase == nextPhase) return current.matchesRecordedData(expectedPhase, data)
            if (current.phase != expectedPhase) return false

            val transitionedAt = transitionTime(current)
            val history = current.requirePhaseHistory().advance(
                expectedPhase = expectedPhase,
                nextPhase = nextPhase,
                transitionedAt = transitionedAt,
                data = data
            )
            val merged = current.merge(data)
            val updated = logDao.advanceRun(
                activityRunId = activityRunId,
                expectedRowVersion = current.rowVersion,
                expectedPhase = expectedPhase,
                nextPhase = nextPhase,
                platformUid = merged.platformUid,
                modelId = merged.modelId,
                platformName = merged.platformName,
                modelName = merged.modelName,
                inputCount = merged.inputCount,
                operationCount = merged.operationCount,
                phaseSummaryJson = history.encode(),
                updatedAt = transitionedAt
            )
            if (updated == 1) return true
        }
        return false
    }

    override suspend fun finishRun(
        activityRunId: String,
        expectedPhase: String,
        status: String,
        data: MemoryActivityRunData
    ): Boolean {
        if (activityRunId.isBlank()) return false
        require(expectedPhase in MemoryActivityPhase.ALL) { "Unknown expected memory activity phase" }
        require(status in MemoryActivityStatus.TERMINAL) { "Memory activity run must finish with a terminal status" }
        require(status !in ERROR_STATUSES || data.errorCode != null) {
            "Failed or blocked memory activity run requires an error code"
        }
        repeat(MAX_CAS_ATTEMPTS) {
            val current = logDao.getById(activityRunId) ?: return false
            if (!current.isRun) return false
            if (current.status in MemoryActivityStatus.TERMINAL) {
                return current.matchesTerminalReplay(expectedPhase, status, data)
            }
            if (current.phase != expectedPhase) return false

            val completedAt = transitionTime(current)
            val history = current.requirePhaseHistory().finish(
                expectedPhase = expectedPhase,
                status = status,
                completedAt = completedAt,
                data = data
            )
            val merged = current.merge(data)
            val updated = logDao.finishRun(
                activityRunId = activityRunId,
                expectedRowVersion = current.rowVersion,
                expectedPhase = expectedPhase,
                status = status,
                platformUid = merged.platformUid,
                modelId = merged.modelId,
                platformName = merged.platformName,
                modelName = merged.modelName,
                inputCount = merged.inputCount,
                operationCount = merged.operationCount,
                errorCode = data.errorCode,
                phaseSummaryJson = history.encode(),
                completedAt = completedAt,
                updatedAt = completedAt
            )
            if (updated == 1) return true
        }
        return false
    }

    override suspend fun reconcileJobRuns(): Int {
        val maintenanceJobDao = jobDao ?: return 0
        var reconciledCount = 0
        logDao.getActiveJobRuns(RECONCILIATION_LIMIT)
            .asSequence()
            .forEach { row ->
                val disposition = reconciliationDisposition(
                    row = row,
                    job = maintenanceJobDao.getById(checkNotNull(row.jobId))
                ) ?: return@forEach
                if (finishCurrentRun(row.logId, disposition.status, disposition.errorCode)) {
                    reconciledCount += 1
                }
            }
        return reconciledCount
    }

    override suspend fun start(
        batchId: String,
        category: String,
        platformName: String?,
        modelName: String?,
        attempt: Int?,
        turnCount: Int?
    ): String {
        val now = now()
        val logId = UUID.randomUUID().toString()
        logDao.upsert(
            MemoryActivityLog(
                logId = logId,
                batchId = batchId,
                category = category,
                status = MemoryActivityStatus.RUNNING,
                platformName = platformName,
                modelName = modelName,
                attempt = attempt,
                turnCount = turnCount,
                operationCount = null,
                detail = null,
                startedAt = now,
                completedAt = null,
                updatedAt = now
            )
        )
        return logId
    }

    override suspend fun finish(logId: String, status: String, detail: String?, operationCount: Int?) {
        if (logId.isBlank()) return
        val now = now()
        logDao.finish(
            logId = logId,
            status = status,
            detail = detail?.take(MAX_DETAIL_LENGTH),
            operationCount = operationCount,
            completedAt = now,
            updatedAt = now
        )
    }

    private fun now(): Long = clock.instant().epochSecond

    private fun transitionTime(current: MemoryActivityLog): Long = maxOf(now(), current.startedAt, current.updatedAt)

    private fun MemoryActivityLog.requirePhaseHistory(): MemoryActivityPhaseHistory {
        val encoded = checkNotNull(phaseSummaryJson) { "Memory activity run is missing phase history" }
        val history = MemoryActivityPhaseHistory.decode(encoded)
        check(history.phases.lastOrNull()?.phase == phase) { "Memory activity run phase history is inconsistent" }
        return history
    }

    private fun MemoryActivityLog.merge(data: MemoryActivityRunData): MergedRunData = MergedRunData(
        platformUid = stableValue(platformUid, data.platformUid, "platform identity"),
        modelId = stableValue(modelId, data.modelId, "model identity"),
        platformName = stableValue(platformName, data.platformName, "platform name"),
        modelName = stableValue(modelName, data.modelName, "model name"),
        inputCount = stableValue(inputCount, data.inputCount, "input count"),
        operationCount = data.operationCount ?: operationCount
    )

    private fun MemoryActivityLog.matchesTerminalReplay(
        expectedPhase: String,
        status: String,
        data: MemoryActivityRunData
    ): Boolean = phase == expectedPhase &&
        this.status == status &&
        (data.platformUid == null || data.platformUid == platformUid) &&
        (data.modelId == null || data.modelId == modelId) &&
        (data.platformName == null || data.platformName == platformName) &&
        (data.modelName == null || data.modelName == modelName) &&
        (data.inputCount == null || data.inputCount == inputCount) &&
        (data.operationCount == null || data.operationCount == operationCount) &&
        (data.errorCode == null || data.errorCode == errorCode) &&
        matchesRecordedPhaseData(expectedPhase, data)

    private fun MemoryActivityLog.matchesRecordedData(
        expectedPhase: String,
        data: MemoryActivityRunData
    ): Boolean =
        (data.platformUid == null || data.platformUid == platformUid) &&
            (data.modelId == null || data.modelId == modelId) &&
            (data.platformName == null || data.platformName == platformName) &&
            (data.modelName == null || data.modelName == modelName) &&
            (data.inputCount == null || data.inputCount == inputCount) &&
            (data.operationCount == null || data.operationCount == operationCount) &&
            data.errorCode == null &&
            matchesRecordedPhaseData(expectedPhase, data)

    private fun MemoryActivityLog.matchesRecordedPhaseData(
        expectedPhase: String,
        data: MemoryActivityRunData
    ): Boolean {
        if (data.cursor == null && data.hashPrefix == null) return true
        val recordedPhase = phaseSummaryJson
            ?.let { encoded -> runCatching { MemoryActivityPhaseHistory.decode(encoded) }.getOrNull() }
            ?.phases
            ?.lastOrNull { summary -> summary.phase == expectedPhase }
            ?: return false
        return (data.cursor == null || data.cursor == recordedPhase.cursor) &&
            (data.hashPrefix == null || data.hashPrefix == recordedPhase.hashPrefix)
    }

    private fun <T> stableValue(current: T?, update: T?, field: String): T? {
        require(current == null || update == null || current == update) {
            "Memory activity $field cannot change after it is recorded"
        }
        return update ?: current
    }

    private suspend fun finishCurrentRun(
        activityRunId: String,
        status: String,
        errorCode: String?
    ): Boolean {
        repeat(MAX_CAS_ATTEMPTS) {
            val current = logDao.getById(activityRunId) ?: return false
            if (!current.isRun || current.status in MemoryActivityStatus.TERMINAL) return false
            val expectedPhase = current.phase ?: return false
            if (
                finishRun(
                    activityRunId = activityRunId,
                    expectedPhase = expectedPhase,
                    status = status,
                    data = MemoryActivityRunData(errorCode = errorCode)
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun reconciliationDisposition(
        row: MemoryActivityLog,
        job: MemoryMaintenanceJob?
    ): ReconciliationDisposition? {
        if (job == null) {
            return ReconciliationDisposition(MemoryActivityStatus.FAILED, ERROR_JOB_MISSING)
        }
        val isCurrentAttempt = row.retryCycle == job.retryCycle && row.attempt == job.attempts
        val isNextAttempt = row.retryCycle == job.retryCycle && row.attempt == job.attempts + 1
        return when (job.status) {
            MemoryMaintenanceJobStatus.RUNNING -> if (isCurrentAttempt) {
                null
            } else {
                ReconciliationDisposition(MemoryActivityStatus.FAILED, ERROR_ATTEMPT_SUPERSEDED)
            }
            MemoryMaintenanceJobStatus.PENDING -> if (isCurrentAttempt || isNextAttempt) {
                null
            } else {
                ReconciliationDisposition(MemoryActivityStatus.FAILED, ERROR_ATTEMPT_SUPERSEDED)
            }
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE -> if (isNextAttempt) {
                null
            } else {
                ReconciliationDisposition(MemoryActivityStatus.FAILED, ERROR_RETRY_SCHEDULED)
            }
            MemoryMaintenanceJobStatus.SUCCEEDED ->
                ReconciliationDisposition(MemoryActivityStatus.SUCCEEDED, null)
            MemoryMaintenanceJobStatus.DISMISSED ->
                ReconciliationDisposition(MemoryActivityStatus.SKIPPED, ERROR_JOB_DISMISSED)
            MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY ->
                ReconciliationDisposition(MemoryActivityStatus.BLOCKED, ERROR_JOB_BLOCKED)
            MemoryMaintenanceJobStatus.FAILED_TERMINAL,
            MemoryMaintenanceJobStatus.WAITING_REPAIR ->
                ReconciliationDisposition(MemoryActivityStatus.FAILED, ERROR_JOB_TERMINAL)
            else -> ReconciliationDisposition(MemoryActivityStatus.FAILED, ERROR_JOB_STATE_UNKNOWN)
        }
    }

    private val MemoryActivityLog.isRun: Boolean
        get() = category in MemoryActivityCategory.NEW_RUN_CATEGORIES && phase != null && phaseSummaryJson != null

    companion object {
        private const val MAX_DETAIL_LENGTH = 500
        private const val MAX_CAS_ATTEMPTS = 8
        private const val RECONCILIATION_LIMIT = 200
        private const val ERROR_ATTEMPT_SUPERSEDED = "job_attempt_superseded"
        private const val ERROR_JOB_BLOCKED = "job_blocked_dependency"
        private const val ERROR_JOB_DISMISSED = "job_dismissed"
        private const val ERROR_JOB_MISSING = "job_missing"
        private const val ERROR_JOB_STATE_UNKNOWN = "job_state_unknown"
        private const val ERROR_JOB_TERMINAL = "job_terminal"
        private const val ERROR_RETRY_SCHEDULED = "job_retry_scheduled"
        private val ERROR_STATUSES = setOf(MemoryActivityStatus.BLOCKED, MemoryActivityStatus.FAILED)
    }
}

private data class MergedRunData(
    val platformUid: String?,
    val modelId: String?,
    val platformName: String?,
    val modelName: String?,
    val inputCount: Int?,
    val operationCount: Int?
)

private data class ReconciliationDisposition(
    val status: String,
    val errorCode: String?
)
