package com.example.silverageassistant.data.weather

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoWeatherDataSourceTest {
    @Test
    fun response_isMappedToCurrentAndFourDailyForecasts() {
        val fetchedAt = Instant.parse("2026-07-18T08:00:00Z")
        val dataSource = OpenMeteoWeatherDataSource(
            clock = Clock.fixed(fetchedAt, ZoneOffset.UTC),
        )

        val snapshot = dataSource.parseResponse(validResponse)

        assertEquals(fetchedAt, snapshot.fetchedAt)
        assertEquals("Asia/Shanghai", snapshot.timezone)
        assertEquals("多云", snapshot.current.condition)
        assertEquals(28.4, snapshot.current.temperatureCelsius, 0.0)
        assertEquals(4, snapshot.daily.size)
        assertEquals("雷雨", snapshot.daily[1].condition)
        assertTrue(snapshot.advisories.any { it.contains("雷雨") })
    }

    private val validResponse = """
        {
          "timezone": "Asia/Shanghai",
          "current": {
            "time": "2026-07-18T16:00",
            "temperature_2m": 28.4,
            "apparent_temperature": 30.1,
            "relative_humidity_2m": 62,
            "precipitation": 0.0,
            "weather_code": 2,
            "wind_speed_10m": 12.0
          },
          "daily": {
            "time": ["2026-07-18", "2026-07-19", "2026-07-20", "2026-07-21"],
            "weather_code": [2, 95, 3, 1],
            "temperature_2m_max": [31.0, 29.0, 32.0, 34.0],
            "temperature_2m_min": [23.0, 22.0, 24.0, 25.0],
            "precipitation_probability_max": [20, 80, 30, 10],
            "precipitation_sum": [0.0, 12.0, 0.5, 0.0],
            "wind_speed_10m_max": [20.0, 35.0, 18.0, 15.0]
          }
        }
    """.trimIndent()
}
