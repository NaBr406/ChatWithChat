package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.repository.SettingRepository
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryModelDependencyNotifierTest {
    @Test
    fun `available model reopens matching work and schedules semantic processing once`() = runBlocking {
        val fixture = Fixture()

        repeat(3) { fixture.notifier.onDependenciesChanged() }

        val reopened = checkNotNull(fixture.dao.getById(fixture.job.jobId))
        assertEquals(MemoryMaintenanceJobStatus.PENDING, reopened.status)
        assertEquals(0, reopened.attempts)
        assertEquals(fixture.job.retryCycle + 1, reopened.retryCycle)
        assertEquals(listOf(MemoryMaintenanceJobFamily.SEMANTIC), fixture.workEnqueuer.works.map { work -> work.family })
    }

    @Test
    fun `unavailable fixed model stays blocked through repeated unrelated settings changes`() = runBlocking {
        val fixture = Fixture()
        fixture.preference = MemoryModelPreference.Fixed("deleted-provider", "missing-model")

        repeat(3) { fixture.notifier.onDependenciesChanged() }

        // Another available model must not substitute for the unavailable fixed selection.
        assertEquals(fixture.job, fixture.dao.getById(fixture.job.jobId))
        assertEquals(0, fixture.workEnqueuer.enqueueCalls)
    }

    @Test
    fun `disabled fixed model reopens only after that model is enabled`() = runBlocking {
        val fixture = Fixture()
        fixture.preference = MemoryModelPreference.Fixed(fixture.platform.uid, fixture.platform.model)
        fixture.models[0] = fixture.models[0].copy(enabled = false)

        repeat(3) { fixture.notifier.onDependenciesChanged() }

        assertEquals(fixture.job, fixture.dao.getById(fixture.job.jobId))
        assertEquals(0, fixture.workEnqueuer.enqueueCalls)

        fixture.models[0] = fixture.models[0].copy(enabled = true)
        repeat(3) { fixture.notifier.onDependenciesChanged() }

        val reopened = checkNotNull(fixture.dao.getById(fixture.job.jobId))
        assertEquals(MemoryMaintenanceJobStatus.PENDING, reopened.status)
        assertEquals(fixture.job.retryCycle + 1, reopened.retryCycle)
        assertEquals(1, fixture.workEnqueuer.enqueueCalls)
    }

    @Test
    fun `unavailable auto selection stays blocked until an eligible provider exists`() = runBlocking {
        val fixture = Fixture()
        fixture.platforms[0] = fixture.platform.copy(enabled = false)

        repeat(3) { fixture.notifier.onDependenciesChanged() }

        assertEquals(fixture.job, fixture.dao.getById(fixture.job.jobId))
        assertEquals(0, fixture.workEnqueuer.enqueueCalls)

        fixture.platforms[0] = fixture.platform
        fixture.notifier.onDependenciesChanged()

        assertEquals(MemoryMaintenanceJobStatus.PENDING, fixture.dao.getById(fixture.job.jobId)?.status)
        assertEquals(1, fixture.workEnqueuer.enqueueCalls)
    }

    @Test
    fun `changing an unavailable fixed selection to auto reopens unbound work`() = runBlocking {
        val fixture = Fixture()
        fixture.preference = MemoryModelPreference.Fixed("deleted-provider", "missing-model")
        fixture.notifier.onDependenciesChanged()
        assertEquals(fixture.job, fixture.dao.getById(fixture.job.jobId))

        fixture.preference = MemoryModelPreference.Auto
        fixture.notifier.onDependenciesChanged()

        assertEquals(MemoryMaintenanceJobStatus.PENDING, fixture.dao.getById(fixture.job.jobId)?.status)
        assertEquals(1, fixture.workEnqueuer.enqueueCalls)
    }

    @Test
    fun `dependency change without matching jobs does not schedule work`() = runBlocking {
        val fixture = Fixture(hasBlockedJob = false)

        fixture.notifier.onDependenciesChanged()

        assertEquals(0, fixture.workEnqueuer.enqueueCalls)
    }

    private class Fixture(hasBlockedJob: Boolean = true) {
        val platform = PlatformV2(
            uid = "available-provider",
            name = "Available provider",
            compatibleType = ClientType.CUSTOM,
            enabled = true,
            apiUrl = "https://example.test/v1",
            token = "test-token",
            model = "memory-model"
        )
        val platforms = mutableListOf(platform)
        val models = mutableListOf(
            PlatformModelV2(platformUid = platform.uid, modelId = platform.model, displayName = platform.model)
        )
        var preference: MemoryModelPreference = MemoryModelPreference.Auto
        val job = MemoryMaintenanceJob(
            jobId = "blocked-memory-model",
            type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
            status = MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY,
            idempotencyKey = "blocked-memory-model",
            payloadJson = "{}",
            attempts = 1,
            lastError = MemoryModelUnavailableReason.FROZEN_MODEL_UNAVAILABLE.code,
            createdAt = 1,
            startedAt = null,
            updatedAt = 1,
            nextRunAt = null,
            family = MemoryMaintenanceJobFamily.SEMANTIC,
            retryCycle = 48,
            blockedReason = MemoryModelUnavailableReason.FROZEN_MODEL_UNAVAILABLE.code
        )
        val dao = InMemoryMaintenanceJobDao(if (hasBlockedJob) listOf(job) else emptyList())
        val workEnqueuer = RecordingWorkEnqueuer()
        private val settings = object : SettingRepository by FakeMaintenanceSettingRepository(
            memoryEnabled = true,
            platforms = platforms,
            platformModels = models
        ) {
            override suspend fun fetchMemoryModelPreference(): MemoryModelPreference = preference
        }
        val notifier = SchedulerMemoryModelDependencyNotifier(
            schedulerProvider = Provider { MemoryMaintenanceScheduler(dao) },
            workEnqueuer = workEnqueuer,
            modelResolverProvider = Provider { MemoryModelResolver(settings) }
        )
    }
}
