package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDistillationCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
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
    private val clock: Clock = Clock.systemDefaultZone(),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }
) {
    suspend fun ensurePlanningJobs(): MemoryDailyDistillationPlanResult {
        if (!settingRepository.fetchMemoryEnabled()) return MemoryDailyDistillationPlanResult()
        val nextBatch = findNextBatch()
        var scheduledJobId: String? = null
        var scheduledCheckpointId: String? = null
        var completedBatchCount = 0
        if (nextBatch != null) {
            completedBatchCount = nextBatch.completedBatchCount
            val checkpoint = nextBatch.checkpoint
            when {
                checkpoint == null -> {
                    val planJob = enqueueBatchPlan(nextBatch)
                    scheduledJobId = planJob.jobId
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.REPAIR)
                }
                checkpoint.status == MemoryDistillationCheckpointStatus.PENDING &&
                    checkpoint.targetBaseHash == nextBatch.input.targetBaseHash -> {
                    val semanticJob = ensureSemanticJob(checkpoint, nextBatch.input)
                    scheduledJobId = semanticJob.jobId
                    scheduledCheckpointId = checkpoint.checkpointId
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
                            workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
                        }
                    }
                }
                checkpoint.status in MemoryDistillationCheckpointStatus.REPLANNABLE -> {
                    val replanned = replanCheckpoint(checkpoint, nextBatch.input)
                    val semanticJob = ensureSemanticJob(replanned, nextBatch.input.withCreatedAt(replanned.createdAt))
                    scheduledJobId = semanticJob.jobId
                    scheduledCheckpointId = replanned.checkpointId
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
                }
                checkpoint.status == MemoryDistillationCheckpointStatus.PREPARED -> {
                    val semanticJob = ensureSemanticJob(checkpoint, nextBatch.input.withCreatedAt(checkpoint.createdAt))
                    scheduledJobId = semanticJob.jobId
                    scheduledCheckpointId = checkpoint.checkpointId
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
                }
            }
        }
        val nextDailyPlanAt = ensureNextDailyWake()
        return MemoryDailyDistillationPlanResult(
            scheduledJobId = scheduledJobId,
            scheduledCheckpointId = scheduledCheckpointId,
            completedBatchCount = completedBatchCount,
            nextDailyPlanAt = nextDailyPlanAt
        )
    }

    suspend fun processPlan(job: MemoryMaintenanceJob): MemoryDailyDistillationPlanResult {
        val payload = decodePlanPayload(job)
            ?: error("invalid_daily_distillation_plan_payload")
        if (!settingRepository.fetchMemoryEnabled()) return MemoryDailyDistillationPlanResult()
        if (payload.kind == MemoryDailyDistillationPlanKind.DAILY_WAKE) {
            return ensurePlanningJobs()
        }

        val nextBatch = findNextBatch()
        if (nextBatch == null || !nextBatch.matches(payload)) {
            return ensurePlanningJobs()
        }
        val checkpoint = nextBatch.checkpoint ?: insertPendingCheckpoint(nextBatch.input)
        val semanticJob = ensureSemanticJob(checkpoint, nextBatch.input.withCreatedAt(checkpoint.createdAt))
        workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
        val nextDailyPlanAt = ensureNextDailyWake()
        return MemoryDailyDistillationPlanResult(
            scheduledJobId = semanticJob.jobId,
            scheduledCheckpointId = checkpoint.checkpointId,
            completedBatchCount = nextBatch.completedBatchCount,
            nextDailyPlanAt = nextDailyPlanAt
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
        var completedBatchCount = 0

        corpus.files
            .asSequence()
            .mapNotNull(::dailyFileOrNull)
            .filter { daily -> daily.date < today }
            .sortedWith(compareBy<DailyFile> { daily -> daily.date }.thenBy { daily -> daily.sourcePath })
            .forEach { daily ->
                val parsed = markdownMemoryCodec.parse(daily.markdown)
                val evidence = parsed.entries.map { entry -> entry.toEvidence(daily.sourcePath) }
                if (evidence.isEmpty()) {
                    if (parsed.skippedEntries.isEmpty() && daily.markdown.isHeaderOnly()) {
                        completedBatchCount += ensureEmptyCheckpoint(daily, targetBaseHash)
                    }
                    return@forEach
                }
                val batches = splitEvidence(evidence)
                batches.forEachIndexed { batchIndex, batchEvidence ->
                    val batchKey = batchKey(batchIndex, batchEvidence)
                    val checkpoint = recoveryDao.getDistillationCheckpoint(
                        dailySourcePath = daily.sourcePath,
                        dailySourceHash = daily.sourceHash,
                        batchKey = batchKey
                    )
                    if (checkpoint?.status == MemoryDistillationCheckpointStatus.COMPLETED) {
                        completedBatchCount += 1
                        return@forEachIndexed
                    }
                    val batchId = batchId(daily, batchKey)
                    return PlannedBatch(
                        input = MemoryDailyDistillationFrozenInput(
                            batchId = batchId,
                            batchKey = batchKey,
                            dailySourcePath = daily.sourcePath,
                            dailySourceHash = daily.sourceHash,
                            dailyDate = daily.date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            dailyEvidence = batchEvidence,
                            existingMemories = existingMemories,
                            targetBaseHash = targetBaseHash,
                            createdAt = now()
                        ),
                        checkpoint = checkpoint,
                        completedBatchCount = completedBatchCount
                    )
                }
            }
        return null
    }

    private suspend fun enqueueBatchPlan(batch: PlannedBatch): MemoryMaintenanceJob {
        val input = batch.input
        val job = maintenanceScheduler.enqueue(
            type = MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION,
            idempotencyKey = "memory-daily-distillation-plan:${input.batchId}:${input.targetBaseHash}",
            payloadJson = json.encodeToString(
                MemoryDailyDistillationPlanJobPayload(
                    kind = MemoryDailyDistillationPlanKind.BATCH,
                    localDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE),
                    dailySourcePath = input.dailySourcePath,
                    dailySourceHash = input.dailySourceHash,
                    batchKey = input.batchKey,
                    targetBaseHash = input.targetBaseHash
                )
            )
        )
        return if (
            job.status == MemoryMaintenanceJobStatus.DISMISSED &&
            job.lastError == "memory_disabled"
        ) {
            maintenanceScheduler.reviveDismissedDailyPlan(job.jobId) ?: job
        } else {
            job
        }
    }

    private suspend fun insertPendingCheckpoint(
        input: MemoryDailyDistillationFrozenInput
    ): MemoryDistillationCheckpoint {
        val checkpointId = checkpointId(input)
        val checkpoint = MemoryDistillationCheckpoint(
            checkpointId = checkpointId,
            dailySourcePath = input.dailySourcePath,
            dailySourceHash = input.dailySourceHash,
            batchKey = input.batchKey,
            dailyDate = input.dailyDate,
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
        val inserted = recoveryDao.insertDistillationCheckpointIgnore(checkpoint)
        return if (inserted != -1L) {
            checkpoint
        } else {
            checkNotNull(
                recoveryDao.getDistillationCheckpoint(
                    input.dailySourcePath,
                    input.dailySourceHash,
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
        return if (
            job.status == MemoryMaintenanceJobStatus.DISMISSED &&
            job.lastError == "memory_disabled"
        ) {
            maintenanceScheduler.reviveDismissedDailyDistillation(job.jobId) ?: job
        } else {
            job
        }
    }

    private suspend fun markCheckpointStale(
        checkpoint: MemoryDistillationCheckpoint,
        status: String
    ): MemoryDistillationCheckpoint {
        val changed = recoveryDao.transitionDistillationCheckpointCas(
            checkpointId = checkpoint.checkpointId,
            expectedStatus = checkpoint.status,
            expectedRowVersion = checkpoint.rowVersion,
            expectedDailySourcePath = checkpoint.dailySourcePath,
            expectedDailySourceHash = checkpoint.dailySourceHash,
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
                checkpoint.dailySourcePath,
                checkpoint.dailySourceHash,
                checkpoint.batchKey
            )
        )
    }

    private suspend fun replanCheckpoint(
        original: MemoryDistillationCheckpoint,
        input: MemoryDailyDistillationFrozenInput
    ): MemoryDistillationCheckpoint {
        val checkpoint = recoveryDao.getDistillationCheckpoint(
            original.dailySourcePath,
            original.dailySourceHash,
            original.batchKey
        ) ?: error("Distillation checkpoint disappeared before replanning")
        check(checkpoint.status in MemoryDistillationCheckpointStatus.REPLANNABLE)
        val newSemanticJobId = semanticJobId(checkpoint.checkpointId, input.targetBaseHash)
        val changed = recoveryDao.replanDistillationCheckpointCas(
            checkpointId = checkpoint.checkpointId,
            expectedStatus = checkpoint.status,
            expectedRowVersion = checkpoint.rowVersion,
            expectedDailySourcePath = checkpoint.dailySourcePath,
            expectedDailySourceHash = checkpoint.dailySourceHash,
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
                checkpoint.dailySourcePath,
                checkpoint.dailySourceHash,
                checkpoint.batchKey
            )
        )
    }

    private suspend fun ensureEmptyCheckpoint(daily: DailyFile, targetBaseHash: String): Int {
        val batchKey = EMPTY_BATCH_KEY
        val existing = recoveryDao.getDistillationCheckpoint(daily.sourcePath, daily.sourceHash, batchKey)
        if (existing != null) return if (existing.status == MemoryDistillationCheckpointStatus.COMPLETED) 1 else 0
        val checkpointId = "distill_${"${daily.sourcePath}|${daily.sourceHash}|$batchKey".sha256Utf8().take(ID_HASH_LENGTH)}"
        val now = now()
        val inserted = recoveryDao.insertDistillationCheckpointIgnore(
            MemoryDistillationCheckpoint(
                checkpointId = checkpointId,
                dailySourcePath = daily.sourcePath,
                dailySourceHash = daily.sourceHash,
                batchKey = batchKey,
                dailyDate = daily.date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                semanticJobId = "local_noop_$checkpointId",
                targetSourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                targetBaseHash = targetBaseHash,
                targetSourceHash = targetBaseHash,
                mutationGroupId = null,
                status = MemoryDistillationCheckpointStatus.COMPLETED,
                createdAt = now,
                updatedAt = now,
                processedAt = now
            )
        )
        return if (inserted != -1L) 1 else 0
    }

    private suspend fun ensureNextDailyWake(): Long {
        val nextDate = LocalDate.now(clock).plusDays(1)
        val nextRunAt = nextDate.atStartOfDay(clock.zone).toEpochSecond()
        val job = maintenanceScheduler.enqueue(
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
            maintenanceScheduler.reviveDismissedDailyPlan(job.jobId)
        }
        return nextRunAt
    }

    private fun decodePlanPayload(job: MemoryMaintenanceJob): MemoryDailyDistillationPlanJobPayload? = try {
        check(job.type == MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION)
        json.decodeFromString<MemoryDailyDistillationPlanJobPayload>(job.payloadJson).also { payload ->
            check(payload.kind in setOf(MemoryDailyDistillationPlanKind.BATCH, MemoryDailyDistillationPlanKind.DAILY_WAKE))
            LocalDate.parse(payload.localDate, DateTimeFormatter.ISO_LOCAL_DATE)
            if (payload.kind == MemoryDailyDistillationPlanKind.BATCH) {
                check(!payload.dailySourcePath.isNullOrBlank())
                check(payload.dailySourceHash?.matches(SHA_256_REGEX) == true)
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
                    updatedAt = entry.updatedAt
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

    private fun MarkdownMemoryEntry.toEvidence(sourcePath: String): MemoryDailyDistillationEvidence {
        val evidenceIdentity = listOf(
            sourcePath,
            id,
            text,
            type,
            sensitivity,
            source,
            createdAt.toString(),
            updatedAt.toString()
        ).joinToString(separator = "\u0000")
        return MemoryDailyDistillationEvidence(
            evidenceKey = "daily_${evidenceIdentity.sha256Utf8().take(ID_HASH_LENGTH)}",
            entryId = id,
            text = text,
            type = type,
            sensitivity = sensitivity,
            source = source,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun String.isHeaderOnly(): Boolean = lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .all { line -> line.startsWith("# ") }

    private fun batchKey(
        batchIndex: Int,
        evidence: List<MemoryDailyDistillationEvidence>
    ): String = "batch_${batchIndex.toString().padStart(4, '0')}_${
        evidence.joinToString("|") { item -> item.evidenceKey }.sha256Utf8().take(BATCH_HASH_LENGTH)
    }"

    private fun batchId(daily: DailyFile, batchKey: String): String =
        "daily_${"${daily.sourcePath}|${daily.sourceHash}|$batchKey".sha256Utf8().take(ID_HASH_LENGTH)}"

    private fun checkpointId(input: MemoryDailyDistillationFrozenInput): String =
        "distill_${"${input.dailySourcePath}|${input.dailySourceHash}|${input.batchKey}".sha256Utf8().take(ID_HASH_LENGTH)}"

    private fun semanticJobId(checkpointId: String, targetBaseHash: String): String =
        "distill_job_${"$checkpointId|$targetBaseHash".sha256Utf8().take(ID_HASH_LENGTH)}"

    private fun MemoryDailyDistillationFrozenInput.withCreatedAt(createdAt: Long) = copy(createdAt = createdAt)

    private fun PlannedBatch.matches(payload: MemoryDailyDistillationPlanJobPayload): Boolean =
        input.dailySourcePath == payload.dailySourcePath &&
            input.dailySourceHash == payload.dailySourceHash &&
            input.batchKey == payload.batchKey &&
            input.targetBaseHash == payload.targetBaseHash

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
        const val EMPTY_BATCH_KEY = "empty"
        val DAILY_SOURCE_REGEX = Regex("memory/(\\d{4}-\\d{2}-\\d{2})\\.md")
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
    }
}
