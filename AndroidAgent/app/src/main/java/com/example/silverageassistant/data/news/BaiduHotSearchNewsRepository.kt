package com.example.silverageassistant.data.news

import com.example.silverageassistant.domain.news.NewsFeed
import com.example.silverageassistant.domain.news.NewsFailureReason
import com.example.silverageassistant.domain.news.NewsItem
import com.example.silverageassistant.domain.news.NewsRepository
import com.example.silverageassistant.domain.news.NewsServiceException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 读取百度热搜公开页面内嵌的 s-data JSON。
 *
 * 百度没有为该页面提供公开的结构化接口，因此解析失败时必须明确报错，不能把页面结构变化
 * 误报成“今天没有新闻”。页面只展示公开标题和摘要，不抓取详情页正文。
 */
class BaiduHotSearchNewsRepository(
    client: OkHttpClient? = null,
) : NewsRepository {
    private val httpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getTopNews(): NewsFeed = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(SOURCE_URL)
            .get()
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw NewsServiceException(
                        message = "Baidu hot search failed with HTTP ${response.code}",
                        reason = NewsFailureReason.HTTP,
                    )
                }
                val body = response.body?.string()
                    ?: throw NewsServiceException(
                        message = "Baidu hot search returned an empty response",
                        reason = NewsFailureReason.PARSING,
                    )
                parsePage(body)
            }
        } catch (error: NewsServiceException) {
            throw error
        } catch (error: Exception) {
            throw NewsServiceException(
                message = "Unable to load Baidu hot search",
                cause = error,
                reason = NewsFailureReason.NETWORK,
            )
        }
    }

    internal fun parsePage(html: String): NewsFeed {
        try {
            val markerStart = html.indexOf(S_DATA_PREFIX)
            require(markerStart >= 0) { "Missing s-data marker" }
            val jsonStart = markerStart + S_DATA_PREFIX.length
            val jsonEnd = html.indexOf(S_DATA_SUFFIX, jsonStart)
            require(jsonEnd > jsonStart) { "Incomplete s-data block" }

            val root = json.parseToJsonElement(html.substring(jsonStart, jsonEnd)).jsonObject
            // 百度会根据 UA 返回两套结构：桌面版包在 data.cards，移动版直接使用 cards。
            val document = root["data"]?.jsonObject ?: root
            val cards = document.getValue("cards").jsonArray
            val hotList = cards.firstOrNull { card ->
                card.jsonObject["component"]?.jsonPrimitive?.contentOrNull == "hotList"
            }?.jsonObject
            val tabTextList = cards.firstOrNull { card ->
                card.jsonObject["component"]?.jsonPrimitive?.contentOrNull == "tabTextList"
            }?.jsonObject
            val rawItems = when {
                hotList != null -> hotList.getValue("content").jsonArray.toList()
                tabTextList != null -> tabTextList.getValue("content").jsonArray.flatMap { section ->
                    section.jsonObject["content"]?.jsonArray?.toList().orEmpty()
                }
                else -> error("Missing hotList or tabTextList card")
            }
            val items = rawItems.mapNotNull { element ->
                val item = element.jsonObject
                val title = item["word"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                NewsItem(
                    title = title,
                    summary = item["desc"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                )
            }
                .take(NEWS_LIMIT)
            require(items.isNotEmpty()) { "Baidu hotList contains no readable items" }

            return NewsFeed(
                success = true,
                source = SOURCE_NAME,
                sourceUrl = SOURCE_URL,
                count = items.size,
                items = items,
            )
        } catch (error: Exception) {
            throw NewsServiceException(
                message = "Invalid Baidu hot search page",
                cause = error,
                reason = NewsFailureReason.PARSING,
            )
        }
    }

    companion object {
        const val SOURCE_NAME = "百度热搜"
        const val SOURCE_URL = "https://top.baidu.com/board?tab=realtime"
        const val NEWS_LIMIT = 15
        private const val S_DATA_PREFIX = "<!--s-data:"
        private const val S_DATA_SUFFIX = "-->"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }
}
