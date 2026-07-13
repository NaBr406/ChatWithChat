package dev.chungjungsoo.gptmobile.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryMaintenanceStartupCoordinatorTest {

    @Test
    fun `repair still runs when legacy migration fails`() = runBlocking {
        var migrationCalls = 0
        var enqueueCalls = 0
        var repairCalls = 0

        runMemoryStartupTasks(
            migrate = {
                migrationCalls += 1
                error("migration failed")
            },
            enqueueRepair = { enqueueCalls += 1 },
            provision = {},
            bootstrap = {},
            repair = { repairCalls += 1 }
        )

        assertEquals(1, migrationCalls)
        assertEquals(1, enqueueCalls)
        assertEquals(1, repairCalls)
    }

    @Test
    fun `repair failure does not repeat migration`() {
        var migrationCalls = 0
        var enqueueCalls = 0
        var repairCalls = 0

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                runMemoryStartupTasks(
                    migrate = { migrationCalls += 1 },
                    enqueueRepair = { enqueueCalls += 1 },
                    provision = {},
                    bootstrap = {},
                    repair = {
                        repairCalls += 1
                        error("repair failed")
                    }
                )
            }
        }

        assertEquals(1, migrationCalls)
        assertEquals(1, enqueueCalls)
        assertEquals(1, repairCalls)
    }

    @Test
    fun `repair still runs when durable enqueue fails`() = runBlocking {
        var repairCalls = 0

        runMemoryStartupTasks(
            migrate = {},
            enqueueRepair = { error("work manager unavailable") },
            provision = {},
            bootstrap = {},
            repair = { repairCalls += 1 }
        )

        assertEquals(1, repairCalls)
    }

    @Test
    fun `durable repair wake is enqueued before migration starts`() = runBlocking {
        val order = mutableListOf<String>()

        runMemoryStartupTasks(
            migrate = { order += "migrate" },
            enqueueRepair = { order += "enqueue" },
            provision = { order += "provision" },
            bootstrap = { order += "bootstrap" },
            repair = { order += "repair" }
        )

        assertEquals(listOf("enqueue", "migrate", "provision", "bootstrap", "repair"), order)
    }

    @Test
    fun `repair still runs when provisioning and bootstrap fail`() = runBlocking {
        val order = mutableListOf<String>()

        runMemoryStartupTasks(
            enqueueRepair = { order += "enqueue" },
            migrate = { order += "migrate" },
            provision = {
                order += "provision"
                error("model unavailable")
            },
            bootstrap = {
                order += "bootstrap"
                error("bootstrap unavailable")
            },
            repair = { order += "repair" }
        )

        assertEquals(listOf("enqueue", "migrate", "provision", "bootstrap", "repair"), order)
    }
}
