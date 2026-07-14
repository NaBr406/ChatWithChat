package dev.chungjungsoo.gptmobile.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryMaintenanceStartupCoordinatorTest {

    @Test
    fun `repair failure propagates after optional startup steps run once`() {
        var enqueueCalls = 0
        var provisionCalls = 0
        var bootstrapCalls = 0
        var repairCalls = 0

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                runMemoryStartupTasks(
                    enqueueRepair = { enqueueCalls += 1 },
                    provision = { provisionCalls += 1 },
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
        assertEquals(1, bootstrapCalls)
        assertEquals(1, repairCalls)
    }

    @Test
    fun `repair still runs when durable enqueue fails`() = runBlocking {
        var repairCalls = 0

        runMemoryStartupTasks(
            enqueueRepair = { error("work manager unavailable") },
            provision = {},
            bootstrap = {},
            repair = { repairCalls += 1 }
        )

        assertEquals(1, repairCalls)
    }

    @Test
    fun `durable repair wake is enqueued before provisioning starts`() = runBlocking {
        val order = mutableListOf<String>()

        runMemoryStartupTasks(
            enqueueRepair = { order += "enqueue" },
            provision = { order += "provision" },
            bootstrap = { order += "bootstrap" },
            repair = { order += "repair" }
        )

        assertEquals(listOf("enqueue", "provision", "bootstrap", "repair"), order)
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
            bootstrap = {
                order += "bootstrap"
                error("bootstrap unavailable")
            },
            repair = { order += "repair" }
        )

        assertEquals(listOf("enqueue", "provision", "bootstrap", "repair"), order)
    }
}
