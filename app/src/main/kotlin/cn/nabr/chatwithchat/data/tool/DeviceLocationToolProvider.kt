package cn.nabr.chatwithchat.data.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class DeviceLocationToolProvider(
    private val locationReader: DeviceLocationReader
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.DeviceLocation

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        category = ToolCategory.Device,
        defaultEnabled = false,
        isSensitive = true,
        presentationKey = "device_location",
        iconKey = "location"
    )

    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPrivate
    override val permissionRequirements: List<ToolPermissionRequirement> = listOf(DeviceLocationPermissionRequirement)

    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = 1,
        maxCallsPerChat = 2,
        timeoutSeconds = 12,
        maxResultChars = 800
    )

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
        val timeoutMillis = config.locationReadTimeoutMillis(policy)
        val snapshot = locationReader.readLocation(timeoutMillis).getOrElse { throwable ->
            if (throwable is ToolPermissionDeniedException) throw throwable
            return call.errorResult(throwable.locationToolError())
        }

        return ToolResult(
            callId = call.id,
            name = call.name,
            content = snapshot.toToolContent(),
            metadata = buildMap {
                put("latitude", snapshot.latitude.formatCoordinate())
                put("longitude", snapshot.longitude.formatCoordinate())
                snapshot.accuracyMeters?.let { put("accuracy_meters", it.formatMeters()) }
                snapshot.altitudeMeters?.let { put("altitude_meters", it.formatMeters()) }
                snapshot.provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
                put("timestamp", Instant.ofEpochMilli(snapshot.timeMillis).toString())
            }
        )
    }

    private fun DeviceLocationSnapshot.toToolContent(): String = buildString {
        appendLine("Device location:")
        appendLine("Latitude: ${latitude.formatCoordinate()}")
        appendLine("Longitude: ${longitude.formatCoordinate()}")
        accuracyMeters?.let { appendLine("Accuracy meters: ${it.formatMeters()}") }
        altitudeMeters?.let { appendLine("Altitude meters: ${it.formatMeters()}") }
        provider?.takeIf { it.isNotBlank() }?.let { appendLine("Provider: $it") }
        appendLine("Timestamp: ${Instant.ofEpochMilli(timeMillis)}")
    }.trim()

    private fun ToolLoopConfig.locationReadTimeoutMillis(policy: ToolPolicy): Long {
        val toolTimeoutMillis = ((policy.timeoutSeconds ?: toolTimeoutSeconds).coerceAtLeast(2)) * 1_000L
        return (toolTimeoutMillis - TOOL_TIMEOUT_MARGIN_MILLIS)
            .coerceIn(MIN_LOCATION_READ_TIMEOUT_MILLIS, MAX_LOCATION_READ_TIMEOUT_MILLIS)
    }
}

interface DeviceLocationReader {
    suspend fun readLocation(timeoutMillis: Long): Result<DeviceLocationSnapshot>
}

data class DeviceLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val altitudeMeters: Double? = null,
    val provider: String? = null,
    val timeMillis: Long
)

