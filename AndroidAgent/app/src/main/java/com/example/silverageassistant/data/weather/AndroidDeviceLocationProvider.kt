package com.example.silverageassistant.data.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.example.silverageassistant.domain.weather.GeoCoordinates
import com.example.silverageassistant.domain.weather.LocationPermissionRequiredException
import com.example.silverageassistant.domain.weather.LocationUnavailableException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

fun interface DeviceLocationProvider {
    suspend fun currentCoordinates(): GeoCoordinates
}

class AndroidDeviceLocationProvider(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
) : DeviceLocationProvider {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun currentCoordinates(): GeoCoordinates {
        ensurePermission()
        recentLastKnownLocation()?.let { return it.toCoordinates() }
        val providers = availableCurrentLocationProviders()
        val location = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
            requestCurrentLocation(providers)
        } ?: throw LocationUnavailableException()
        return location.toCoordinates()
    }

    private fun ensurePermission() {
        if (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw LocationPermissionRequiredException()
        }
    }

    @Suppress("MissingPermission")
    private fun recentLastKnownLocation(): Location? {
        val nowMillis = clock.millis()
        return locationManager.getProviders(true)
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter { location ->
                val age = nowMillis - location.time
                age in 0..LAST_LOCATION_MAX_AGE.toMillis()
            }
            .maxByOrNull(Location::getTime)
    }

    private fun availableCurrentLocationProviders(): List<String> {
        val available = locationManager.getProviders(true).toSet()
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            FUSED_PROVIDER_NAME,
        ).filter(available::contains)
            .ifEmpty { throw LocationUnavailableException() }
    }

    @Suppress("MissingPermission")
    private suspend fun requestCurrentLocation(providers: List<String>): Location? =
        suspendCancellableCoroutine { continuation ->
            val attempts = providers.associateWith { CancellationSignal() }
            val remaining = AtomicInteger(attempts.size)
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                attempts.values.forEach(CancellationSignal::cancel)
            }
            attempts.forEach { (provider, cancellationSignal) ->
                if (!continuation.isActive) return@forEach
                try {
                    LocationManagerCompat.getCurrentLocation(
                        locationManager,
                        provider,
                        cancellationSignal,
                        ContextCompat.getMainExecutor(appContext),
                    ) { location ->
                        when {
                            location != null && completed.compareAndSet(false, true) -> {
                                attempts.values
                                    .filterNot { it === cancellationSignal }
                                    .forEach(CancellationSignal::cancel)
                                if (continuation.isActive) continuation.resume(location)
                            }
                            location == null &&
                                remaining.decrementAndGet() == 0 &&
                                completed.compareAndSet(false, true) -> {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    if (
                        remaining.decrementAndGet() == 0 &&
                        completed.compareAndSet(false, true) &&
                        continuation.isActive
                    ) {
                        continuation.resume(null)
                    }
                }
            }
        }

    private fun Location.toCoordinates() = GeoCoordinates(
        latitude = latitude,
        longitude = longitude,
    )

    private companion object {
        val LAST_LOCATION_MAX_AGE: Duration = Duration.ofHours(6)
        const val LOCATION_TIMEOUT_MILLIS = 15_000L
        const val FUSED_PROVIDER_NAME = "fused"
    }
}
