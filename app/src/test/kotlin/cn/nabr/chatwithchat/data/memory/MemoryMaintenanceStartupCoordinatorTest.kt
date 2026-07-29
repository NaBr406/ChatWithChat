package cn.nabr.chatwithchat.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `optional failures and incomplete receipts record only fixed bounded codes`() = runBlocking {
        val secret = "prompt-body C:/private/memory.md credential=secret-token"
        val failures = mutableListOf<Pair<String, String>>()

        runMemoryStartupTasks(
            enqueueRepair = { error(secret) },
            provision = { error(secret) },
            recoverReceipts = { INCOMPLETE_RECOVERY },
            bootstrap = { error("must not run") },
            repair = {},
            recordFailure = { errorCode, status -> failures += errorCode to status }
        )

        assertEquals(
            listOf(
                "startup_repair_enqueue_failed" to MemoryActivityStatus.FAILED,
                "startup_embedding_provision_failed" to MemoryActivityStatus.FAILED,
                "startup_receipt_recovery_incomplete" to MemoryActivityStatus.BLOCKED
            ),
            failures
        )
        assertFalse(failures.joinToString().contains(secret))
    }

    @Test
    fun `final repair failure is recorded before the original failure propagates`() {
        val failures = mutableListOf<Pair<String, String>>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                runMemoryStartupTasks(
                    enqueueRepair = {},
                    provision = {},
                    recoverReceipts = { COMPLETE_RECOVERY },
                    bootstrap = {},
                    repair = { error("secret repair details") },
                    recordFailure = { errorCode, status -> failures += errorCode to status }
                )
            }
        }

        assertEquals(
            listOf("startup_final_repair_failed" to MemoryActivityStatus.FAILED),
            failures
        )
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
