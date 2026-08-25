package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.entity.MemoryDailyEvidence
import cn.nabr.chatwithchat.data.database.entity.MemoryDailyEvidenceDecision
import cn.nabr.chatwithchat.data.database.entity.MemoryDistillationCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MemoryDailyDistillationScheduler(
    private val memoryFileStore: MemoryFileStore,
    private val markdownMemoryCodec: MarkdownMemoryCodec,
    private val recoveryDao: MemoryRecoveryDao,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val settingRepository: SettingRepository,
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer,
    private val activityLogger: MemoryActivityLogger = MemoryActivityLogger.None,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }
) {
    suspend fun ensurePlanningJobs(
        recordStandaloneDisposition: Boolean = true
    ): MemoryDailyDistillationPlanResult {
        if (!settingRepository.fetchMemoryEnabled()) {
            val result = MemoryDailyDistillationPlanResult(
                disposition = MemoryDailyDistillationPlanDisposition.SKIPPED,
                reason = MemoryDailyDistillationPlanReason.MEMORY_DISABLED
            )
            if (recordStandaloneDisposition) recordStandaloneDisposition(result)
            return result
        }
        val nextBatch = findNextBatch()
        var scheduledJobId: String? = null
        var scheduledCheckpointId: String? = null
        var completedBatchCount = 0
        var disposition = MemoryDailyDistillationPlanDisposition.NO_ELIGIBLE_INPUT
        var reason = MemoryDailyDistillationPlanReason.NO_ELIGIBLE_DAILY_FILE
        if (nextBatch != null) {
            completedBatchCount = nextBatch.completedBatchCount
            val checkpoint = nextBatch.checkpoint
            when {
                checkpoint == null -> {
                    val planJob = enqueueBatchPlan(nextBatch)
                    scheduledJobId = planJob.jobId
                    disposition = MemoryDailyDistillationPlanDisposition.WORK_SCHEDULED
                    reason = MemoryDailyDistillationPlanReason.PLANNER_JOB_SCHEDULED
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.REPAIR)
                }
                checkpoint.status == MemoryDistillationCheckpointStatus.PENDING &&
                    checkpoint.targetBaseHash == nextBatch.input.targetBaseHash -> {
                    val semanticJob = ensureSemanticJob(checkpoint, nextBatch.input)
                    scheduledJobId = semanticJob.jobId
                    scheduledCheckpointId = checkpoint.checkpointId
                    disposition = MemoryDailyDistillationPlanDisposition.WORK_SCHEDULED
                    reason = MemoryDailyDistillationPlanReason.SEMANTIC_JOB_SCHEDULED
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
                }
                checkpoint.status == MemoryDistillationCheckpointStatus.PENDING -> {
                    when (
                        maintenanceScheduler.markRecoveredTerminal(
                            checkpoint.semanticJobId,
                            MemoryDistillationCheckpointStatus.STALE_TARGET_BASE
                        )
                    ) {
                        MemoryRecoveredJobDisposition.ACTIVE -> {
                            scheduledJobId = checkpoint.semanticJobId
                            scheduledCheckpointId = checkpoint.checkpointId
                            disposition = MemoryDailyDistillationPlanDisposition.SKIPPED
                            reason = MemoryDailyDistillationPlanReason.ALREADY_ACTIVE
                        }
                        MemoryRecoveredJobDisposition.MISSING,
                        MemoryRecoveredJobDisposition.SUCCEEDED -> {
                            markCheckpointStale(checkpoint, MemoryDistillationCheckpointStatus.STALE_TARGET_BASE)
                            val replanned = replanCheckpoint(checkpoint, nextBatch.input)
                            val semanticJob = ensureSemanticJob(
                                replanned,
                                nextBatch.input.withCreatedAt(replanned.createdAt)
                            )
                            scheduledJobId = semanticJob.jobId
                            scheduledCheckpointId = replanned.checkpointId
                            disposition = MemoryDailyDistillationPlanDisposition.WORK_SCHEDULED
                            reason = MemoryDailyDistillationPlanReason.SEMANTIC_JOB_SCHEDULED
                            workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
                        }
                    }
                }
                checkpoint.status in MemoryDistillationCheckpointStatus.REPLANNABLE -> {
                    val replanned = replanCheckpoint(checkpoint, nextBatch.input)
                    val semanticJob = ensureSemanticJob(replanned, nextBatch.input.withCreatedAt(replanned.createdAt))
                    scheduledJobId = semanticJob.jobId
                    scheduledCheckpointId = replanned.checkpointId
                    disposition = MemoryDailyDistillationPlanDisposition.WORK_SCHEDULED
                    reason = MemoryDailyDistillationPlanReason.SEMANTIC_JOB_SCHEDULED
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
                }
                checkpoint.status == MemoryDistillationCheckpointStatus.PREPARED -> {
                    val semanticJob = ensureSemanticJob(checkpoint, nextBatch.input.withCreatedAt(checkpoint.createdAt))
                    scheduledJobId = semanticJob.jobId
                    scheduledCheckpointId = checkpoint.checkpointId
                    disposition = MemoryDailyDistillationPlanDisposition.WORK_SCHEDULED
                    reason = MemoryDailyDistillationPlanReason.SEMANTIC_JOB_SCHEDULED
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
                }
            }
        }
        val nextDailyPlanAt = ensureNextDailyWake()
        val result = MemoryDailyDistillationPlanResult(
            scheduledJobId = scheduledJobId,
            scheduledCheckpointId = scheduledCheckpointId,
            completedBatchCount = completedBatchCount,
            nextDailyPlanAt = nextDailyPlanAt,
            disposition = disposition,
            reason = reason
        )
        if (
            recordStandaloneDisposition &&
            result.disposition != MemoryDailyDistillationPlanDisposition.WORK_SCHEDULED
        ) {
            recordStandaloneDisposition(result)
        }
        return result
    }

    suspend fun processPlan(job: MemoryMaintenanceJob): MemoryDailyDistillationPlanResult {
        val payload = decodePlanPayload(job)
            ?: error("invalid_daily_distillation_plan_payload")
        if (!settingRepository.fetchMemoryEnabled()) {
            return MemoryDailyDistillationPlanResult(
                disposition = MemoryDailyDistillationPlanDisposition.SKIPPED,
                reason = MemoryDailyDistillationPlanReason.MEMORY_DISABLED
            )
        }
        if (payload.kind == MemoryDailyDistillationPlanKind.DAILY_WAKE) {
            return ensurePlanningJobs(recordStandaloneDisposition = false)
        }

        val nextBatch = findNextBatch()
        if (nextBatch == null || !nextBatch.matches(payload)) {
            val replanned = ensurePlanningJobs(recordStandaloneDisposition = false)
            return if (replanned.disposition == MemoryDailyDistillationPlanDisposition.WORK_SCHEDULED) {
                replanned
            } else {
                replanned.copy(
                    disposition = MemoryDailyDistillationPlanDisposition.SKIPPED,
                    reason = MemoryDailyDistillationPlanReason.PLAN_SUPERSEDED
                )
            }
        }
        val checkpoint = nextBatch.checkpoint ?: insertPendingCheckpoint(nextBatch.input)
        val semanticJob = ensureSemanticJob(checkpoint, nextBatch.input.withCreatedAt(checkpoint.createdAt))
        workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
        val nextDailyPlanAt = ensureNextDailyWake()
        return MemoryDailyDistillationPlanResult(
            scheduledJobId = semanticJob.jobId,
            scheduledCheckpointId = checkpoint.checkpointId,
            completedBatchCount = nextBatch.completedBatchCount,
            nextDailyPlanAt = nextDailyPlanAt,
            disposition = MemoryDailyDistillationPlanDisposition.WORK_SCHEDULED,
            reason = MemoryDailyDistillationPlanReason.SEMANTIC_JOB_SCHEDULED
        )
    }

    private suspend fun findNextBatch(): PlannedBatch? {
        val corpus = memoryFileStore.readCorpusFiles(MemoryCorpus.MAINTENANCE_WORKING_SET).getOrThrow()
        val longTerm = corpus.files.singleOrNull { file ->
            file.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
        } ?: return null
        val longTermMarkdown = String(longTerm.bytes, StandardCharsets.UTF_8)
        val targetBaseHash = longTerm.bytes.sha256Hex()
        val existingMemories = boundedExistingMemories(longTermMarkdown)
        val today = LocalDate.now(clock)
        val windowStart = today.minusDays(DAILY_WINDOW_DAYS.toLong())
        val windowEnd = today.minusDays(1)
        val dailyFiles = corpus.files
            .asSequence()
            .mapNotNull(::dailyFileOrNull)
            .filter { daily -> daily.date in windowStart..windowEnd }
            .sortedWith(compareBy<DailyFile> { daily -> daily.date }.thenBy { daily -> daily.sourcePath })
            .toList()
        if (dailyFiles.isEmpty()) return null

        val windowId = "daily_${windowStart}_${windowEnd}"
        val manifest = dailyFiles.map { daily ->
            MemoryDailyDistillationSourceManifest(
                sourcePath = daily.sourcePath,
                sourceDate = daily.date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                sourceHash = daily.sourceHash
            )
        }
        val manifestHash = json.encodeToString(manifest).sha256Utf8()
        val evidence = dailyFiles.flatMap { daily ->
            markdownMemoryCodec.parse(daily.markdown).entries.map { entry ->
                MemoryDailyEvidenceIdentity.fromEntry(entry, daily.sourcePath, daily.date, clock)
            }
        }.filter { item ->
            val ledger = recoveryDao.getDailyEvidence(item.evidenceKey, item.contentHash)
            ledger?.decision != MemoryDailyEvidenceDecision.PROMOTED && !(
                ledger?.decision == MemoryDailyEvidenceDecision.IGNORED && ledger.lastWindowId == windowId
                )
        }
        var completedBatchCount = 0
        splitEvidence(evidence).forEachIndexed { batchIndex, batchEvidence ->
            val batchKey = batchKey(batchIndex, batchEvidence)
            val checkpoint = recoveryDao.getDistillationCheckpoint(
                windowId = windowId,
                windowSourceManifestHash = manifestHash,
                batchKey = batchKey
            )
            if (checkpoint?.status == MemoryDistillationCheckpointStatus.COMPLETED) {
                completedBatchCount += 1
                return@forEachIndexed
            }
            val input = MemoryDailyDistillationFrozenInput(
                batchId = batchId(windowId, manifestHash, batchKey),
                batchKey = batchKey,
                windowId = windowId,
                windowStartDate = windowStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
                windowEndDate = windowEnd.format(DateTimeFormatter.ISO_LOCAL_DATE),
                windowSourceManifest = manifest,
                windowSourceManifestHash = manifestHash,
                dailyEvidence = batchEvidence,
                existingMemories = existingMemories,
                targetBaseHash = targetBaseHash,
                createdAt = now()
            )
            return PlannedBatch(input, checkpoint, completedBatchCount)
        }
        return null
    }

    private suspend fun enqueueBatchPlan(batch: PlannedBatch): MemoryMaintenanceJob {
        val input = batch.input
        var job = maintenanceScheduler.enqueue(
            type = MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION,
            idempotencyKey = "memory-daily-distillation-plan:${input.batchId}:${input.targetBaseHash}",
            payloadJson = json.encodeToString(
                MemoryDailyDistillationPlanJobPayload(
                    kind = MemoryDailyDistillationPlanKind.BATCH,
                    localDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE),
                    windowId = input.windowId,
                    windowSourceManifestHash = input.windowSourceManifestHash,
                    batchKey = input.batchKey,
                    targetBaseHash = input.targetBaseHash
                )
            )
        )
        if (
            job.status == MemoryMaintenanceJobStatus.DISMISSED &&
            job.lastError == "memory_disabled"
        ) {
            job = maintenanceScheduler.reviveDismissedDailyPlan(job.jobId) ?: job
        }
        if (job.status in ACTIVITY_RUN_STATUSES) {
            activityLogger.startScheduledRun(
                job = job,
                category = MemoryActivityCategory.MAINTENANCE_PLANNING,
                triggerReason = "daily_batch_discovered"
            )
        }
        return job
    }

    private suspend fun insertPendingCheckpoint(
        input: MemoryDailyDistillationFrozenInput
    ): MemoryDistillationCheckpoint {
        val checkpointId = checkpointId(input)
        val checkpoint = MemoryDistillationCheckpoint(
            checkpointId = checkpointId,
            windowId = input.windowId,
            windowStartDate = input.windowStartDate,
            windowEndDate = input.windowEndDate,
            windowSourceManifestJson = json.encodeToString(input.windowSourceManifest),
            windowSourceManifestHash = input.windowSourceManifestHash,
            batchKey = input.batchKey,
            semanticJobId = semanticJobId(checkpointId, input.targetBaseHash),
            targetSourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            targetBaseHash = input.targetBaseHash,
            targetSourceHash = input.targetBaseHash,
            mutationGroupId = null,
            status = MemoryDistillationCheckpointStatus.PENDING,
            createdAt = input.createdAt,
            updatedAt = input.createdAt,
            processedAt = null
        )
        val inserted = recoveryDao.insertDistillationCheckpointWithEvidence(
            checkpoint,
            input.dailyEvidence.map { evidence -> evidence.toLedger(now = input.createdAt) }
        )
        return if (inserted != -1L) {
            checkpoint
        } else {
            checkNotNull(
                recoveryDao.getDistillationCheckpoint(
                    input.windowId,
                    input.windowSourceManifestHash,
                    input.batchKey
                )
            )
        }
    }

    private suspend fun ensureSemanticJob(
        checkpoint: MemoryDistillationCheckpoint,
        input: MemoryDailyDistillationFrozenInput
    ): MemoryMaintenanceJob {
        val frozenInput = input.copy(
            targetBaseHash = checkpoint.targetBaseHash,
            createdAt = checkpoint.createdAt
        )
        val inputHash = json.encodeToString(frozenInput).sha256Utf8()
        val job = maintenanceScheduler.enqueue(
            type = MemoryMaintenanceJobType.DISTILL_DAILY_NOTES,
            idempotencyKey = "memory-daily-distillation:${checkpoint.checkpointId}:${checkpoint.targetBaseHash}",
            payloadJson = json.encodeToString(
                MemoryDailyDistillationJobPayload(
                    checkpointId = checkpoint.checkpointId,
                    input = frozenInput,
                    inputHash = inputHash
                )
            ),
            jobId = checkpoint.semanticJobId
        )
        val scheduledJob = if (
            job.status == MemoryMaintenanceJobStatus.DISMISSED &&
            job.lastError == "memory_disabled"
        ) {
            maintenanceScheduler.reviveDismissedDailyDistillation(job.jobId) ?: job
        } else {
            job
        }
        if (scheduledJob.status in ACTIVITY_RUN_STATUSES) {
            activityLogger.startScheduledRun(
                job = scheduledJob,
                category = MemoryActivityCategory.DAILY_DISTILLATION,
                triggerReason = "daily_distillation_planned",
                inputCount = input.dailyEvidence.size
            )
        }
        return scheduledJob
    }

    private suspend fun markCheckpointStale(
        checkpoint: MemoryDistillationCheckpoint,
        status: String
    ): MemoryDistillationCheckpoint {
        val changed = recoveryDao.transitionDistillationCheckpointCas(
            checkpointId = checkpoint.checkpointId,
            expectedStatus = checkpoint.status,
            expectedRowVersion = checkpoint.rowVersion,
            expectedWindowId = checkpoint.windowId,
            expectedWindowSourceManifestHash = checkpoint.windowSourceManifestHash,
            expectedBatchKey = checkpoint.batchKey,
            expectedSemanticJobId = checkpoint.semanticJobId,
            expectedTargetSourcePath = checkpoint.targetSourcePath,
            expectedTargetBaseHash = checkpoint.targetBaseHash,
            expectedTargetSourceHash = checkpoint.targetSourceHash,
            expectedMutationGroupId = checkpoint.mutationGroupId,
            newStatus = status,
            newTargetSourceHash = checkpoint.targetSourceHash,
            mutationGroupId = checkpoint.mutationGroupId,
            updatedAt = now(),
            processedAt = null
        )
        check(changed == 1) { "Distillation checkpoint changed before it could be marked stale" }
        return checkNotNull(
            recoveryDao.getDistillationCheckpoint(
                checkpoint.windowId,
                checkpoint.windowSourceManifestHash,
                checkpoint.batchKey
            )
        )
    }

    private suspend fun replanCheckpoint(
        original: MemoryDistillationCheckpoint,
        input: MemoryDailyDistillationFrozenInput
    ): MemoryDistillationCheckpoint {
        val checkpoint = recoveryDao.getDistillationCheckpoint(
            original.windowId,
            original.windowSourceManifestHash,
            original.batchKey
        ) ?: error("Distillation checkpoint disappeared before replanning")
        check(checkpoint.status in MemoryDistillationCheckpointStatus.REPLANNABLE)
        val newSemanticJobId = semanticJobId(checkpoint.checkpointId, input.targetBaseHash)
        val changed = recoveryDao.replanDistillationCheckpointCas(
            checkpointId = checkpoint.checkpointId,
            expectedStatus = checkpoint.status,
            expectedRowVersion = checkpoint.rowVersion,
            expectedWindowId = checkpoint.windowId,
            expectedWindowSourceManifestHash = checkpoint.windowSourceManifestHash,
            expectedBatchKey = checkpoint.batchKey,
            expectedSemanticJobId = checkpoint.semanticJobId,
            expectedTargetSourcePath = checkpoint.targetSourcePath,
            expectedTargetBaseHash = checkpoint.targetBaseHash,
            expectedTargetSourceHash = checkpoint.targetSourceHash,
            newSemanticJobId = newSemanticJobId,
            newTargetBaseHash = input.targetBaseHash,
            newStatus = MemoryDistillationCheckpointStatus.PENDING,
            updatedAt = now()
        )
        check(changed == 1) { "Distillation checkpoint changed before it could be replanned" }
        return checkNotNull(
            recoveryDao.getDistillationCheckpoint(
                checkpoint.windowId,
                checkpoint.windowSourceManifestHash,
                checkpoint.batchKey
            )
        )
    }


    private suspend fun ensureNextDailyWake(): Long {
        val nextDate = LocalDate.now(clock).plusDays(1)
        val nextRunAt = nextDate.atStartOfDay(clock.zone).toEpochSecond()
        var job = maintenanceScheduler.enqueue(
            type = MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION,
            idempotencyKey = "memory-daily-distillation-wake:$nextDate",
            payloadJson = json.encodeToString(
                MemoryDailyDistillationPlanJobPayload(
                    kind = MemoryDailyDistillationPlanKind.DAILY_WAKE,
                    localDate = nextDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                )
            ),
            nextRunAt = nextRunAt
        )
        if (
            job.status == MemoryMaintenanceJobStatus.DISMISSED &&
            job.lastError == "memory_disabled"
        ) {
            job = maintenanceScheduler.reviveDismissedDailyPlan(job.jobId) ?: job
        }
        if (job.status in ACTIVITY_RUN_STATUSES) {
            activityLogger.startScheduledRun(
                job = job,
                category = MemoryActivityCategory.MAINTENANCE_PLANNING,
                triggerReason = "daily_wake_scheduled"
            )
        }
        return nextRunAt
    }

    private suspend fun recordStandaloneDisposition(result: MemoryDailyDistillationPlanResult) {
        val status = when (result.disposition) {
            MemoryDailyDistillationPlanDisposition.NO_ELIGIBLE_INPUT -> MemoryActivityStatus.NO_OP
            MemoryDailyDistillationPlanDisposition.SKIPPED -> MemoryActivityStatus.SKIPPED
            else -> return
        }
        activityLogger.recordStandalonePlanningResult(
            jobType = MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION,
            triggerReason = "daily_planning_check",
            status = status,
            outcomeCode = result.reason
        )
    }

    private fun decodePlanPayload(job: MemoryMaintenanceJob): MemoryDailyDistillationPlanJobPayload? = try {
        check(job.type == MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION)
        json.decodeFromString<MemoryDailyDistillationPlanJobPayload>(job.payloadJson).also { payload ->
            check(payload.kind in setOf(MemoryDailyDistillationPlanKind.BATCH, MemoryDailyDistillationPlanKind.DAILY_WAKE))
            LocalDate.parse(payload.localDate, DateTimeFormatter.ISO_LOCAL_DATE)
            if (payload.kind == MemoryDailyDistillationPlanKind.BATCH) {
                check(!payload.windowId.isNullOrBlank())
                check(payload.windowSourceManifestHash?.matches(SHA_256_REGEX) == true)
                check(!payload.batchKey.isNullOrBlank())
                check(payload.targetBaseHash?.matches(SHA_256_REGEX) == true)
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private fun dailyFileOrNull(file: MemoryFileContent): DailyFile? {
        val match = DAILY_SOURCE_REGEX.matchEntire(file.sourcePath) ?: return null
        val date = runCatching { LocalDate.parse(match.groupValues[1], DateTimeFormatter.ISO_LOCAL_DATE) }
            .getOrNull() ?: return null
        return DailyFile(
            sourcePath = file.sourcePath,
            sourceHash = file.bytes.sha256Hex(),
            date = date,
            markdown = String(file.bytes, StandardCharsets.UTF_8)
        )
    }

    private fun boundedExistingMemories(markdown: String): List<MemoryBatchExistingMemory> {
        var usedChars = 0
        return markdownMemoryCodec.parse(markdown).entries
            .sortedWith(compareByDescending<MarkdownMemoryEntry> { entry -> entry.updatedAt }.thenBy { entry -> entry.id })
            .filter { entry ->
                if (usedChars + entry.text.length > MAX_EXISTING_MEMORY_CHARS) {
                    false
                } else {
                    usedChars += entry.text.length
                    true
                }
            }
            .take(MAX_EXISTING_MEMORIES)
            .map { entry ->
                MemoryBatchExistingMemory(
                    id = entry.id,
                    sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                    text = entry.text,
                    type = entry.type,
                    sensitivity = entry.sensitivity,
                    source = entry.source,
                    updatedAt = entry.updatedAt,
                    createdAt = entry.createdAt,
                    canonicalKey = entry.canonicalKey,
                    scope = entry.scope,
                    lastObservedAt = entry.lastObservedAt,
                    validity = entry.validity,
                    supersededBy = entry.supersededBy,
                    recallState = entry.recallState,
                    evidenceRefs = entry.evidenceRefs
                )
            }
    }

    private fun splitEvidence(
        evidence: List<MemoryDailyDistillationEvidence>
    ): List<List<MemoryDailyDistillationEvidence>> {
        val result = mutableListOf<List<MemoryDailyDistillationEvidence>>()
        var current = mutableListOf<MemoryDailyDistillationEvidence>()
        var currentChars = 0
        evidence.forEach { item ->
            if (
                current.isNotEmpty() &&
                (current.size >= MAX_EVIDENCE_PER_BATCH || currentChars + item.text.length > MAX_EVIDENCE_CHARS_PER_BATCH)
            ) {
                result += current
                current = mutableListOf()
                currentChars = 0
            }
            current += item
            currentChars += item.text.length
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun batchKey(
        batchIndex: Int,
        evidence: List<MemoryDailyDistillationEvidence>
    ): String = "batch_${batchIndex.toString().padStart(4, '0')}_${
        evidence.joinToString("|") { item -> "${item.evidenceKey}:${item.contentHash}" }.sha256Utf8().take(BATCH_HASH_LENGTH)
    }"

    private fun batchId(windowId: String, manifestHash: String, batchKey: String): String =
        "daily_${"$windowId|$manifestHash|$batchKey".sha256Utf8().take(ID_HASH_LENGTH)}"

    private fun checkpointId(input: MemoryDailyDistillationFrozenInput): String =
        "distill_${"${input.windowId}|${input.windowSourceManifestHash}|${input.batchKey}".sha256Utf8().take(ID_HASH_LENGTH)}"

    private fun semanticJobId(checkpointId: String, targetBaseHash: String): String =
        "distill_job_${"$checkpointId|$targetBaseHash".sha256Utf8().take(ID_HASH_LENGTH)}"

    private fun MemoryDailyDistillationFrozenInput.withCreatedAt(createdAt: Long) = copy(createdAt = createdAt)

    private fun PlannedBatch.matches(payload: MemoryDailyDistillationPlanJobPayload): Boolean =
        input.windowId == payload.windowId &&
            input.windowSourceManifestHash == payload.windowSourceManifestHash &&
            input.batchKey == payload.batchKey &&
            input.targetBaseHash == payload.targetBaseHash

    private fun MemoryDailyDistillationEvidence.toLedger(now: Long): MemoryDailyEvidence = MemoryDailyEvidence(
        evidenceKey = evidenceKey,
        contentHash = contentHash,
        sourcePath = sourcePath,
        sourceDate = sourceDate,
        decision = MemoryDailyEvidenceDecision.PENDING,
        lastWindowId = null,
        lastCheckpointId = null,
        pendingDecision = null,
        mutationGroupId = null,
        targetEntryId = null,
        createdAt = now,
        updatedAt = now,
        decidedAt = null
    )

    private fun now(): Long = clock.instant().epochSecond

    private data class DailyFile(
        val sourcePath: String,
        val sourceHash: String,
        val date: LocalDate,
        val markdown: String
    )

    private data class PlannedBatch(
        val input: MemoryDailyDistillationFrozenInput,
        val checkpoint: MemoryDistillationCheckpoint?,
        val completedBatchCount: Int
    )

    private companion object {
        const val ID_HASH_LENGTH = 24
        const val BATCH_HASH_LENGTH = 16
        const val MAX_EVIDENCE_PER_BATCH = 24
        const val MAX_EVIDENCE_CHARS_PER_BATCH = 12_000
        const val MAX_EXISTING_MEMORIES = 100
        const val MAX_EXISTING_MEMORY_CHARS = 32_000
        const val DAILY_WINDOW_DAYS = 14
        val ACTIVITY_RUN_STATUSES = setOf(
            MemoryMaintenanceJobStatus.PENDING,
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
            MemoryMaintenanceJobStatus.RUNNING
        )
        val DAILY_SOURCE_REGEX = Regex("memory/(\\d{4}-\\d{2}-\\d{2})\\.md")
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
    }
}
