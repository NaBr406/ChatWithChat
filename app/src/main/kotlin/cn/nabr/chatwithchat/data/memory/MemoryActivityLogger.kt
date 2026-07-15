package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryActivityLogDao
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import java.time.Clock
import java.util.UUID

interface MemoryActivityLogger {
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
    private val clock: Clock = Clock.systemDefaultZone()
) : MemoryActivityLogger {
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

    companion object {
        private const val MAX_DETAIL_LENGTH = 500
    }
}

object MemoryActivityCategory {
    const val MODEL_CALL = "model_call"
    const val MEMORY_GENERATION = "memory_generation"
    const val MEMORY_ORGANIZATION = "memory_organization"
}

object MemoryActivityStatus {
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
}
