package com.example.silverageassistant.testing

import com.example.silverageassistant.domain.weather.CurrentWeather
import com.example.silverageassistant.domain.weather.DailyWeather
import com.example.silverageassistant.domain.weather.WeatherSnapshot
import java.time.Instant
import java.time.LocalDate

fun weatherSnapshot(
    fetchedAt: Instant = Instant.parse("2026-07-18T08:00:00Z"),
) = WeatherSnapshot(
    fetchedAt = fetchedAt,
    timezone = "Asia/Shanghai",
    locationName = "上海",
    current = CurrentWeather(
        observedAtLocal = "2026-07-18T16:00",
        condition = "多云",
        weatherCode = 2,
        temperatureCelsius = 28.4,
        apparentTemperatureCelsius = 30.1,
        relativeHumidityPercent = 62,
        precipitationMillimetres = 0.0,
        windSpeedKilometresPerHour = 12.0,
    ),
    daily = (0L..3L).map { offset ->
        DailyWeather(
            date = LocalDate.parse("2026-07-18").plusDays(offset),
            condition = if (offset == 1L) "中雨" else "多云",
            weatherCode = if (offset == 1L) 63 else 2,
            minimumTemperatureCelsius = 23.0 + offset,
            maximumTemperatureCelsius = 31.0 + offset,
            precipitationProbabilityPercent = if (offset == 1L) 80 else 20,
            precipitationMillimetres = if (offset == 1L) 12.0 else 0.0,
            maximumWindSpeedKilometresPerHour = 20.0,
        )
    },
    advisories = listOf("明天可能下雨，外出记得带伞。"),
)
