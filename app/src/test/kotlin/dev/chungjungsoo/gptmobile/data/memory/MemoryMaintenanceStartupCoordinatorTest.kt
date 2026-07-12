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
            repair = { order += "repair" }
        )

        assertEquals(listOf("enqueue", "migrate", "repair"), order)
    }
}
