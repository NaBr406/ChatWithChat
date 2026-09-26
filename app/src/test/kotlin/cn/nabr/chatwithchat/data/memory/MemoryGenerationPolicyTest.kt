package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryGenerationPolicyTest {
    @Test
    fun `temporary observation cannot write long term memory`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MemoryGenerationPolicy.validate(
                memoryClass = MemoryClass.TEMPORARY_OBSERVATION,
                type = "project_context",
                scope = "project:chatwithchat",
                source = MemorySource.EXPLICIT_USER_STATEMENT,
                recallState = MemoryRecallState.QUERY,
                destination = MemoryBatchDestination.LONG_TERM
            )
        }

        assertEquals("temporary_observation_long_term_forbidden", error.message)
    }

    @Test
    fun `active project requires project scope and query recall`() {
        val scopeError = assertThrows(IllegalArgumentException::class.java) {
            MemoryGenerationPolicy.validate(
                memoryClass = MemoryClass.ACTIVE_PROJECT,
                type = "project_context",
                scope = MemoryScope.GENERAL,
                source = MemorySource.EXPLICIT_USER_STATEMENT,
                recallState = MemoryRecallState.QUERY,
                longTermWrite = true
            )
        }
        assertEquals("active_project_scope_required", scopeError.message)

        val recallError = assertThrows(IllegalArgumentException::class.java) {
            MemoryGenerationPolicy.validate(
                memoryClass = MemoryClass.ACTIVE_PROJECT,
                type = "project_context",
                scope = "project:chatwithchat",
                source = MemorySource.EXPLICIT_USER_STATEMENT,
                recallState = MemoryRecallState.CORE,
                longTermWrite = true
            )
        }
        assertEquals("active_project_query_required", recallError.message)
    }

    @Test
    fun `active project uses refreshable lifecycle`() {
        val normalized = MemoryGenerationPolicy.validate(
            memoryClass = MemoryClass.ACTIVE_PROJECT,
            type = "project_context",
            scope = "project:chatwithchat",
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            recallState = MemoryRecallState.QUERY,
            longTermWrite = true
        )

        assertEquals(MemoryClass.ACTIVE_PROJECT, normalized)
    }

    @Test
    fun `assistant inference cannot enter core`() {
        listOf("interest", "communication_style", "stable_profile", "boundary").forEach { type ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                MemoryGenerationPolicy.validate(
                    memoryClass = MemoryClass.DURABLE_FACT,
                    type = type,
                    scope = MemoryScope.GENERAL,
                    source = MemorySource.ASSISTANT_INFERRED,
                    recallState = MemoryRecallState.CORE,
                    longTermWrite = true
                )
            }
            assertEquals("assistant_inferred_core_forbidden", error.message)
        }
    }
}
