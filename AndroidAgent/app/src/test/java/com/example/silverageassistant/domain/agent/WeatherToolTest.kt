package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.data.weather.CachedWeatherRepository
import com.example.silverageassistant.data.weather.DeviceLocationProvider
import com.example.silverageassistant.data.weather.LocationNameResolver
import com.example.silverageassistant.data.weather.WeatherRemoteDataSource
import com.example.silverageassistant.domain.weather.GeoCoordinates
import com.example.silverageassistant.testing.weatherSnapshot
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherToolTest {
    @Test
    fun repeatedToolCalls_useRepositoryCacheAndNeverExposeCoordinates() = runBlocking {
        val now = Instant.parse("2026-07-18T08:00:30Z")
        var remoteCalls = 0
        val repository = CachedWeatherRepository(
            locationProvider = DeviceLocationProvider {
                GeoCoordinates(31.234567, 121.456789)
            },
            remoteDataSource = WeatherRemoteDataSource {
                remoteCalls += 1
                weatherSnapshot(Instant.parse("2026-07-18T08:00:00Z"))
            },
            locationNameResolver = LocationNameResolver { "上海" },
            cacheTtl = Duration.ofMinutes(1),
            now = { now },
        )
        val tool = WeatherTool(repository, now = { now })

        val first = tool.execute("{}")
        val second = tool.execute("{}")
        val secondJson = Json.parseToJsonElement(second).jsonObject

        assertEquals(1, remoteCalls)
        assertFalse(Json.parseToJsonElement(first).jsonObject["from_cache"]!!.jsonPrimitive.boolean)
        assertTrue(secondJson["from_cache"]!!.jsonPrimitive.boolean)
        assertEquals("上海", secondJson["location_name"]!!.jsonPrimitive.content)
        assertEquals("正在查询天气", tool.runningDisplayName)
        assertFalse(second.contains("31.234567"))
        assertFalse(second.contains("121.456789"))
    }
}