class AndroidDeviceLocationReader(
    context: Context
) : DeviceLocationReader {
    private val appContext = context.applicationContext

    override suspend fun readLocation(timeoutMillis: Long): Result<DeviceLocationSnapshot> = try {
        Result.success(readLocationOrThrow(timeoutMillis))
    } catch (throwable: CancellationException) {
        throw throwable
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

    private suspend fun readLocationOrThrow(timeoutMillis: Long): DeviceLocationSnapshot {
        val permissions = appContext.locationPermissionStatus()
        if (!permissions.hasLocationPermission) {
            throw ToolPermissionDeniedException(
                toolName = ToolDefinition.DeviceLocation.name,
                missingRequirements = listOf(DeviceLocationPermissionRequirement)
            )
        }

        val locationManager = ContextCompat.getSystemService(appContext, LocationManager::class.java)
            ?: throw LocationToolException("location_service_unavailable")
        if (!locationManager.isLocationEnabled) {
            throw LocationToolException("location_services_disabled")
        }

        val providers = locationManager.candidateProviders(permissions.hasFineLocation)
        if (providers.isEmpty()) {
            throw LocationToolException("location_provider_unavailable")
        }

        val lastKnown = providers
            .mapNotNull { provider -> locationManager.safeLastKnownLocation(provider) }
            .bestLocation()

        lastKnown?.takeIf { it.isRecentEnough() }?.let { recentLocation ->
            return recentLocation.toSnapshot()
        }

        val current = locationManager.firstCurrentLocation(providers, timeoutMillis)
        return (current ?: lastKnown)
            ?.toSnapshot()
            ?: throw LocationToolException("location_unavailable")
    }

    private fun LocationManager.candidateProviders(hasFineLocation: Boolean): List<String> {
        val enabledProviders = runCatching { getProviders(true).toSet() }
            .getOrDefault(emptySet())
        val preferredProviders = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (hasFineLocation) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return preferredProviders.distinct().filter { provider -> provider in enabledProviders }
    }

    private fun LocationManager.safeLastKnownLocation(provider: String): Location? =
        runCatching { getLastKnownLocation(provider) }.getOrNull()

    private suspend fun LocationManager.safeCurrentLocation(provider: String): Location? =
        runCatching {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                getCurrentLocation(
                    provider,
                    cancellationSignal,
                    ContextCompat.getMainExecutor(appContext)
                ) { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
            }
        }.getOrNull()

    private suspend fun LocationManager.firstCurrentLocation(
        providers: List<String>,
        timeoutMillis: Long
    ): Location? {
        if (providers.isEmpty()) return null
        val perProviderTimeoutMillis = (timeoutMillis / providers.size)
            .coerceIn(MIN_PROVIDER_TIMEOUT_MILLIS, timeoutMillis.coerceAtLeast(MIN_PROVIDER_TIMEOUT_MILLIS))
        providers.forEach { provider ->
            val location = withTimeoutOrNull(perProviderTimeoutMillis) {
                safeCurrentLocation(provider)
            }
            if (location != null) return location
        }
        return null
    }

    private fun Context.locationPermissionStatus(): LocationPermissionStatus {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return LocationPermissionStatus(
            hasFineLocation = fine,
            hasLocationPermission = fine || coarse
        )
    }

    private fun Location.toSnapshot(): DeviceLocationSnapshot = DeviceLocationSnapshot(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        altitudeMeters = altitude.takeIf { hasAltitude() },
        provider = provider,
        timeMillis = time.takeIf { it > 0L } ?: System.currentTimeMillis()
    )

    private fun Location.isRecentEnough(): Boolean {
        val capturedAt = time.takeIf { it > 0L } ?: return false
        return System.currentTimeMillis() - capturedAt <= MAX_RECENT_LAST_KNOWN_AGE_MILLIS
    }

    private fun List<Location>.bestLocation(): Location? = sortedWith(
        compareByDescending<Location> { location -> location.time }
            .thenBy { location -> location.accuracy.takeIf { location.hasAccuracy() } ?: Float.MAX_VALUE }
    ).firstOrNull()

    private data class LocationPermissionStatus(
        val hasFineLocation: Boolean,
        val hasLocationPermission: Boolean
    )
}

object UnavailableDeviceLocationReader : DeviceLocationReader {
    override suspend fun readLocation(timeoutMillis: Long): Result<DeviceLocationSnapshot> =
        Result.failure(
            ToolPermissionDeniedException(
                toolName = ToolDefinition.DeviceLocation.name,
                missingRequirements = listOf(DeviceLocationPermissionRequirement)
            )
        )
}

private class LocationToolException(message: String) : IllegalStateException(message)

private fun Throwable.locationToolError(): String = message
    ?.takeIf { it.startsWith("location_") }
    ?: "location_failed:${message ?: this::class.simpleName.orEmpty()}"

private fun Double.formatCoordinate(): String = String.format(Locale.US, "%.6f", this)

private fun Number.formatMeters(): String = String.format(Locale.US, "%.1f", toDouble())

private const val TOOL_TIMEOUT_MARGIN_MILLIS = 1_500L
private const val MIN_LOCATION_READ_TIMEOUT_MILLIS = 1_000L
private const val MAX_LOCATION_READ_TIMEOUT_MILLIS = 4_500L
private const val MIN_PROVIDER_TIMEOUT_MILLIS = 750L
private const val MAX_RECENT_LAST_KNOWN_AGE_MILLIS = 5 * 60 * 1_000L

private val DeviceLocationPermissionRequirement = ToolPermissionRequirement(
    permissions = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ),
    label = "Location",
    deniedMessage = "The device_location tool requires Android Location permission. Ask the user to enable location permission for the app in Android system settings, then try again.",
    grantMode = ToolPermissionGrantMode.ANY_OF
)
