package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryLongTermConsolidationDao
import cn.nabr.chatwithchat.data.database.entity.MemoryLongTermConsolidationCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MemoryLongTermConsolidationService(
    private val checkpointDao: MemoryLongTermConsolidationDao,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val settingRepository: SettingRepository,
    private val modelResolver: MemoryModelResolver,
    private val memoryIntelligence: MemoryIntelligence,
    private val memoryFileStore: MemoryFileStore,
    private val markdownMemoryCodec: MarkdownMemoryCodec,
    private val operationController: MemoryLongTermConsolidationOperationController,
    private val memoryMutationCoordinator: MemoryMutationCoordinator,
    private val longTermScheduler: MemoryLongTermConsolidationScheduler,
    private val activityLogger: MemoryActivityLogger = MemoryActivityLogger.None,
    private val commitObserver: MemoryLongTermConsolidationCommitObserver = MemoryLongTermConsolidationCommitObserver.None,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val maxPartitionsPerInvocation: Int = DEFAULT_MAX_PARTITIONS_PER_INVOCATION,
    private val maxLlmCallsPerInvocation: Int = DEFAULT_MAX_LLM_CALLS_PER_INVOCATION,
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }
) : MemoryLongTermConsolidationRecoveryFinalizer {
    private val policy = MemoryLongTermConsolidationPolicy()

    init {
        require(maxPartitionsPerInvocation > 0) { "Long-term partition invocation limit must be positive" }
        require(maxLlmCallsPerInvocation > 0) { "Long-term LLM invocation limit must be positive" }
    }

    suspend fun process(job: MemoryMaintenanceJob): MemoryLongTermProcessResult {
        terminalResultOrNull(job)?.let { return it }
        val payload = decodePayload(job) ?: run {
            val activityRunId = activityLogger.startSemanticRun(job, MemoryActivityCategory.LONG_TERM_CONSOLIDATION)
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                MemoryActivityRunData(errorCode = "invalid_long_term_payload"),
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
            )
            return terminal(job, "invalid_long_term_consolidation_payload")
        }
        var checkpoint = checkpointFor(job, payload)
            ?: run {
                val activityRunId = activityLogger.startSemanticRun(job, MemoryActivityCategory.LONG_TERM_CONSOLIDATION)
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.FAILED,
                    MemoryActivityRunData(errorCode = "long_term_checkpoint_missing"),
                    expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
                )
                return terminal(job, "long_term_consolidation_checkpoint_missing")
            }
        val activityRunId = activityLogger.startSemanticRun(
            job = job,
            category = MemoryActivityCategory.LONG_TERM_CONSOLIDATION,
            triggerReason = checkpoint.triggerReason,
            inputCount = checkpoint.entryCount
        )
        if (checkpoint.status == MemoryLongTermCheckpointStatus.COMPLETED) {
            if (job.status == MemoryMaintenanceJobStatus.RUNNING) maintenanceScheduler.markSucceeded(job)
            memoryMutationCoordinator.findBySemanticJobId(job.jobId)?.let { mutation ->
                memoryMutationCoordinator.acknowledgeSemanticCompletion(mutation.group.groupId)
            }
            scheduleNextPass(checkpoint.continuationRequired, checkpoint.checkpointId)
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.NO_OP,
                MemoryActivityRunData(inputCount = checkpoint.entryCount),
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
            )
            return MemoryLongTermProcessResult(
                status = MemoryLongTermProcessResult.STATUS_DUPLICATE,
                jobId = job.jobId
            )
        }
        check(job.status == MemoryMaintenanceJobStatus.RUNNING) { "memory_job_not_claimed" }
        check(!job.leaseOwner.isNullOrBlank()) { "memory_job_missing_lease" }
        checkpoint = recordAttempt(checkpoint, job.attempts)
        if (!settingRepository.fetchMemoryEnabled()) {
            recordCheckpointError(checkpoint, REASON_MEMORY_DISABLED)
            maintenanceScheduler.markDismissed(job, REASON_MEMORY_DISABLED)
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.SKIPPED,
                MemoryActivityRunData(inputCount = checkpoint.entryCount, errorCode = REASON_MEMORY_DISABLED),
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
            )
            return MemoryLongTermProcessResult(
                status = MemoryLongTermProcessResult.STATUS_TERMINAL,
                jobId = job.jobId,
                reason = REASON_MEMORY_DISABLED
            )
        }

        memoryMutationCoordinator.findBySemanticJobId(job.jobId)?.let { mutation ->
            activityLogger.advanceRunSafely(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityPhase.ORGANIZATION,
                MemoryActivityRunData(inputCount = checkpoint.entryCount, cursor = checkpoint.partitionCursor)
            )
            checkpoint = alignCheckpointWithMutation(checkpoint, mutation)
            return commitPreparedMutation(
                job = job,
                checkpoint = checkpoint,
                mutation = mutation,
                operationCount = 0,
                activityRunId = activityRunId
            )
        }
        if (checkpoint.status != MemoryLongTermCheckpointStatus.PENDING) {
            recordCheckpointError(checkpoint, "long_term_checkpoint_missing_prepared_mutation")
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                MemoryActivityRunData(
                    inputCount = checkpoint.entryCount,
                    errorCode = "long_term_checkpoint_state_invalid"
                ),
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
            )
            return terminal(job, "long_term_checkpoint_missing_prepared_mutation")
        }

        val snapshot = readFrozenSnapshot(checkpoint) ?: run {
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.SKIPPED,
                MemoryActivityRunData(
                    inputCount = checkpoint.entryCount,
                    cursor = checkpoint.partitionCursor,
                    errorCode = REASON_STALE_SOURCE
                ),
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
            )
            return staleSource(job, checkpoint, REASON_STALE_SOURCE)
        }
        if (snapshot.structuralRepairCount > 0) {
            activityLogger.advanceRunSafely(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityPhase.ORGANIZATION,
                MemoryActivityRunData(
                    inputCount = checkpoint.entryCount,
                    operationCount = snapshot.structuralRepairCount,
                    cursor = checkpoint.partitionCursor
                )
            )
            checkpoint = persistContinuationIntent(checkpoint, continuationRequired = true)
            val repair = operationController.renderStructuralRepair(
                baseMarkdown = snapshot.sourceMarkdown,
                repairedMarkdown = snapshot.workingMarkdown,
                repairedCount = snapshot.structuralRepairCount
            )
            return prepareAndCommitRendered(
                job = job,
                checkpoint = checkpoint,
                rendered = repair,
                activityRunId = activityRunId
            )
        }
        var currentJob = job
        var persistedProposal = decodePersistedProposal(checkpoint)
            ?: run {
                recordCheckpointError(checkpoint, "invalid_persisted_long_term_proposal")
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.FAILED,
                    MemoryActivityRunData(
                        inputCount = checkpoint.entryCount,
                        cursor = checkpoint.partitionCursor,
                        errorCode = "invalid_long_term_proposal"
                    ),
                    expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
                )
                return terminal(job, "invalid_persisted_long_term_proposal")
            }
        var processedPartitions = 0
        var llmCalls = 0
        var activityPlatform: PlatformV2? = null

        while (
            checkpoint.partitionCursor < checkpoint.entryCount &&
            persistedProposal.decisions.size < EARLY_COMMIT_DECISION_COUNT
        ) {
            if (processedPartitions >= maxPartitionsPerInvocation) {
                return deferForInvocationBudget(currentJob, checkpoint)
            }
            currentJob = maintenanceScheduler.renewClaimedLease(currentJob)
            val assignedIds = persistedProposal.decisions.flatMap(MemoryLongTermCanonicalDecision::memoryIds).toSet()
            val boundedRequest = policy.nextBoundedRequest(
                checkpointId = checkpoint.checkpointId,
                orderedEntries = snapshot.entries,
                cursor = checkpoint.partitionCursor,
                alreadyAssignedIds = assignedIds
            )
            val partition = boundedRequest.partition
            val request = boundedRequest.request
            val candidateGroups = request.candidateGroups
            var nextProposal = persistedProposal
            var persistProposal = checkpoint.proposalJson != null
            if (candidateGroups.isNotEmpty()) {
                if (llmCalls >= maxLlmCallsPerInvocation) {
                    return deferForInvocationBudget(currentJob, checkpoint)
                }
                when (val binding = resolveModelBinding(currentJob, checkpoint)) {
                    is ModelBindingResult.Unavailable -> {
                        recordCheckpointError(checkpoint, binding.reason)
                        activityLogger.finishRunSafely(
                            activityRunId,
                            MemoryActivityStatus.BLOCKED,
                            MemoryActivityRunData(
                                inputCount = checkpoint.entryCount,
                                cursor = checkpoint.partitionCursor,
                                errorCode = binding.reason
                            ),
                            expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
                        )
                        maintenanceScheduler.markBlockedDependency(currentJob, binding.reason)
                        return MemoryLongTermProcessResult(
                            status = MemoryLongTermProcessResult.STATUS_BLOCKED,
                            jobId = job.jobId,
                            reason = binding.reason
                        )
                    }
                    is ModelBindingResult.Resolved -> {
                        currentJob = binding.job
                        checkpoint = binding.checkpoint
                        activityPlatform = binding.platform
                        llmCalls += 1
                        val proposal = memoryIntelligence.consolidateLongTermMemory(
                            request = request,
                            resolvedPlatform = binding.platform,
                            activityRunId = activityRunId
                        ) ?: run {
                            activityLogger.finishRunSafely(
                                activityRunId,
                                MemoryActivityStatus.FAILED,
                                binding.platform.toMemoryActivityData(
                                    inputCount = checkpoint.entryCount,
                                    cursor = checkpoint.partitionCursor,
                                    errorCode = "long_term_model_output_invalid"
                                ),
                                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
                            )
                            return retryable(
                                currentJob,
                                checkpoint,
                                "long_term_consolidation_unavailable_or_invalid"
                            )
                        }
                        nextProposal = runCatching {
                            policy.validateAndMergeProposal(
                                existing = persistedProposal,
                                partitionRequest = request,
                                proposal = proposal
                            )
                        }.getOrElse { throwable ->
                            rethrowInterruption(throwable)
                            activityLogger.finishRunSafely(
                                activityRunId,
                                MemoryActivityStatus.FAILED,
                                binding.platform.toMemoryActivityData(
                                    inputCount = checkpoint.entryCount,
                                    cursor = checkpoint.partitionCursor,
                                    errorCode = "invalid_long_term_proposal"
                                ),
                                expectedPhase = MemoryActivityPhase.GENERATION
                            )
                            return retryable(
                                currentJob,
                                checkpoint,
                                "invalid_long_term_consolidation_proposal:${throwable.message}"
                            )
                        }
                        persistProposal = true
                    }
                }
            }
            val proposalJson = nextProposal.takeIf { persistProposal }?.let { proposal ->
                json.encodeToString(proposal)
            }
            checkpoint = advancePartition(
                checkpoint = checkpoint,
                newCursor = partition.endExclusive,
                proposalJson = proposalJson
            )
            commitObserver.afterPartitionPersisted(checkpoint)
            persistedProposal = nextProposal
            processedPartitions += 1
        }

        val currentHash = memoryFileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME)
            .getOrNull()
        if (currentHash != checkpoint.baseSourceHash) {
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.SKIPPED,
                activityPlatform.activityData(
                    inputCount = checkpoint.entryCount,
                    cursor = checkpoint.partitionCursor,
                    errorCode = REASON_STALE_SOURCE
                ),
                expectedPhase = if (llmCalls > 0) {
                    MemoryActivityPhase.GENERATION
                } else {
                    MemoryActivityPhase.MODEL_RESOLUTION
                }
            )
            return staleSource(currentJob, checkpoint, REASON_STALE_SOURCE)
        }
        if (llmCalls > 0) {
            activityLogger.advanceRunSafely(
                activityRunId,
                MemoryActivityPhase.GENERATION,
                MemoryActivityPhase.ORGANIZATION,
                activityPlatform.activityData(
                    inputCount = checkpoint.entryCount,
                    cursor = checkpoint.partitionCursor
                )
            )
        } else {
            activityLogger.advanceRunSafely(
                activityRunId,
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityPhase.ORGANIZATION,
                MemoryActivityRunData(inputCount = checkpoint.entryCount, cursor = checkpoint.partitionCursor)
            )
        }
        val rendered = runCatching {
            operationController.render(
                baseMarkdown = snapshot.workingMarkdown,
                entries = snapshot.entries,
                proposal = persistedProposal,
                renderedAt = checkpoint.createdAt
            )
        }.getOrElse { throwable ->
            rethrowInterruption(throwable)
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                activityPlatform.activityData(
                    inputCount = checkpoint.entryCount,
                    cursor = checkpoint.partitionCursor,
                    errorCode = "long_term_render_failed"
                ),
                expectedPhase = MemoryActivityPhase.ORGANIZATION
            )
            return retryable(
                currentJob,
                checkpoint,
                "long_term_consolidation_render_failed:${throwable.message}"
            )
        }
        val continuationRequired =
            checkpoint.partitionCursor < checkpoint.entryCount || rendered.hasMoreCandidates
        checkpoint = persistContinuationIntent(checkpoint, continuationRequired)
        if (rendered.targets.isEmpty()) {
            checkpoint = completeCheckpoint(
                checkpoint = checkpoint,
                resultSourceHash = checkpoint.baseSourceHash,
                completedGeneration = checkpoint.baseGeneration,
                mutationGroupId = null
            )
            commitObserver.afterCheckpointCompletion(checkpoint)
            maintenanceScheduler.markSucceeded(currentJob)
            scheduleNextPass(continuationRequired, checkpoint.checkpointId)
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.NO_OP,
                activityPlatform.activityData(
                    inputCount = checkpoint.entryCount,
                    operationCount = 0,
                    cursor = checkpoint.partitionCursor
                ),
                expectedPhase = MemoryActivityPhase.ORGANIZATION
            )
            return MemoryLongTermProcessResult(
                status = MemoryLongTermProcessResult.STATUS_SUCCEEDED,
                jobId = job.jobId,
                operationCount = 0,
                reason = REASON_CLEAN_NO_OP
            )
        }

        return prepareAndCommitRendered(
            job = currentJob,
            checkpoint = checkpoint,
            rendered = rendered,
            activityRunId = activityRunId
        )
    }

    override suspend fun finalizeRecoveredMutation(recovered: MemoryRecoveredSemanticMutation): Boolean {
        var checkpoint = checkpointDao.getByJobId(recovered.semanticJobId) ?: return false
        if (recovered.terminalReason != null && checkpoint.status == MemoryLongTermCheckpointStatus.CONFLICT) {
            scheduleReplan()
            return true
        }
        if (recovered.terminalReason == null && checkpoint.status == MemoryLongTermCheckpointStatus.COMPLETED) {
            scheduleNextPass(checkpoint.continuationRequired, checkpoint.checkpointId)
            return true
        }
        val mutation = memoryMutationCoordinator.findBySemanticJobId(recovered.semanticJobId)
            ?: error("Recovered long-term consolidation mutation is missing")
        checkpoint = alignCheckpointWithMutation(checkpoint, mutation)
        if (recovered.terminalReason != null) {
            if (checkpoint.status != MemoryLongTermCheckpointStatus.CONFLICT) {
                transitionCheckpoint(
                    checkpoint = checkpoint,
                    newStatus = MemoryLongTermCheckpointStatus.CONFLICT,
                    newActiveKey = null,
                    newResultSourceHash = checkpoint.resultSourceHash,
                    newCompletedGeneration = null,
                    newMutationGroupId = mutation.group.groupId,
                    lastError = recovered.terminalReason,
                    completedAt = now()
                )
            }
            scheduleReplan()
            return true
        }
        if (checkpoint.status != MemoryLongTermCheckpointStatus.COMPLETED) {
            val receipt = requireNotNull(
                mutation.receipts.singleOrNull { item ->
                    item.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
                }
            ) { "Recovered long-term consolidation mutation has no canonical receipt" }
            checkpoint = completeCheckpoint(
                checkpoint = checkpoint,
                resultSourceHash = receipt.targetSourceHash,
                completedGeneration = mutation.group.generation,
                mutationGroupId = mutation.group.groupId
            )
            commitObserver.afterCheckpointCompletion(checkpoint)
        }
        scheduleNextPass(checkpoint.continuationRequired, checkpoint.checkpointId)
        return true
    }

    private suspend fun prepareAndCommitRendered(
        job: MemoryMaintenanceJob,
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        rendered: RenderedMemoryLongTermConsolidation,
        activityRunId: String
    ): MemoryLongTermProcessResult {
        val mutation = runCatching {
            maintenanceScheduler.renewClaimedLease(job)
            memoryMutationCoordinator.prepare(
                semanticJobId = job.jobId,
                semanticBatchId = checkpoint.checkpointId,
                targets = rendered.targets
            )
        }.getOrElse { throwable ->
            rethrowInterruption(throwable)
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                checkpoint.activityData(
                    operationCount = rendered.operationCount,
                    errorCode = "long_term_prepare_failed"
                ),
                expectedPhase = MemoryActivityPhase.ORGANIZATION
            )
            return retryable(
                job,
                checkpoint,
                "long_term_consolidation_prepare_failed:${throwable.message}"
            )
        }
        commitObserver.afterPrepared(mutation)
        val preparedCheckpoint = transitionCheckpoint(
            checkpoint = checkpoint,
            newStatus = MemoryLongTermCheckpointStatus.PREPARED,
            newActiveKey = checkpoint.activeKey,
            newResultSourceHash = rendered.targetSourceHash,
            newCompletedGeneration = null,
            newMutationGroupId = mutation.group.groupId,
            lastError = null,
            completedAt = null
        )
        return commitPreparedMutation(
            job = job,
            checkpoint = preparedCheckpoint,
            mutation = mutation,
            operationCount = rendered.operationCount,
            activityRunId = activityRunId
        )
    }

    private suspend fun commitPreparedMutation(
        job: MemoryMaintenanceJob,
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        mutation: MemoryPreparedMutation,
        operationCount: Int,
        activityRunId: String
    ): MemoryLongTermProcessResult {
        val commitResult = runCatching {
            maintenanceScheduler.renewClaimedLease(job)
            memoryMutationCoordinator.reconcile(mutation)
        }.getOrElse { throwable ->
            rethrowInterruption(throwable)
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                checkpoint.activityData(
                    operationCount = operationCount,
                    errorCode = "long_term_commit_failed"
                ),
                expectedPhase = MemoryActivityPhase.ORGANIZATION
            )
            return retryable(
                job,
                checkpoint,
                "long_term_consolidation_commit_failed:${throwable.message}"
            )
        }
        if (commitResult is MemoryMutationCommitResult.Conflict) {
            transitionCheckpoint(
                checkpoint = checkpoint,
                newStatus = MemoryLongTermCheckpointStatus.CONFLICT,
                newActiveKey = null,
                newResultSourceHash = checkpoint.resultSourceHash,
                newCompletedGeneration = null,
                newMutationGroupId = commitResult.mutation.group.groupId,
                lastError = commitResult.reason,
                completedAt = now()
            )
            maintenanceScheduler.markFailedTerminal(job, commitResult.reason)
            scheduleReplan()
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                checkpoint.activityData(
                    operationCount = operationCount,
                    errorCode = "long_term_commit_conflict"
                ),
                expectedPhase = MemoryActivityPhase.ORGANIZATION
            )
            return MemoryLongTermProcessResult(
                status = MemoryLongTermProcessResult.STATUS_TERMINAL,
                jobId = job.jobId,
                operationCount = operationCount,
                reason = commitResult.reason
            )
        }

        val committed = commitResult as MemoryMutationCommitResult.CanonicalCommitted
        commitObserver.afterCanonicalFileCommit(committed.mutation)
        val receipt = requireNotNull(
            committed.mutation.receipts.singleOrNull { item ->
                item.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
            }
        ) { "Long-term consolidation mutation has no canonical receipt" }
        val completed = completeCheckpoint(
            checkpoint = checkpoint,
            resultSourceHash = receipt.targetSourceHash,
            completedGeneration = committed.mutation.group.generation,
            mutationGroupId = committed.mutation.group.groupId
        )
        commitObserver.afterCheckpointCompletion(completed)
        maintenanceScheduler.markSucceeded(job)
        memoryMutationCoordinator.acknowledgeSemanticCompletion(committed.mutation.group.groupId)
        scheduleNextPass(completed.continuationRequired, completed.checkpointId)
        activityLogger.finishRunSafely(
            activityRunId,
            MemoryActivityStatus.SUCCEEDED,
            completed.activityData(operationCount = operationCount),
            expectedPhase = MemoryActivityPhase.ORGANIZATION
        )
        return MemoryLongTermProcessResult(
            status = MemoryLongTermProcessResult.STATUS_SUCCEEDED,
            jobId = job.jobId,
            operationCount = operationCount
        )
    }

    private suspend fun resolveModelBinding(
        job: MemoryMaintenanceJob,
        checkpoint: MemoryLongTermConsolidationCheckpoint
    ): ModelBindingResult {
        val binding = resolveClaimedMemoryModel(
            job = job,
            settingRepository = settingRepository,
            modelResolver = modelResolver,
            maintenanceScheduler = maintenanceScheduler
        )
        val resolved = when (binding) {
            is ClaimedMemoryModelBinding.Unavailable -> return ModelBindingResult.Unavailable(binding.reason.code)
            is ClaimedMemoryModelBinding.Resolved -> binding
        }
        val boundJob = resolved.job
        var boundCheckpoint = checkpoint
        val checkpointBinding = listOf(
            checkpoint.resolvedPlatformUid,
            checkpoint.resolvedModelId,
            checkpoint.resolvedAt
        )
        require(
            checkpointBinding.all { value -> value == null } ||
                (checkpoint.resolvedPlatformUid != null && checkpoint.resolvedModelId != null && checkpoint.resolvedAt != null)
        ) { "partial long-term checkpoint model binding" }
        if (checkpoint.resolvedPlatformUid == null) {
            val changed = checkpointDao.bindResolvedModelCas(
                checkpointId = checkpoint.checkpointId,
                expectedStatus = checkpoint.status,
                expectedRowVersion = checkpoint.rowVersion,
                platformUid = checkNotNull(boundJob.resolvedPlatformUid),
                modelId = checkNotNull(boundJob.resolvedModelId),
                resolvedAt = checkNotNull(boundJob.resolvedAt),
                updatedAt = now()
            )
            boundCheckpoint = checkNotNull(checkpointDao.getById(checkpoint.checkpointId))
            if (changed != 1) {
                require(
                    boundCheckpoint.resolvedPlatformUid == boundJob.resolvedPlatformUid &&
                        boundCheckpoint.resolvedModelId == boundJob.resolvedModelId &&
                        boundCheckpoint.resolvedAt == boundJob.resolvedAt
                ) { "long-term checkpoint model binding conflict" }
            }
        }
        require(
            boundCheckpoint.resolvedPlatformUid == boundJob.resolvedPlatformUid &&
                boundCheckpoint.resolvedModelId == boundJob.resolvedModelId &&
                boundCheckpoint.resolvedAt == boundJob.resolvedAt
        ) { "long-term job and checkpoint model bindings differ" }
        return ModelBindingResult.Resolved(boundJob, boundCheckpoint, resolved.platform)
    }

    private fun readFrozenSnapshot(checkpoint: MemoryLongTermConsolidationCheckpoint): FrozenSnapshot? {
        val markdown = memoryFileStore.readLongTermMemory().getOrNull() ?: return null
        if (markdown.toByteArray(Charsets.UTF_8).sha256Hex() != checkpoint.baseSourceHash) return null
        val repaired = requireNotNull(markdownMemoryCodec.repairStructuralRelationships(markdown)) {
            "unsafe_memory_metadata"
        }
        val orderedIds = json.decodeFromString<List<String>>(checkpoint.orderedEntryIdsJson)
        require(orderedIds.size == checkpoint.entryCount) { "long-term entry count mismatch" }
        require(orderedIds.distinct().size == orderedIds.size) { "duplicate frozen long-term entry id" }
        if (repaired.repairedCount > 0) {
            require(orderedIds.isEmpty()) { "structural repair checkpoint must not freeze entry ids" }
            return FrozenSnapshot(
                sourceMarkdown = markdown,
                workingMarkdown = repaired.markdown,
                structuralRepairCount = repaired.repairedCount,
                entries = emptyList()
            )
        }
        require(repaired.entries.map(MarkdownMemoryEntry::id) == orderedIds) {
            "long-term ordered snapshot mismatch"
        }
        return FrozenSnapshot(
            sourceMarkdown = markdown,
            workingMarkdown = repaired.markdown,
            structuralRepairCount = repaired.repairedCount,
            entries = repaired.entries
        )
    }

    private fun decodePersistedProposal(
        checkpoint: MemoryLongTermConsolidationCheckpoint
    ): MemoryLongTermPersistedProposal? = try {
        if (checkpoint.proposalJson == null) {
            require(checkpoint.proposalHash == null)
            MemoryLongTermPersistedProposal()
        } else {
            require(checkpoint.proposalJson.sha256Utf8() == checkpoint.proposalHash)
            json.decodeFromString<MemoryLongTermPersistedProposal>(checkpoint.proposalJson)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private suspend fun advancePartition(
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        newCursor: Int,
        proposalJson: String?
    ): MemoryLongTermConsolidationCheckpoint {
        val proposalHash = proposalJson?.sha256Utf8()
        val changed = checkpointDao.advancePartitionCas(
            checkpointId = checkpoint.checkpointId,
            expectedStatus = checkpoint.status,
            expectedRowVersion = checkpoint.rowVersion,
            expectedBaseSourceHash = checkpoint.baseSourceHash,
            expectedOrderedSnapshotHash = checkpoint.orderedSnapshotHash,
            expectedPartitionCursor = checkpoint.partitionCursor,
            expectedProposalHash = checkpoint.proposalHash,
            expectedProposalJson = checkpoint.proposalJson,
            newPartitionCursor = newCursor,
            newProposalHash = proposalHash,
            newProposalJson = proposalJson,
            updatedAt = now()
        )
        val current = checkNotNull(checkpointDao.getById(checkpoint.checkpointId))
        if (changed != 1) {
            require(
                current.partitionCursor == newCursor &&
                    current.proposalHash == proposalHash &&
                    current.proposalJson == proposalJson
            ) { "long-term partition changed before persistence" }
        }
        return current
    }

    private suspend fun alignCheckpointWithMutation(
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        mutation: MemoryPreparedMutation
    ): MemoryLongTermConsolidationCheckpoint {
        if (checkpoint.status == MemoryLongTermCheckpointStatus.COMPLETED) return checkpoint
        val receipt = requireNotNull(
            mutation.receipts.singleOrNull { item ->
                item.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
            }
        ) { "Long-term consolidation mutation has no canonical receipt" }
        if (
            checkpoint.status == MemoryLongTermCheckpointStatus.PREPARED &&
            checkpoint.mutationGroupId == mutation.group.groupId &&
            checkpoint.resultSourceHash == receipt.targetSourceHash
        ) {
            return checkpoint
        }
        require(checkpoint.status == MemoryLongTermCheckpointStatus.PENDING)
        return transitionCheckpoint(
            checkpoint = checkpoint,
            newStatus = MemoryLongTermCheckpointStatus.PREPARED,
            newActiveKey = checkpoint.activeKey,
            newResultSourceHash = receipt.targetSourceHash,
            newCompletedGeneration = null,
            newMutationGroupId = mutation.group.groupId,
            lastError = null,
            completedAt = null
        )
    }

    private suspend fun persistContinuationIntent(
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        continuationRequired: Boolean
    ): MemoryLongTermConsolidationCheckpoint {
        if (checkpoint.continuationRequired == continuationRequired) return checkpoint
        val changed = checkpointDao.setContinuationRequiredCas(
            checkpointId = checkpoint.checkpointId,
            expectedStatus = checkpoint.status,
            expectedRowVersion = checkpoint.rowVersion,
            expectedContinuationRequired = checkpoint.continuationRequired,
            continuationRequired = continuationRequired,
            updatedAt = now()
        )
        val current = checkNotNull(checkpointDao.getById(checkpoint.checkpointId))
        if (changed != 1) {
            require(
                current.status == checkpoint.status &&
                    current.continuationRequired == continuationRequired
            ) { "long-term continuation intent changed before persistence" }
        }
        return current
    }

    private suspend fun completeCheckpoint(
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        resultSourceHash: String,
        completedGeneration: Long,
        mutationGroupId: String?
    ): MemoryLongTermConsolidationCheckpoint = transitionCheckpoint(
        checkpoint = checkpoint,
        newStatus = MemoryLongTermCheckpointStatus.COMPLETED,
        newActiveKey = null,
        newResultSourceHash = resultSourceHash,
        newCompletedGeneration = completedGeneration,
        newMutationGroupId = mutationGroupId,
        lastError = null,
        completedAt = now()
    )

    private suspend fun transitionCheckpoint(
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        newStatus: String,
        newActiveKey: String?,
        newResultSourceHash: String,
        newCompletedGeneration: Long?,
        newMutationGroupId: String?,
        lastError: String?,
        completedAt: Long?
    ): MemoryLongTermConsolidationCheckpoint {
        val timestamp = now()
        val changed = checkpointDao.transitionCas(
            checkpointId = checkpoint.checkpointId,
            expectedStatus = checkpoint.status,
            expectedRowVersion = checkpoint.rowVersion,
            expectedResultSourceHash = checkpoint.resultSourceHash,
            expectedMutationGroupId = checkpoint.mutationGroupId,
            newStatus = newStatus,
            newActiveKey = newActiveKey,
            newResultSourceHash = newResultSourceHash,
            newCompletedGeneration = newCompletedGeneration,
            newMutationGroupId = newMutationGroupId,
            lastError = lastError?.take(MAX_ERROR_LENGTH),
            completedAt = completedAt,
            updatedAt = timestamp
        )
        val current = checkNotNull(checkpointDao.getById(checkpoint.checkpointId))
        if (changed != 1) {
            require(
                current.status == newStatus &&
                    current.activeKey == newActiveKey &&
                    current.resultSourceHash == newResultSourceHash &&
                    current.completedGeneration == newCompletedGeneration &&
                    current.mutationGroupId == newMutationGroupId &&
                    current.lastError == lastError?.take(MAX_ERROR_LENGTH) &&
                    current.completedAt == completedAt
            ) { "long-term checkpoint changed before transition" }
        }
        return current
    }

    private suspend fun staleSource(
        job: MemoryMaintenanceJob,
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        reason: String
    ): MemoryLongTermProcessResult {
        transitionCheckpoint(
            checkpoint = checkpoint,
            newStatus = MemoryLongTermCheckpointStatus.STALE_SOURCE,
            newActiveKey = null,
            newResultSourceHash = checkpoint.resultSourceHash,
            newCompletedGeneration = null,
            newMutationGroupId = checkpoint.mutationGroupId,
            lastError = reason,
            completedAt = now()
        )
        maintenanceScheduler.markDismissed(job, reason)
        scheduleReplan()
        return MemoryLongTermProcessResult(
            status = MemoryLongTermProcessResult.STATUS_TERMINAL,
            jobId = job.jobId,
            reason = reason
        )
    }

    private suspend fun scheduleNextPass(
        continuationRequired: Boolean,
        completedCheckpointId: String
    ) {
        if (continuationRequired) {
            longTermScheduler.ensureContinuationScheduled(completedCheckpointId)
        } else {
            longTermScheduler.ensureScheduled(completedCheckpointId)
        }
    }

    private suspend fun deferForInvocationBudget(
        job: MemoryMaintenanceJob,
        checkpoint: MemoryLongTermConsolidationCheckpoint
    ): MemoryLongTermProcessResult {
        persistContinuationIntent(checkpoint, continuationRequired = true)
        maintenanceScheduler.deferClaimedWithoutRetry(job)
        runCatching {
            longTermScheduler.ensureScheduled()
        }.onFailure(::rethrowInterruption)
        return MemoryLongTermProcessResult(
            status = MemoryLongTermProcessResult.STATUS_DEFERRED,
            jobId = job.jobId,
            reason = REASON_INVOCATION_BUDGET_EXHAUSTED
        )
    }

    private suspend fun scheduleReplan() {
        val latestCompleted = checkpointDao.getLatestCompleted(MemoryLongTermCheckpointStatus.COMPLETED)
        if (latestCompleted == null) {
            longTermScheduler.ensureScheduled()
        } else {
            longTermScheduler.ensureContinuationScheduled(latestCompleted.checkpointId)
        }
    }

    private fun decodePayload(job: MemoryMaintenanceJob): MemoryLongTermConsolidationJobPayload? = try {
        require(job.type == MemoryMaintenanceJobType.CONSOLIDATE_LONG_TERM_MEMORY)
        require(job.family == MemoryMaintenanceJobFamily.SEMANTIC)
        json.decodeFromString<MemoryLongTermConsolidationJobPayload>(job.payloadJson).also { payload ->
            require(payload.checkpointId.isNotBlank())
            require(SHA_256_REGEX.matches(payload.baseSourceHash))
            require(SHA_256_REGEX.matches(payload.orderedSnapshotHash))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private suspend fun checkpointFor(
        job: MemoryMaintenanceJob,
        payload: MemoryLongTermConsolidationJobPayload
    ): MemoryLongTermConsolidationCheckpoint? = checkpointDao.getByJobId(job.jobId)?.takeIf { checkpoint ->
        checkpoint.checkpointId == payload.checkpointId &&
            checkpoint.jobId == job.jobId &&
            checkpoint.baseSourceHash == payload.baseSourceHash &&
            checkpoint.orderedSnapshotHash == payload.orderedSnapshotHash &&
            checkpoint.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME &&
            checkpoint.status in (
                MemoryLongTermCheckpointStatus.ACTIVE +
                    setOf(
                        MemoryLongTermCheckpointStatus.COMPLETED,
                        MemoryLongTermCheckpointStatus.STALE_SOURCE,
                        MemoryLongTermCheckpointStatus.CONFLICT
                    )
                )
    }

    private suspend fun recordAttempt(
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        attempt: Int
    ): MemoryLongTermConsolidationCheckpoint {
        require(attempt > 0) { "long-term consolidation attempt must be positive" }
        if (checkpoint.attempt == attempt && checkpoint.lastError == null) return checkpoint
        val changed = checkpointDao.recordAttemptCas(
            checkpointId = checkpoint.checkpointId,
            expectedStatus = checkpoint.status,
            expectedRowVersion = checkpoint.rowVersion,
            attempt = attempt,
            updatedAt = now()
        )
        val current = checkNotNull(checkpointDao.getById(checkpoint.checkpointId))
        if (changed != 1) {
            require(current.attempt == attempt && current.lastError == null) {
                "long-term checkpoint attempt changed before persistence"
            }
        }
        return current
    }

    private suspend fun recordCheckpointError(
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        reason: String
    ): MemoryLongTermConsolidationCheckpoint {
        val boundedReason = reason.take(MAX_ERROR_LENGTH)
        if (checkpoint.lastError == boundedReason) return checkpoint
        val changed = checkpointDao.recordErrorCas(
            checkpointId = checkpoint.checkpointId,
            expectedStatus = checkpoint.status,
            expectedRowVersion = checkpoint.rowVersion,
            lastError = boundedReason,
            updatedAt = now()
        )
        val current = checkNotNull(checkpointDao.getById(checkpoint.checkpointId))
        if (changed != 1) {
            require(current.status == checkpoint.status && current.lastError == boundedReason) {
                "long-term checkpoint error changed before persistence"
            }
        }
        return current
    }

    private suspend fun retryable(
        job: MemoryMaintenanceJob,
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        reason: String
    ): MemoryLongTermProcessResult {
        recordCheckpointError(checkpoint, reason)
        val updated = maintenanceScheduler.markFailedRetryable(job, reason)
        val status = if (updated.status == MemoryMaintenanceJobStatus.FAILED_RETRYABLE) {
            MemoryLongTermProcessResult.STATUS_RETRYABLE
        } else {
            MemoryLongTermProcessResult.STATUS_TERMINAL
        }
        return MemoryLongTermProcessResult(status = status, jobId = job.jobId, reason = reason)
    }

    private suspend fun terminal(
        job: MemoryMaintenanceJob,
        reason: String
    ): MemoryLongTermProcessResult {
        if (job.status == MemoryMaintenanceJobStatus.RUNNING) {
            maintenanceScheduler.markFailedTerminal(job, reason)
        }
        return MemoryLongTermProcessResult(
            status = MemoryLongTermProcessResult.STATUS_TERMINAL,
            jobId = job.jobId,
            reason = reason
        )
    }

    private fun terminalResultOrNull(job: MemoryMaintenanceJob): MemoryLongTermProcessResult? = when (job.status) {
        MemoryMaintenanceJobStatus.SUCCEEDED -> MemoryLongTermProcessResult(
            status = MemoryLongTermProcessResult.STATUS_DUPLICATE,
            jobId = job.jobId
        )
        MemoryMaintenanceJobStatus.DISMISSED,
        MemoryMaintenanceJobStatus.FAILED_TERMINAL,
        MemoryMaintenanceJobStatus.WAITING_REPAIR,
        MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY -> MemoryLongTermProcessResult(
            status = MemoryLongTermProcessResult.STATUS_TERMINAL,
            jobId = job.jobId,
            reason = job.lastError ?: job.blockedReason
        )
        else -> null
    }

    private fun rethrowInterruption(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        if (throwable is InterruptedException) throw throwable
    }

    private fun PlatformV2?.activityData(
        inputCount: Int? = null,
        operationCount: Int? = null,
        cursor: Int? = null,
        errorCode: String? = null
    ): MemoryActivityRunData = this?.toMemoryActivityData(
        inputCount = inputCount,
        operationCount = operationCount,
        cursor = cursor,
        errorCode = errorCode
    ) ?: MemoryActivityRunData(
        inputCount = inputCount,
        operationCount = operationCount,
        cursor = cursor,
        errorCode = errorCode
    )

    private fun MemoryLongTermConsolidationCheckpoint.activityData(
        operationCount: Int? = null,
        errorCode: String? = null
    ): MemoryActivityRunData = MemoryActivityRunData(
        platformUid = resolvedPlatformUid,
        modelId = resolvedModelId,
        modelName = resolvedModelId,
        inputCount = entryCount,
        operationCount = operationCount,
        cursor = partitionCursor,
        errorCode = errorCode
    )

    private fun now(): Long = clock.instant().epochSecond

    private sealed interface ModelBindingResult {
        data class Resolved(
            val job: MemoryMaintenanceJob,
            val checkpoint: MemoryLongTermConsolidationCheckpoint,
            val platform: PlatformV2
        ) : ModelBindingResult

        data class Unavailable(val reason: String) : ModelBindingResult
    }

    private data class FrozenSnapshot(
        val sourceMarkdown: String,
        val workingMarkdown: String,
        val structuralRepairCount: Int,
        val entries: List<MarkdownMemoryEntry>
    )

    private companion object {
        const val DEFAULT_MAX_PARTITIONS_PER_INVOCATION = 4
        const val DEFAULT_MAX_LLM_CALLS_PER_INVOCATION = 2
        const val EARLY_COMMIT_DECISION_COUNT =
            MemoryLongTermConsolidationPolicy.MAX_PERSISTED_DECISIONS -
                MemoryLongTermConsolidationPolicy.MAX_DECISIONS_PER_PARTITION
        const val MAX_ERROR_LENGTH = 500
        const val REASON_MEMORY_DISABLED = "memory_disabled"
        const val REASON_STALE_SOURCE = "stale_long_term_source"
        const val REASON_CLEAN_NO_OP = "clean_no_op"
        const val REASON_INVOCATION_BUDGET_EXHAUSTED = "long_term_invocation_budget_exhausted"
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
    }
}

interface MemoryLongTermConsolidationRecoveryFinalizer {
    suspend fun finalizeRecoveredMutation(recovered: MemoryRecoveredSemanticMutation): Boolean
}

interface MemoryLongTermConsolidationCommitObserver {
    suspend fun afterPartitionPersisted(checkpoint: MemoryLongTermConsolidationCheckpoint) = Unit
    suspend fun afterPrepared(mutation: MemoryPreparedMutation) = Unit
    suspend fun afterCanonicalFileCommit(mutation: MemoryPreparedMutation) = Unit
    suspend fun afterCheckpointCompletion(checkpoint: MemoryLongTermConsolidationCheckpoint) = Unit

    data object None : MemoryLongTermConsolidationCommitObserver
}
