package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.domain.model.ChatToolDefinition
import com.example.silverageassistant.domain.weather.LocationPermissionRequiredException
import com.example.silverageassistant.domain.weather.LocationUnavailableException
import com.example.silverageassistant.domain.weather.WeatherRepository
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class WeatherTool(
    private val repository: WeatherRepository,
    private val now: () -> Instant = Instant::now,
) : AgentTool {
    override val definition = ChatToolDefinition(
        name = NAME,
        description = "查询老人当前位置的实时天气、今天以及未来三天的天气预报。",
        parametersJson = """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
        """.trimIndent(),
    )
    override val riskLevel = ToolRiskLevel.Low
    override val runningDisplayName = "正在查询天气"

    override suspend fun execute(argumentsJson: String): String {
        val arguments = Json.parseToJsonElement(argumentsJson).jsonObject
        require(arguments.isEmpty()) { "get_weather does not accept arguments" }
        return try {
            val result = repository.getWeather()
            val snapshot = result.snapshot
            val ageMinutes = Duration.between(snapshot.fetchedAt, now())
                .toMinutes()
                .coerceAtLeast(0)
            buildJsonObject {
                put("ok", true)
                put("source", "open_meteo")
                put("fetched_at", snapshot.fetchedAt.toString())
                put("timezone", snapshot.timezone)
                snapshot.locationName?.let { put("location_name", it) }
                put("from_cache", result.fromCache)
                put("is_stale", result.isStale)
                put("cache_age_minutes", ageMinutes)
                put(
                    "current",
                    buildJsonObject {
                        put("condition", snapshot.current.condition)
                        put("temperature_celsius", snapshot.current.temperatureCelsius)
                        put(
                            "apparent_temperature_celsius",
                            snapshot.current.apparentTemperatureCelsius,
                        )
                        put(
                            "relative_humidity_percent",
                            snapshot.current.relativeHumidityPercent,
                        )
                        put(
                            "precipitation_millimetres",
                            snapshot.current.precipitationMillimetres,
                        )
                        put(
                            "wind_speed_kilometres_per_hour",
                            snapshot.current.windSpeedKilometresPerHour,
                        )
                    },
                )
                put(
                    "daily",
                    buildJsonArray {
                        snapshot.daily.forEach { day ->
                            add(
                                buildJsonObject {
                                    put("date", day.date.toString())
                                    put("condition", day.condition)
                                    put(
                                        "minimum_temperature_celsius",
                                        day.minimumTemperatureCelsius,
                                    )
                                    put(
                                        "maximum_temperature_celsius",
                                        day.maximumTemperatureCelsius,
                                    )
                                    put(
                                        "precipitation_probability_percent",
                                        day.precipitationProbabilityPercent,
                                    )
                                },
                            )
                        }
                    },
                )
                put(
                    "advisories",
                    buildJsonArray {
                        snapshot.advisories.forEach(::add)
                    },
                )
            }.toString()
        } catch (_: LocationPermissionRequiredException) {
            errorResult(
                code = "LOCATION_PERMISSION_REQUIRED",
                message = "需要先允许应用获取大致位置。",
            )
        } catch (_: LocationUnavailableException) {
            errorResult(
                code = "LOCATION_UNAVAILABLE",
                message = "暂时无法获取当前位置，请检查手机定位是否开启。",
            )
        } catch (_: Exception) {
            errorResult(
                code = "WEATHER_UNAVAILABLE",
                message = "天气服务暂时无法使用，请稍后再试。",
            )
        }
    }

    private fun errorResult(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error_code", code)
        put("message", message)
    }.toString()

    companion object {
        const val NAME = "get_weather"
    }
}
