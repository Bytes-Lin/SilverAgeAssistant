package com.example.silverageassistant.data.weather

import com.example.silverageassistant.domain.weather.GeoCoordinates
import com.example.silverageassistant.testing.weatherSnapshot
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedWeatherRepositoryTest {
    @Test
    fun successfulLocationWeather_refresh_publishesLocationDerivedTimeZone() = runBlocking {
        var resolvedTimeZone: String? = null
        val repository = CachedWeatherRepository(
            locationProvider = DeviceLocationProvider { GeoCoordinates(31.2, 121.5) },
            remoteDataSource = WeatherRemoteDataSource { weatherSnapshot() },
            onLocationTimeZoneResolved = { resolvedTimeZone = it },
        )

        repository.getWeather()

        assertEquals("Asia/Shanghai", resolvedTimeZone)
    }

    @Test
    fun resolvedCity_isAttachedToWeatherFromTheSameCoordinates() = runBlocking {
        val coordinates = GeoCoordinates(31.2, 121.5)
        var resolverCoordinates: GeoCoordinates? = null
        val repository = CachedWeatherRepository(
            locationProvider = DeviceLocationProvider { coordinates },
            remoteDataSource = WeatherRemoteDataSource { weatherSnapshot() },
            locationNameResolver = LocationNameResolver {
                resolverCoordinates = it
                "上海"
            },
        )

        val result = repository.getWeather()

        assertEquals(coordinates, resolverCoordinates)
        assertEquals("上海", result.snapshot.locationName)
    }

    @Test
    fun repeatedQueryWithinOneMinute_usesSingleRemoteRequest() = runBlocking {
        var now = Instant.parse("2026-07-18T08:00:00Z")
        var remoteCalls = 0
        val repository = CachedWeatherRepository(
            locationProvider = DeviceLocationProvider { GeoCoordinates(31.2, 121.5) },
            remoteDataSource = WeatherRemoteDataSource {
                remoteCalls += 1
                weatherSnapshot(now)
            },
            cacheTtl = Duration.ofMinutes(1),
            now = { now },
        )

        val first = repository.getWeather()
        now = now.plusSeconds(59)
        val second = repository.getWeather()

        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
        assertEquals(1, remoteCalls)
    }

    @Test
    fun queryAfterOneMinute_refreshesRemoteWeather() = runBlocking {
        var now = Instant.parse("2026-07-18T08:00:00Z")
        var remoteCalls = 0
        val repository = CachedWeatherRepository(
            locationProvider = DeviceLocationProvider { GeoCoordinates(31.2, 121.5) },
            remoteDataSource = WeatherRemoteDataSource {
                remoteCalls += 1
                weatherSnapshot(now)
            },
            cacheTtl = Duration.ofMinutes(1),
            now = { now },
        )

        repository.getWeather()
        now = now.plusSeconds(60)
        val refreshed = repository.getWeather()

        assertFalse(refreshed.fromCache)
        assertEquals(2, remoteCalls)
    }

    @Test
    fun expiredCache_isReturnedAsStaleWhenRefreshFails() = runBlocking {
        var now = Instant.parse("2026-07-18T08:00:00Z")
        var shouldFail = false
        val repository = CachedWeatherRepository(
            locationProvider = DeviceLocationProvider { GeoCoordinates(31.2, 121.5) },
            remoteDataSource = WeatherRemoteDataSource {
                if (shouldFail) error("network unavailable")
                weatherSnapshot(now)
            },
            cacheTtl = Duration.ofMinutes(1),
            now = { now },
        )
        repository.getWeather()
        now = now.plusSeconds(61)
        shouldFail = true

        val fallback = repository.getWeather()

        assertTrue(fallback.fromCache)
        assertTrue(fallback.isStale)
    }
}
