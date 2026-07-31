package cn.nabr.chatwithchat.presentation.ui.memory

import cn.nabr.chatwithchat.data.memory.MemoryLongTermConsolidationRunResult
import cn.nabr.chatwithchat.data.memory.MemoryLongTermPlanResult
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobStatus
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceProcessResult
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryViewModelTest {

    @Test
    fun `successful manual run reports completed`() {
        val result = MemoryLongTermConsolidationRunResult(
            plan = MemoryLongTermPlanResult(scheduled = true, jobId = "job", reason = "manual"),
            process = MemoryMaintenanceProcessResult(
                processedCount = 1,
                succeededCount = 1,
                retryableCount = 0,
                terminalCount = 0,
                blockedCount = 0
            ),
            finalJobStatus = MemoryMaintenanceJobStatus.SUCCEEDED
        )

        assertEquals(LongTermConsolidationUiResult.COMPLETED, result.toUiResult())
    }

    @Test
    fun `retryable manual run remains started while background retry is pending`() {
        val result = MemoryLongTermConsolidationRunResult(
            plan = MemoryLongTermPlanResult(scheduled = true, jobId = "job", reason = "manual"),
            process = MemoryMaintenanceProcessResult(
                processedCount = 0,
                succeededCount = 0,
                retryableCount = 0,
                terminalCount = 0,
                blockedCount = 0
            ),
            finalJobStatus = MemoryMaintenanceJobStatus.FAILED_RETRYABLE
        )

        assertEquals(LongTermConsolidationUiResult.STARTED, result.toUiResult())
    }

    @Test
    fun `terminal active job is reported as failed instead of already running`() {
        val result = MemoryLongTermConsolidationRunResult(
            plan = MemoryLongTermPlanResult(
                scheduled = false,
                jobId = "job",
                reason = "active_checkpoint"
            ),
            finalJobStatus = MemoryMaintenanceJobStatus.FAILED_TERMINAL
        )

        assertEquals(LongTermConsolidationUiResult.FAILED, result.toUiResult())
    }
}
