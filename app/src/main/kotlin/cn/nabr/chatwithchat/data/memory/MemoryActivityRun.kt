package cn.nabr.chatwithchat.data.memory

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class MemoryActivityRunKey(
    val jobId: String,
    val retryCycle: Int,
    val attempt: Int
) {
    init {
        require(MemoryActivityStructuredValue.isOpaqueId(jobId)) {
            "Memory activity job ID is not a bounded opaque identifier"
        }
        require(retryCycle >= 0) { "Memory activity retry cycle must not be negative" }
        require(attempt > 0) { "Memory activity attempt must be positive" }
    }

    val activityRunId: String
        get() = UUID.nameUUIDFromBytes(
            "$IDENTITY_NAMESPACE|$jobId|$retryCycle|$attempt".toByteArray(StandardCharsets.UTF_8)
        ).toString()

    private companion object {
        const val IDENTITY_NAMESPACE = "chatwithchat-memory-activity-v1"
    }
}

data class MemoryActivityRunStart(
    val key: MemoryActivityRunKey,
    val category: String,
    val jobType: String,
    val initialPhase: String,
    val triggerReason: String? = null,
    val data: MemoryActivityRunData = MemoryActivityRunData()
) {
    init {
        require(category in MemoryActivityCategory.NEW_RUN_CATEGORIES) { "Unknown memory activity run category" }
        require(MemoryActivityStructuredValue.isCode(jobType)) { "Invalid memory activity job type" }
        require(triggerReason == null || MemoryActivityStructuredValue.isCode(triggerReason)) {
            "Invalid memory activity trigger reason"
        }
        require(initialPhase in MemoryActivityPhase.ALL) { "Unknown initial memory activity phase" }
        require(data.errorCode == null) { "A new memory activity run must not have an error code" }
        if (category == MemoryActivityCategory.MAINTENANCE_PLANNING) {
            require(initialPhase == MemoryActivityPhase.SCHEDULED || initialPhase == MemoryActivityPhase.PLANNING) {
                "Planner activity run must start scheduled or planning"
            }
            require(!data.hasModelSnapshot) { "Planner activity run must not have a model snapshot" }
        } else {
            require(initialPhase == MemoryActivityPhase.SCHEDULED || initialPhase == MemoryActivityPhase.MODEL_RESOLUTION) {
                "Semantic activity run must start scheduled or resolving its model"
            }
        }
    }

    val initialStatus: String
        get() = if (initialPhase == MemoryActivityPhase.SCHEDULED) {
            MemoryActivityStatus.SCHEDULED
        } else {
            MemoryActivityStatus.RUNNING
        }
}

data class MemoryActivityRunData(
    val platformUid: String? = null,
    val modelId: String? = null,
    val platformName: String? = null,
    val modelName: String? = null,
    val inputCount: Int? = null,
    val operationCount: Int? = null,
    val cursor: Int? = null,
    val hashPrefix: String? = null,
    val errorCode: String? = null
) {
    init {
        require((platformUid == null) == (modelId == null)) {
            "Memory activity model identity must be complete"
        }
        require(platformUid == null || MemoryActivityStructuredValue.isIdentity(platformUid)) {
            "Invalid memory activity platform identity"
        }
        require(modelId == null || MemoryActivityStructuredValue.isIdentity(modelId)) {
            "Invalid memory activity model identity"
        }
        require(platformName == null || MemoryActivityStructuredValue.isDisplayText(platformName)) {
            "Invalid memory activity platform name"
        }
        require(modelName == null || MemoryActivityStructuredValue.isDisplayText(modelName)) {
            "Invalid memory activity model name"
        }
        require(platformName == null || platformUid != null) {
            "Memory activity platform name requires a model identity"
        }
        require(modelName == null || modelId != null) {
            "Memory activity model name requires a model identity"
        }
        require(inputCount == null || inputCount >= 0) { "Memory activity input count must not be negative" }
        require(operationCount == null || operationCount >= 0) {
            "Memory activity operation count must not be negative"
        }
        require(cursor == null || cursor >= 0) { "Memory activity cursor must not be negative" }
        require(hashPrefix == null || MemoryActivityStructuredValue.isHashPrefix(hashPrefix)) {
            "Invalid memory activity hash prefix"
        }
        require(errorCode == null || MemoryActivityStructuredValue.isCode(errorCode)) {
            "Invalid memory activity error code"
        }
    }

    val hasModelSnapshot: Boolean
        get() = platformUid != null || modelId != null || platformName != null || modelName != null
}

