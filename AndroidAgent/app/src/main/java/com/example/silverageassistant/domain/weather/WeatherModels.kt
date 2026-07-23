package com.example.silverageassistant.domain.weather

import java.time.Instant
import java.time.LocalDate

data class GeoCoordinates(
    val latitude: Double,
    val longitude: Double,
)

data class CurrentWeather(
    val observedAtLocal: String,
    val condition: String,
    val weatherCode: Int,
    val temperatureCelsius: Double,
    val apparentTemperatureCelsius: Double,
    val relativeHumidityPercent: Int,
    val precipitationMillimetres: Double,
    val windSpeedKilometresPerHour: Double,
)

data class DailyWeather(
    val date: LocalDate,
    val condition: String,
    val weatherCode: Int,
    val minimumTemperatureCelsius: Double,
    val maximumTemperatureCelsius: Double,
    val precipitationProbabilityPercent: Int,
    val precipitationMillimetres: Double,
    val maximumWindSpeedKilometresPerHour: Double,
)

data class WeatherSnapshot(
    val fetchedAt: Instant,
    val timezone: String,
    val locationName: String? = null,
    val current: CurrentWeather,
    val daily: List<DailyWeather>,
    val advisories: List<String>,
)

data class WeatherResult(
    val snapshot: WeatherSnapshot,
    val fromCache: Boolean,
    val isStale: Boolean = false,
)

fun interface WeatherRepository {
    suspend fun getWeather(): WeatherResult
}

class LocationPermissionRequiredException :
    IllegalStateException("Location permission is required")

class LocationUnavailableException :
    IllegalStateException("Current location is unavailable")

class WeatherServiceException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
