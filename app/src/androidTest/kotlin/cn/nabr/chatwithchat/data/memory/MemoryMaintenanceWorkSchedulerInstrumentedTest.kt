package cn.nabr.chatwithchat.data.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryMaintenanceWorkSchedulerInstrumentedTest {
    @Test
    fun immediateRequests_useFamilyWorkersConstraintsAndVersionedNames() {
        val cases = listOf(
            FamilyCase(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                workerClassName = MemorySemanticWorker::class.java.name,
                networkType = NetworkType.CONNECTED,
                uniqueWorkName = "memory_maintenance_semantic_v1_immediate"
            ),
            FamilyCase(
                family = MemoryMaintenanceJobFamily.INDEX,
                workerClassName = MemoryIndexWorker::class.java.name,
                networkType = NetworkType.NOT_REQUIRED,
                uniqueWorkName = "memory_maintenance_index_v1_immediate"
            ),
            FamilyCase(
                family = MemoryMaintenanceJobFamily.REPAIR,
                workerClassName = MemoryRepairWorker::class.java.name,
                networkType = NetworkType.NOT_REQUIRED,
                uniqueWorkName = "memory_maintenance_repair_v2_immediate"
            )
        )

        cases.forEach { case ->
            val plan = MemoryMaintenanceWorkScheduler.createPlan(case.family)

            assertEquals(case.workerClassName, plan.request.workSpec.workerClassName)
            assertEquals(case.networkType, plan.request.workSpec.constraints.requiredNetworkType)
            assertEquals(0L, plan.request.workSpec.initialDelay)
            assertEquals(case.uniqueWorkName, plan.uniqueWorkName)
            assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, plan.existingWorkPolicy)
        }
    }

    @Test
    fun delayedRequests_useSeparateNamesReplacePolicyAndRequestedDelay() {
        val cases = listOf(
            MemoryMaintenanceJobFamily.SEMANTIC to "memory_maintenance_semantic_v1_delayed",
            MemoryMaintenanceJobFamily.INDEX to "memory_maintenance_index_v1_delayed",
            MemoryMaintenanceJobFamily.REPAIR to "memory_maintenance_repair_v2_delayed"
        )

        cases.forEach { (family, uniqueWorkName) ->
            val plan = MemoryMaintenanceWorkScheduler.createPlan(family, delaySeconds = 90)

            assertEquals(90_000L, plan.request.workSpec.initialDelay)
            assertEquals(MemoryMaintenanceWakeWorker::class.java.name, plan.request.workSpec.workerClassName)
            assertEquals(NetworkType.NOT_REQUIRED, plan.request.workSpec.constraints.requiredNetworkType)
            assertEquals(family, plan.request.workSpec.input.getString(MemoryMaintenanceWakeWorker.INPUT_FAMILY))
            assertEquals(uniqueWorkName, plan.uniqueWorkName)
            assertEquals(ExistingWorkPolicy.REPLACE, plan.existingWorkPolicy)
        }
    }

    @Test
    fun unknownFamily_failsBeforeCreatingARequest() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryMaintenanceWorkScheduler.createPlan("unknown")
        }
    }

    private data class FamilyCase(
        val family: String,
        val workerClassName: String,
        val networkType: NetworkType,
        val uniqueWorkName: String
    )
}