@Serializable
data class MemoryActivityPhaseHistory(
    val version: Int = CURRENT_VERSION,
    val phases: List<MemoryActivityPhaseSummary> = emptyList()
) {
    init {
        require(version == CURRENT_VERSION) { "Unsupported memory activity phase history version" }
        require(phases.size <= MAX_PHASE_COUNT) { "Memory activity phase history is too large" }
        phases.zipWithNext().forEach { (current, next) ->
            require(MemoryActivityPhase.canAdvance(current.phase, next.phase)) {
                "Memory activity phase history is not monotonic"
            }
            require(current.status == MemoryActivityStatus.SUCCEEDED && current.completedAt != null) {
                "Only a succeeded phase can precede another phase"
            }
        }
    }

    fun encode(): String = STRICT_JSON.encodeToString(serializer(), this).also { encoded ->
        require(encoded.length <= MAX_SERIALIZED_LENGTH) { "Memory activity phase history is too large" }
    }

    fun advance(
        expectedPhase: String,
        nextPhase: String,
        transitionedAt: Long,
        data: MemoryActivityRunData = MemoryActivityRunData()
    ): MemoryActivityPhaseHistory {
        require(data.errorCode == null) { "A succeeded phase must not have an error code" }
        val current = phases.lastOrNull() ?: error("Memory activity phase history is empty")
        require(current.phase == expectedPhase) { "Memory activity phase does not match the expected phase" }
        require(MemoryActivityPhase.canAdvance(expectedPhase, nextPhase)) { "Memory activity phase cannot advance" }
        require(phases.size < MAX_PHASE_COUNT) { "Memory activity phase history is too large" }
        val completed = current.complete(MemoryActivityStatus.SUCCEEDED, transitionedAt, data)
        return copy(
            phases = phases.dropLast(1) + completed +
                MemoryActivityPhaseSummary(
                    phase = nextPhase,
                    status = MemoryActivityStatus.RUNNING,
                    startedAt = transitionedAt
                )
        )
    }

    fun finish(
        expectedPhase: String,
        status: String,
        completedAt: Long,
        data: MemoryActivityRunData = MemoryActivityRunData()
    ): MemoryActivityPhaseHistory {
        require(status in MemoryActivityStatus.TERMINAL) { "Memory activity run must finish with a terminal status" }
        val current = phases.lastOrNull() ?: error("Memory activity phase history is empty")
        require(current.phase == expectedPhase) { "Memory activity phase does not match the expected phase" }
        return copy(phases = phases.dropLast(1) + current.complete(status, completedAt, data))
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_PHASE_COUNT = 12

        private val STRICT_JSON = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
            isLenient = false
        }

        fun decode(value: String): MemoryActivityPhaseHistory {
            require(value.length <= MAX_SERIALIZED_LENGTH) { "Memory activity phase history is too large" }
            return STRICT_JSON.decodeFromString(serializer(), value)
        }

        fun start(
            phase: String,
            status: String,
            startedAt: Long,
            data: MemoryActivityRunData = MemoryActivityRunData()
        ): MemoryActivityPhaseHistory = MemoryActivityPhaseHistory(
            phases = listOf(
                MemoryActivityPhaseSummary(
                    phase = phase,
                    status = status,
                    startedAt = startedAt,
                    inputCount = data.inputCount,
                    operationCount = data.operationCount,
                    cursor = data.cursor,
                    hashPrefix = data.hashPrefix,
                    errorCode = data.errorCode
                )
            )
        )

        private const val MAX_SERIALIZED_LENGTH = 8_000
    }
}

data class MemoryActivityStandaloneRunStart(
    val activityRunId: String,
    val batchId: String,
    val category: String,
    val jobType: String? = null,
    val initialPhase: String,
    val triggerReason: String? = null,
    val data: MemoryActivityRunData = MemoryActivityRunData()
) {
    init {
        require(MemoryActivityStructuredValue.isOpaqueId(activityRunId)) {
            "Standalone memory activity run ID is not a bounded opaque identifier"
        }
        require(MemoryActivityStructuredValue.isOpaqueId(batchId)) {
            "Standalone memory activity batch ID is not a bounded opaque identifier"
        }
        require(category == MemoryActivityCategory.MAINTENANCE_PLANNING) {
            "Standalone memory activity run must be a planner diagnostic"
        }
        require(jobType == null || MemoryActivityStructuredValue.isCode(jobType)) {
            "Invalid standalone memory activity job type"
        }
        require(initialPhase == MemoryActivityPhase.SCHEDULED || initialPhase == MemoryActivityPhase.PLANNING) {
            "Standalone planner activity run must start scheduled or planning"
        }
        require(triggerReason == null || MemoryActivityStructuredValue.isCode(triggerReason)) {
            "Invalid standalone memory activity trigger reason"
        }
        require(!data.hasModelSnapshot) { "Standalone planner activity run must not have a model snapshot" }
        require(data.errorCode == null) { "A new memory activity run must not have an error code" }
    }

    val initialStatus: String
        get() = if (initialPhase == MemoryActivityPhase.SCHEDULED) {
            MemoryActivityStatus.SCHEDULED
        } else {
            MemoryActivityStatus.RUNNING
        }
}

