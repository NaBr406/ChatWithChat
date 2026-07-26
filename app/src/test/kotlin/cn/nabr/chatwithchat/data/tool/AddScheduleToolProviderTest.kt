package cn.nabr.chatwithchat.data.tool

import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddScheduleToolProviderTest {
    @Test
    fun `schedule tool sends parsed event to launcher`() = runBlocking {
        val launcher = CapturingScheduleLauncher()
        val provider = AddScheduleToolProvider(launcher, ZoneId.of("Asia/Shanghai"))

        val result = provider.execute(
            ToolCall(
                id = "call_schedule",
                name = "add_schedule",
                arguments = """{"title":"Review","start_time":"2026-07-25T10:00:00+08:00","duration_minutes":90,"location":"Room A"}"""
            ),
            ToolLoopConfig.Default
        )

        assertFalse(result.isError)
        assertEquals("Review", launcher.request?.title)
        assertEquals("2026-07-25T02:00:00Z", launcher.request?.startAt.toString())
        assertEquals("2026-07-25T03:30:00Z", launcher.request?.endAt.toString())
        assertEquals("Room A", launcher.request?.location)
        assertEquals("Review", result.metadata["title"])
    }

    @Test
    fun `schedule tool rejects an end before start`() = runBlocking {
        val launcher = CapturingScheduleLauncher()
        val result = AddScheduleToolProvider(launcher).execute(
            ToolCall(
                id = "call_schedule",
                name = "add_schedule",
                arguments = """{"title":"Review","start_time":"2026-07-25T10:00:00Z","end_time":"2026-07-25T09:00:00Z"}"""
            ),
            ToolLoopConfig.Default
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("end_time_must_be_after_start_time"))
        assertEquals(null, launcher.request)
    }

    @Test
    fun `schedule tool uses system permission without per call approval`() {
        val provider = AddScheduleToolProvider(CapturingScheduleLauncher())

        assertEquals(ToolEffect.EXTERNAL_WRITE, provider.securityPolicy.effect)
        assertEquals(ToolApprovalPolicy.REQUIRE_SYSTEM_PERMISSION, provider.securityPolicy.approvalPolicy)
        assertEquals(
            listOf(
                "android.permission.WRITE_CALENDAR"
            ),
            provider.permissionRequirements.single().requestedPermissions()
        )
        assertEquals(ToolPermissionGrantMode.ALL_OF, provider.permissionRequirements.single().grantMode)
        assertEquals("Review", provider.progressLabel(ToolCall("id", "add_schedule", """{"title":"Review"}""")))
    }

    private class CapturingScheduleLauncher : ScheduleEventLauncher {
        var request: ScheduleEventRequest? = null

        override fun launch(request: ScheduleEventRequest): Result<Unit> {
            this.request = request
            return Result.success(Unit)
        }
    }
}
