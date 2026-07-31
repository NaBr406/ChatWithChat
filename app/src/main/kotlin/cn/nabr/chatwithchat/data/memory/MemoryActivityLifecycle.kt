package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import java.nio.charset.StandardCharsets
import java.util.UUID

internal suspend fun MemoryActivityLogger.startSemanticRun(
    job: MemoryMaintenanceJob,
    category: String,
    triggerReason: String? = null,
    inputCount: Int? = null
): String {
    require(job.status == MemoryMaintenanceJobStatus.RUNNING) { "Memory activity requires a claimed job" }
    val key = MemoryActivityRunKey(
        jobId = job.jobId,
        retryCycle = job.retryCycle,
        attempt = job.attempts
    )
    val activityRunId = key.activityRunId
    runCatching {
        startRun(
            MemoryActivityRunStart(
                key = key,
                category = category,
                jobType = job.type,
                initialPhase = MemoryActivityPhase.MODEL_RESOLUTION,
                triggerReason = triggerReason,
                data = MemoryActivityRunData(inputCount = inputCount)
            )
        )
        advancePhase(
            activityRunId = activityRunId,
            expectedPhase = MemoryActivityPhase.SCHEDULED,
            nextPhase = MemoryActivityPhase.MODEL_RESOLUTION,
            data = MemoryActivityRunData(inputCount = inputCount)
        )
    }
    return activityRunId
}

internal suspend fun MemoryActivityLogger.startPlannerRun(
    job: MemoryMaintenanceJob,
    triggerReason: String? = null
): String {
    require(job.status == MemoryMaintenanceJobStatus.RUNNING) { "Planner activity requires a claimed job" }
    val key = MemoryActivityRunKey(
        jobId = job.jobId,
        retryCycle = job.retryCycle,
        attempt = job.attempts
    )
    val activityRunId = key.activityRunId
    runCatching {
        startRun(
            MemoryActivityRunStart(
                key = key,
                category = MemoryActivityCategory.MAINTENANCE_PLANNING,
                jobType = job.type,
                initialPhase = MemoryActivityPhase.PLANNING,
                triggerReason = triggerReason
            )
        )
        advancePhase(
            activityRunId = activityRunId,
            expectedPhase = MemoryActivityPhase.SCHEDULED,
            nextPhase = MemoryActivityPhase.PLANNING
        )
    }
    return activityRunId
}

internal suspend fun MemoryActivityLogger.startScheduledRun(
    job: MemoryMaintenanceJob,
    category: String,
    triggerReason: String? = null,
    inputCount: Int? = null
): String {
    val attempt = if (job.status == MemoryMaintenanceJobStatus.RUNNING) job.attempts else job.attempts + 1
    val key = MemoryActivityRunKey(job.jobId, job.retryCycle, attempt)
    return runCatching {
        startRun(
            MemoryActivityRunStart(
                key = key,
                category = category,
                jobType = job.type,
                initialPhase = MemoryActivityPhase.SCHEDULED,
                triggerReason = triggerReason,
                data = MemoryActivityRunData(inputCount = inputCount)
            )
        )
    }.getOrDefault(key.activityRunId)
}

internal suspend fun MemoryActivityLogger.advanceRunSafely(
    activityRunId: String,
    expectedPhase: String,
    nextPhase: String,
    data: MemoryActivityRunData = MemoryActivityRunData()
): Boolean = runCatching {
    advancePhase(activityRunId, expectedPhase, nextPhase, data)
}.getOrDefault(false)

internal suspend fun MemoryActivityLogger.finishRunSafely(
    activityRunId: String,
    status: String,
    data: MemoryActivityRunData,
    expectedPhase: String
): Boolean = runCatching {
    finishRun(activityRunId, expectedPhase, status, data)
}.getOrDefault(false)

internal suspend fun MemoryActivityLogger.recordStandalonePlanningResult(
    jobType: String?,
    triggerReason: String,
    status: String,
    outcomeCode: String
): String {
    val activityRunId = standalonePlanningRunId(jobType, triggerReason, status, outcomeCode)
    runCatching {
        startStandaloneRun(
            MemoryActivityStandaloneRunStart(
                activityRunId = activityRunId,
                batchId = activityRunId,
                category = MemoryActivityCategory.MAINTENANCE_PLANNING,
                jobType = jobType,
                initialPhase = MemoryActivityPhase.PLANNING,
                triggerReason = triggerReason
            )
        )
        finishRun(
            activityRunId = activityRunId,
            expectedPhase = MemoryActivityPhase.PLANNING,
            status = status,
            data = MemoryActivityRunData(errorCode = outcomeCode)
        )
    }
    return activityRunId
}

private fun standalonePlanningRunId(
    jobType: String?,
    triggerReason: String,
    status: String,
    outcomeCode: String
): String = UUID.nameUUIDFromBytes(
    listOf(
        STANDALONE_IDENTITY_NAMESPACE,
        jobType.orEmpty(),
        triggerReason,
        status,
        outcomeCode
    ).joinToString("|").toByteArray(StandardCharsets.UTF_8)
).toString()

internal fun PlatformV2.toMemoryActivityData(
    inputCount: Int? = null,
    operationCount: Int? = null,
    cursor: Int? = null,
    hashPrefix: String? = null,
    errorCode: String? = null,
    errorDetail: String? = null
): MemoryActivityRunData = MemoryActivityRunData(
    platformUid = uid,
    modelId = model,
    platformName = name,
    modelName = model,
    inputCount = inputCount,
    operationCount = operationCount,
    cursor = cursor,
    hashPrefix = hashPrefix,
    errorCode = errorCode,
    errorDetail = errorDetail
)

private const val STANDALONE_IDENTITY_NAMESPACE = "chatwithchat-memory-standalone-activity-v1"