@Serializable
data class MemoryActivityPhaseSummary(
    val phase: String,
    val status: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val inputCount: Int? = null,
    val operationCount: Int? = null,
    val cursor: Int? = null,
    val hashPrefix: String? = null,
    val errorCode: String? = null
) {
    init {
        require(phase in MemoryActivityPhase.ALL) { "Unknown memory activity phase" }
        require(status in MemoryActivityStatus.ALL) { "Unknown memory activity status" }
        require(startedAt >= 0) { "Memory activity phase start must not be negative" }
        require(completedAt == null || completedAt >= startedAt) { "Memory activity phase completion precedes its start" }
        require((status in MemoryActivityStatus.TERMINAL) == (completedAt != null)) {
            "Memory activity phase completion does not match its status"
        }
        require(inputCount == null || inputCount >= 0) { "Memory activity input count must not be negative" }
        require(operationCount == null || operationCount >= 0) { "Memory activity operation count must not be negative" }
        require(cursor == null || cursor >= 0) { "Memory activity cursor must not be negative" }
        require(hashPrefix == null || MemoryActivityStructuredValue.isHashPrefix(hashPrefix)) {
            "Invalid memory activity hash prefix"
        }
        require(errorCode == null || MemoryActivityStructuredValue.isCode(errorCode)) {
            "Invalid memory activity error code"
        }
    }

    internal fun complete(
        status: String,
        completedAt: Long,
        data: MemoryActivityRunData
    ): MemoryActivityPhaseSummary = copy(
        status = status,
        completedAt = completedAt,
        inputCount = data.inputCount ?: inputCount,
        operationCount = data.operationCount ?: operationCount,
        cursor = data.cursor ?: cursor,
        hashPrefix = data.hashPrefix ?: hashPrefix,
        errorCode = data.errorCode
    )
}

object MemoryActivityCategory {
    const val MAINTENANCE_PLANNING = "maintenance_planning"
    const val TURN_BATCH_CONSOLIDATION = "turn_batch_consolidation"
    const val DAILY_DISTILLATION = "daily_distillation"
    const val LONG_TERM_CONSOLIDATION = "long_term_consolidation"

    const val MODEL_CALL = "model_call"
    const val MEMORY_GENERATION = "memory_generation"
    const val MEMORY_ORGANIZATION = "memory_organization"

    val NEW_RUN_CATEGORIES = setOf(
        MAINTENANCE_PLANNING,
        TURN_BATCH_CONSOLIDATION,
        DAILY_DISTILLATION,
        LONG_TERM_CONSOLIDATION
    )

    val LEGACY_CATEGORIES = setOf(MODEL_CALL, MEMORY_GENERATION, MEMORY_ORGANIZATION)
    val ALL = NEW_RUN_CATEGORIES + LEGACY_CATEGORIES
}

object MemoryActivityStatus {
    const val SCHEDULED = "scheduled"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val NO_OP = "no_op"
    const val SKIPPED = "skipped"
    const val BLOCKED = "blocked"
    const val FAILED = "failed"

    val TERMINAL = setOf(SUCCEEDED, NO_OP, SKIPPED, BLOCKED, FAILED)
    val ALL = setOf(SCHEDULED, RUNNING) + TERMINAL
}

object MemoryActivityPhase {
    const val SCHEDULED = "scheduled"
    const val PLANNING = "planning"
    const val MODEL_RESOLUTION = "model_resolution"
    const val MODEL_CALL = "model_call"
    const val GENERATION = "generation"
    const val ORGANIZATION = "organization"

    val ALL = setOf(SCHEDULED, PLANNING, MODEL_RESOLUTION, MODEL_CALL, GENERATION, ORGANIZATION)

    private val NEXT = mapOf(
        SCHEDULED to setOf(PLANNING, MODEL_RESOLUTION),
        PLANNING to emptySet(),
        MODEL_RESOLUTION to setOf(MODEL_CALL, ORGANIZATION),
        MODEL_CALL to setOf(GENERATION),
        GENERATION to setOf(ORGANIZATION),
        ORGANIZATION to emptySet()
    )

    fun canAdvance(current: String, next: String): Boolean {
        require(current in ALL) { "Unknown current memory activity phase" }
        require(next in ALL) { "Unknown next memory activity phase" }
        return next in checkNotNull(NEXT[current])
    }
}

private object MemoryActivityStructuredValue {
    private val CODE = Regex("[a-z0-9][a-z0-9._:-]{0,99}")
    private val HASH_PREFIX = Regex("[a-f0-9]{8,16}")
    private val OPAQUE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")

    fun isCode(value: String): Boolean = CODE.matches(value)

    fun isHashPrefix(value: String): Boolean = HASH_PREFIX.matches(value)

    fun isOpaqueId(value: String): Boolean = OPAQUE_ID.matches(value)

    fun isIdentity(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_IDENTITY_LENGTH && value.none(Char::isISOControl)

    fun isDisplayText(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_DISPLAY_LENGTH && value.none(Char::isISOControl)

    private const val MAX_IDENTITY_LENGTH = 200
    private const val MAX_DISPLAY_LENGTH = 160
}
