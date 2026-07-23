package com.example.silverageassistant.data.weather

import com.example.silverageassistant.domain.weather.GeoCoordinates
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class FallbackLocationNameResolver(
    private vararg val resolvers: LocationNameResolver,
) : LocationNameResolver {
    override suspend fun resolve(coordinates: GeoCoordinates): String? {
        resolvers.forEach { resolver ->
            resolver.resolve(coordinates)
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
        }
        return null
    }
}

class BigDataCloudLocationNameResolver(
    client: OkHttpClient? = null,
) : LocationNameResolver {
    private val httpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(coordinates: GeoCoordinates): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = ENDPOINT.toHttpUrl().newBuilder()
                    .addQueryParameter("latitude", coordinates.latitude.toString())
                    .addQueryParameter("longitude", coordinates.longitude.toString())
                    .addQueryParameter("localityLanguage", "zh")
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()?.let(::parseCity)
                }
            }.getOrNull()
        }

    internal fun parseCity(body: String): String? {
        val root = json.parseToJsonElement(body).jsonObject
        return sequenceOf("city", "locality", "principalSubdivision")
            .mapNotNull { name ->
                root[name]?.jsonPrimitive?.contentOrNull
            }
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?.removeSuffix("市")
    }

    private companion object {
        const val ENDPOINT =
            "https://api.bigdatacloud.net/data/reverse-geocode-client"
        const val USER_AGENT =
            "com.example.silverageassistant/1.0 (Android current-location lookup)"
    }
}
