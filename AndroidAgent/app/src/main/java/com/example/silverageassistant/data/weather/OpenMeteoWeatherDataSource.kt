package com.example.silverageassistant.data.weather

import com.example.silverageassistant.domain.weather.CurrentWeather
import com.example.silverageassistant.domain.weather.DailyWeather
import com.example.silverageassistant.domain.weather.GeoCoordinates
import com.example.silverageassistant.domain.weather.WeatherServiceException
import com.example.silverageassistant.domain.weather.WeatherSnapshot
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

fun interface WeatherRemoteDataSource {
    suspend fun fetch(coordinates: GeoCoordinates): WeatherSnapshot
}

class OpenMeteoWeatherDataSource(
    client: OkHttpClient? = null,
    private val clock: Clock = Clock.systemUTC(),
) : WeatherRemoteDataSource {
    private val httpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(coordinates: GeoCoordinates): WeatherSnapshot =
        withContext(Dispatchers.IO) {
            val url = ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("latitude", coordinates.latitude.toString())
                .addQueryParameter("longitude", coordinates.longitude.toString())
                .addQueryParameter("current", CURRENT_VARIABLES)
                .addQueryParameter("daily", DAILY_VARIABLES)
                .addQueryParameter("forecast_days", FORECAST_DAYS.toString())
                .addQueryParameter("timezone", "auto")
                .build()
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw WeatherServiceException(
                            "Open-Meteo request failed with HTTP ${response.code}",
                        )
                    }
                    val body = requireNotNull(response.body) {
                        "Open-Meteo returned an empty response"
                    }.string()
                    parseResponse(body)
                }
            } catch (error: WeatherServiceException) {
                throw error
            } catch (error: Exception) {
                throw WeatherServiceException("Unable to load weather", error)
            }
        }

    internal fun parseResponse(body: String): WeatherSnapshot {
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val current = root.requiredObject("current")
            val daily = root.requiredObject("daily")
            val dates = daily.requiredArray("time").strings()
            val weatherCodes = daily.requiredArray("weather_code").ints()
            val minimumTemperatures =
                daily.requiredArray("temperature_2m_min").doubles()
            val maximumTemperatures =
                daily.requiredArray("temperature_2m_max").doubles()
            val precipitationProbabilities =
                daily.requiredArray("precipitation_probability_max").ints()
            val precipitation =
                daily.requiredArray("precipitation_sum").doubles()
            val maximumWindSpeeds =
                daily.requiredArray("wind_speed_10m_max").doubles()
            val arrays = listOf(
                weatherCodes.size,
                minimumTemperatures.size,
                maximumTemperatures.size,
                precipitationProbabilities.size,
                precipitation.size,
                maximumWindSpeeds.size,
            )
            require(dates.size >= FORECAST_DAYS && arrays.all { it == dates.size }) {
                "Daily weather arrays have inconsistent lengths"
            }
            val forecasts = dates.indices.take(FORECAST_DAYS).map { index ->
                DailyWeather(
                    date = LocalDate.parse(dates[index]),
                    condition = WeatherCodeMapper.describe(weatherCodes[index]),
                    weatherCode = weatherCodes[index],
                    minimumTemperatureCelsius = minimumTemperatures[index],
                    maximumTemperatureCelsius = maximumTemperatures[index],
                    precipitationProbabilityPercent = precipitationProbabilities[index],
                    precipitationMillimetres = precipitation[index],
                    maximumWindSpeedKilometresPerHour = maximumWindSpeeds[index],
                )
            }
            val currentCode = current.requiredInt("weather_code")
            return WeatherSnapshot(
                fetchedAt = clock.instant(),
                timezone = root.requiredString("timezone"),
                current = CurrentWeather(
                    observedAtLocal = current.requiredString("time"),
                    condition = WeatherCodeMapper.describe(currentCode),
                    weatherCode = currentCode,
                    temperatureCelsius = current.requiredDouble("temperature_2m"),
                    apparentTemperatureCelsius =
                        current.requiredDouble("apparent_temperature"),
                    relativeHumidityPercent =
                        current.requiredInt("relative_humidity_2m"),
                    precipitationMillimetres =
                        current.requiredDouble("precipitation"),
                    windSpeedKilometresPerHour =
                        current.requiredDouble("wind_speed_10m"),
                ),
                daily = forecasts,
                advisories = WeatherAdvisoryPolicy.create(forecasts),
            )
        } catch (error: Exception) {
            throw WeatherServiceException("Invalid Open-Meteo response", error)
        }
    }

    private fun JsonObject.requiredObject(name: String): JsonObject =
        requireNotNull(this[name]) { "Missing $name" }.jsonObject

    private fun JsonObject.requiredArray(name: String): JsonArray =
        requireNotNull(this[name]) { "Missing $name" }.jsonArray

    private fun JsonObject.requiredString(name: String): String =
        requireNotNull(this[name]) { "Missing $name" }.jsonPrimitive.content

    private fun JsonObject.requiredDouble(name: String): Double =
        requireNotNull(this[name]) { "Missing $name" }.jsonPrimitive.double

    private fun JsonObject.requiredInt(name: String): Int =
        requireNotNull(this[name]) { "Missing $name" }.jsonPrimitive.int

    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }
    private fun JsonArray.doubles(): List<Double> = map { it.jsonPrimitive.double }
    private fun JsonArray.ints(): List<Int> = map { it.jsonPrimitive.int }

    private companion object {
        const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        const val FORECAST_DAYS = 4
        const val CURRENT_VARIABLES =
            "temperature_2m,apparent_temperature,weather_code," +
                "relative_humidity_2m,precipitation,wind_speed_10m"
        const val DAILY_VARIABLES =
            "weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,precipitation_sum,wind_speed_10m_max"
    }
}

internal object WeatherCodeMapper {
    fun describe(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大部晴朗"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "米雪"
        80 -> "小阵雨"
        81 -> "中阵雨"
        82 -> "强阵雨"
        85 -> "小阵雪"
        86 -> "大阵雪"
        95 -> "雷雨"
        96, 99 -> "雷雨伴冰雹"
        else -> "天气状况未知"
    }
}

internal object WeatherAdvisoryPolicy {
    fun create(daily: List<DailyWeather>): List<String> {
        val upcoming = daily.take(4)
        return buildList {
            if (upcoming.any { it.weatherCode in THUNDERSTORM_CODES }) {
                add("未来几天可能有雷雨，尽量减少不必要的外出。")
            } else if (
                upcoming.any {
                    it.weatherCode in HEAVY_RAIN_CODES ||
                        it.precipitationProbabilityPercent >= 70
                }
            ) {
                add("未来几天降雨可能较明显，外出记得带伞并注意路滑。")
            }
            if (upcoming.any { it.maximumWindSpeedKilometresPerHour >= 50 }) {
                add("未来几天风力可能较强，避免在大树和广告牌附近停留。")
            }
            if (upcoming.any { it.maximumTemperatureCelsius >= 35 }) {
                add("未来几天气温较高，注意补水并避开午后高温时段。")
            }
            if (upcoming.any { it.minimumTemperatureCelsius <= 5 }) {
                add("未来几天气温较低，外出请注意添衣保暖。")
            }
        }.distinct()
    }

    private val THUNDERSTORM_CODES = setOf(95, 96, 99)
    private val HEAVY_RAIN_CODES = setOf(65, 67, 82)
}
