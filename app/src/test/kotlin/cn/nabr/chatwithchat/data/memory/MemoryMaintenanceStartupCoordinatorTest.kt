package cn.nabr.chatwithchat.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryMaintenanceStartupCoordinatorTest {

    @Test
    fun `repair failure propagates after optional startup steps run once`() {
        var enqueueCalls = 0
        var provisionCalls = 0
        var recoveryCalls = 0
        var bootstrapCalls = 0
        var repairCalls = 0

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                runMemoryStartupTasks(
                    enqueueRepair = { enqueueCalls += 1 },
                    provision = { provisionCalls += 1 },
                    recoverReceipts = {
                        recoveryCalls += 1
                        COMPLETE_RECOVERY
                    },
                    bootstrap = { bootstrapCalls += 1 },
                    repair = {
                        repairCalls += 1
                        error("repair failed")
                    }
                )
            }
        }

        assertEquals(1, enqueueCalls)
        assertEquals(1, provisionCalls)
        assertEquals(1, recoveryCalls)
        assertEquals(1, bootstrapCalls)
        assertEquals(1, repairCalls)
    }

    @Test
    fun `repair still runs when durable enqueue fails`() = runBlocking {
        var repairCalls = 0

        runMemoryStartupTasks(
            enqueueRepair = { error("work manager unavailable") },
            provision = {},
            recoverReceipts = { COMPLETE_RECOVERY },
            bootstrap = {},
            repair = { repairCalls += 1 }
        )

        assertEquals(1, repairCalls)
    }

    @Test
    fun `receipt recovery completes before bootstrap starts`() = runBlocking {
        val order = mutableListOf<String>()

        runMemoryStartupTasks(
            enqueueRepair = { order += "enqueue" },
            provision = { order += "provision" },
            recoverReceipts = {
                order += "recovery"
                COMPLETE_RECOVERY
            },
            bootstrap = { order += "bootstrap" },
            repair = { order += "repair" }
        )

        assertEquals(listOf("enqueue", "provision", "recovery", "bootstrap", "repair"), order)
    }

    @Test
    fun `failed receipt recovery skips bootstrap but final repair still runs`() = runBlocking {
        val order = mutableListOf<String>()

        runMemoryStartupTasks(
            enqueueRepair = { order += "enqueue" },
            provision = {
                order += "provision"
            },
            recoverReceipts = {
                order += "recovery"
                INCOMPLETE_RECOVERY
            },
            bootstrap = {
                order += "bootstrap"
            },
            repair = { order += "repair" }
        )

        assertEquals(listOf("enqueue", "provision", "recovery", "repair"), order)
    }

    @Test
    fun `repair still runs when provisioning and bootstrap fail`() = runBlocking {
        val order = mutableListOf<String>()

        runMemoryStartupTasks(
            enqueueRepair = { order += "enqueue" },
            provision = {
                order += "provision"
                error("model unavailable")
            },
            recoverReceipts = {
                order += "recovery"
                COMPLETE_RECOVERY
            },
            bootstrap = {
                order += "bootstrap"
                error("bootstrap unavailable")
            },
            repair = { order += "repair" }
        )

        assertEquals(listOf("enqueue", "provision", "recovery", "bootstrap", "repair"), order)
    }

    private companion object {
        val COMPLETE_RECOVERY = MemoryMutationRecoveryResult(
            committedCount = 0,
            conflictCount = 0,
            failedCount = 0,
            recoveredSemanticCount = 0,
            retryGenerations = emptySet(),
            hasMore = false
        )
        val INCOMPLETE_RECOVERY = COMPLETE_RECOVERY.copy(
            failedCount = 1,
            retryGenerations = setOf(7L)
        )
    }
}
