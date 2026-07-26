package cn.nabr.chatwithchat.data.tool

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetAlarmToolProviderTest {
    @Test
    fun `alarm tool sends time message and repeat days to launcher`() = runBlocking {
        val launcher = CapturingAlarmLauncher()
        val provider = SetAlarmToolProvider(launcher)

        val result = provider.execute(
            ToolCall(
                id = "call_alarm",
                name = "set_alarm",
                arguments = """{"hour":7,"minute":30,"message":"Stand up","days":[2,3,2]}"""
            ),
            ToolLoopConfig.Default
        )

        assertFalse(result.isError)
        assertEquals(7, launcher.request?.hour)
        assertEquals(30, launcher.request?.minute)
        assertEquals("Stand up", launcher.request?.message)
        assertEquals(listOf(2, 3), launcher.request?.days)
        assertEquals("07:30", provider.progressLabel(ToolCall("id", "set_alarm", """{"hour":7,"minute":30}""")))
    }

    @Test
    fun `alarm tool rejects values outside clock ranges`() = runBlocking {
        val launcher = CapturingAlarmLauncher()
        val result = SetAlarmToolProvider(launcher).execute(
            ToolCall(
                id = "call_alarm",
                name = "set_alarm",
                arguments = """{"hour":24,"minute":0}"""
            ),
            ToolLoopConfig.Default
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("hour_out_of_range"))
        assertEquals(null, launcher.request)
    }

    @Test
    fun `alarm tool uses system permission instead of per call approval`() {
        val provider = SetAlarmToolProvider(CapturingAlarmLauncher())

        assertEquals(ToolEffect.EXTERNAL_WRITE, provider.securityPolicy.effect)
        assertEquals(ToolApprovalPolicy.REQUIRE_SYSTEM_PERMISSION, provider.securityPolicy.approvalPolicy)
        assertEquals(
            listOf(
                "com.android.alarm.permission.SET_ALARM"
            ),
            provider.permissionRequirements.single().requestedPermissions()
        )
    }

    private class CapturingAlarmLauncher : AlarmLauncher {
        var request: AlarmRequest? = null

        override fun launch(request: AlarmRequest): Result<Unit> {
            this.request = request
            return Result.success(Unit)
        }
    }
}
