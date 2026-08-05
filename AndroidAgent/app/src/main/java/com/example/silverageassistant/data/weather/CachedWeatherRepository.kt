package com.example.silverageassistant.data.weather

import com.example.silverageassistant.domain.weather.WeatherRepository
import com.example.silverageassistant.domain.weather.WeatherResult
import com.example.silverageassistant.domain.weather.WeatherSnapshot
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val WEATHER_CACHE_TTL_MINUTES = 120L

/**
 * 首页和天气 Tool 共享的单进程缓存。
 *
 * Mutex 合并同时发生的首页刷新和 Tool 查询，缓存有效期内只访问一次定位和 Open-Meteo。
 * 刷新失败但存在旧值时返回明确标记的 stale 数据，避免短暂断网让首页完全失去天气信息。
 */
class CachedWeatherRepository(
    private val locationProvider: DeviceLocationProvider,
    private val remoteDataSource: WeatherRemoteDataSource,
    private val locationNameResolver: LocationNameResolver? = null,
    private val cacheTtl: Duration = Duration.ofMinutes(WEATHER_CACHE_TTL_MINUTES),
    private val now: () -> Instant = Instant::now,
    private val onLocationTimeZoneResolved: (String) -> Unit = {},
) : WeatherRepository {
    private val refreshMutex = Mutex()
    private var cachedSnapshot: WeatherSnapshot? = null

    override suspend fun getWeather(): WeatherResult = refreshMutex.withLock {
        val cached = cachedSnapshot
        if (cached != null && isFresh(cached)) {
            return WeatherResult(snapshot = cached, fromCache = true)
        }
        try {
            val coordinates = locationProvider.currentCoordinates()
            val locationName = locationNameResolver?.resolve(coordinates)
            val fresh = remoteDataSource.fetch(coordinates).copy(
                locationName = locationName,
            )
            onLocationTimeZoneResolved(fresh.timezone)
            cachedSnapshot = fresh
            WeatherResult(snapshot = fresh, fromCache = false)
        } catch (error: Exception) {
            cached?.let {
                return WeatherResult(
                    snapshot = it,
                    fromCache = true,
                    isStale = true,
                )
            }
            throw error
        }
    }

    private fun isFresh(snapshot: WeatherSnapshot): Boolean {
        val age = Duration.between(snapshot.fetchedAt, now())
        return !age.isNegative && age < cacheTtl
    }
}
