package com.example.silverageassistant.data.weather

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.silverageassistant.domain.weather.GeoCoordinates
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

fun interface LocationNameResolver {
    suspend fun resolve(coordinates: GeoCoordinates): String?
}

class AndroidLocationNameResolver(
    context: Context,
) : LocationNameResolver {
    private val geocoder = Geocoder(
        context.applicationContext,
        Locale.SIMPLIFIED_CHINESE,
    )

    override suspend fun resolve(coordinates: GeoCoordinates): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            withTimeoutOrNull(GEOCODING_TIMEOUT_MILLIS) {
                resolveAddress(coordinates)?.cityLabel()
            }
        }.getOrNull()
    }

    private suspend fun resolveAddress(coordinates: GeoCoordinates): Address? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveAddressAsync(coordinates)
        } else {
            resolveAddressBlocking(coordinates)
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun resolveAddressAsync(coordinates: GeoCoordinates): Address? =
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(
                coordinates.latitude,
                coordinates.longitude,
                MAX_RESULTS,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) {
                            continuation.resume(addresses.firstOrNull())
                        }
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }

    @Suppress("DEPRECATION")
    private suspend fun resolveAddressBlocking(
        coordinates: GeoCoordinates,
    ): Address? = withContext(Dispatchers.IO) {
        geocoder.getFromLocation(
            coordinates.latitude,
            coordinates.longitude,
            MAX_RESULTS,
        )?.firstOrNull()
    }

    private fun Address.cityLabel(): String? =
        sequenceOf(locality, subAdminArea, adminArea)
            .filterNotNull()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?.removeSuffix("市")

    private companion object {
        const val MAX_RESULTS = 1
        const val GEOCODING_TIMEOUT_MILLIS = 5_000L
    }
}
