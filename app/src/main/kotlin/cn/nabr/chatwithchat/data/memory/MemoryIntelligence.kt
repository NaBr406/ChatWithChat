package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.repository.SettingRepository

interface MemoryIntelligence {
    suspend fun consolidateMemoryBatch(
        request: MemoryBatchConsolidationRequest,
        resolvedPlatform: PlatformV2
    ): MemoryBatchConsolidationProposal?

    suspend fun distillDailyMemory(
        request: MemoryDailyDistillationFrozenInput,
        resolvedPlatform: PlatformV2
    ): MemoryDailyDistillationProposal?

    suspend fun consolidateLongTermMemory(
        request: MemoryLongTermConsolidationPartitionRequest,
        resolvedPlatform: PlatformV2
    ): MemoryLongTermConsolidationProposal?
}

internal sealed interface ClaimedMemoryModelBinding {
    data class Resolved(
        val job: MemoryMaintenanceJob,
        val platform: PlatformV2
    ) : ClaimedMemoryModelBinding

    data class Unavailable(
        val reason: MemoryModelUnavailableReason
    ) : ClaimedMemoryModelBinding
}

internal suspend fun resolveClaimedMemoryModel(
    job: MemoryMaintenanceJob,
    settingRepository: SettingRepository,
    modelResolver: MemoryModelResolver,
    maintenanceScheduler: MemoryMaintenanceScheduler
): ClaimedMemoryModelBinding {
    val bindingValues = listOf(job.resolvedPlatformUid, job.resolvedModelId, job.resolvedAt)
    require(bindingValues.all { value -> value == null } || bindingValues.all { value -> value != null }) {
        "partial memory maintenance job model binding"
    }
    val resolution = if (job.resolvedPlatformUid == null) {
        modelResolver.resolvePreference(settingRepository.fetchMemoryModelPreference())
    } else {
        modelResolver.resolveFrozen(
            platformUid = checkNotNull(job.resolvedPlatformUid),
            modelId = checkNotNull(job.resolvedModelId)
        )
    }
    return when (resolution) {
        is MemoryModelResolution.Unavailable -> ClaimedMemoryModelBinding.Unavailable(resolution.reason)
        is MemoryModelResolution.Resolved -> {
            val boundJob = if (job.resolvedPlatformUid == null) {
                maintenanceScheduler.bindResolvedModel(
                    job = job,
                    platformUid = resolution.platform.uid,
                    modelId = resolution.platform.model
                )
            } else {
                job
            }
            ClaimedMemoryModelBinding.Resolved(boundJob, resolution.platform)
        }
    }
}
