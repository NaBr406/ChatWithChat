package cn.nabr.chatwithchat.data.tool

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLocationToolProviderTest {
    @Test
    fun `device location tool returns bounded deterministic location`() = runBlocking {
        val provider = DeviceLocationToolProvider(
            StaticDeviceLocationReader(
                Result.success(
                    DeviceLocationSnapshot(
                        latitude = 31.230416,
                        longitude = 121.473701,
                        accuracyMeters = 12.4f,
                        altitudeMeters = 8.0,
                        provider = "test",
                        timeMillis = 1_783_000_000_000L
                    )
                )
            )
        )

        val result = provider.execute(
            ToolCall(
                id = "call_location",
                name = "device_location",
                arguments = "{}"
            ),
            ToolLoopConfig.Default
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("Device location:"))
        assertTrue(result.content.contains("Latitude: 31.230416"))
        assertTrue(result.content.contains("Longitude: 121.473701"))
        assertEquals("31.230416", result.metadata["latitude"])
        assertEquals("121.473701", result.metadata["longitude"])
        assertEquals("12.4", result.metadata["accuracy_meters"])
        assertEquals("test", result.metadata["provider"])
    }

    @Test
    fun `device location tool returns recoverable permission error`() = runBlocking {
        val provider = DeviceLocationToolProvider(
            StaticDeviceLocationReader(Result.failure(IllegalStateException("location_permission_required")))
        )

        val result = provider.execute(
            ToolCall(
                id = "call_location",
                name = "device_location",
                arguments = "{}"
            ),
            ToolLoopConfig.Default
        )

        assertTrue(result.isError)
        assertEquals("location_permission_required", result.content)
    }

    @Test
    fun `device location tool leaves margin before executor timeout`() = runBlocking {
        val reader = CapturingDeviceLocationReader()
        val provider = DeviceLocationToolProvider(reader)

        val result = provider.execute(
            ToolCall(
                id = "call_location",
                name = "device_location",
                arguments = "{}"
            ),
            ToolLoopConfig(toolTimeoutSeconds = 30)
        )

        assertFalse(result.isError)
        assertEquals(4_500L, reader.timeoutMillis)
    }

    @Test
    fun `device location tool exposes low read only policy`() {
        val policy = DeviceLocationToolProvider(UnavailableDeviceLocationReader).policy

        assertEquals(1, policy.maxCallsPerRequest)
        assertEquals(2, policy.maxCallsPerChat)
        assertEquals(12L, policy.timeoutSeconds)
        assertEquals(800, policy.maxResultChars)
    }

    @Test
    fun `device location tool declares Android location permission requirement`() {
        val requirements = DeviceLocationToolProvider(UnavailableDeviceLocationReader).permissionRequirements

        assertEquals(1, requirements.size)
        assertEquals(ToolPermissionGrantMode.ANY_OF, requirements.single().grantMode)
        assertEquals(
            listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"
            ),
            requirements.single().requestedPermissions()
        )
    }

    private class StaticDeviceLocationReader(
        private val result: Result<DeviceLocationSnapshot>
    ) : DeviceLocationReader {
        override suspend fun readLocation(timeoutMillis: Long): Result<DeviceLocationSnapshot> = result
    }

    private class CapturingDeviceLocationReader : DeviceLocationReader {
        var timeoutMillis: Long? = null

        override suspend fun readLocation(timeoutMillis: Long): Result<DeviceLocationSnapshot> {
            this.timeoutMillis = timeoutMillis
            return Result.success(
                DeviceLocationSnapshot(
                    latitude = 1.0,
                    longitude = 2.0,
                    timeMillis = 1_783_000_000_000L
                )
            )
        }
    }
}
