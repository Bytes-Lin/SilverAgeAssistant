package com.example.silverageassistant.domain.news

data class NewsItem(
    val title: String,
    val summary: String,
)

data class NewsFeed(
    val success: Boolean,
    val source: String,
    val sourceUrl: String,
    val count: Int,
    val items: List<NewsItem>,
)

fun interface NewsRepository {
    suspend fun getTopNews(): NewsFeed
}

class NewsServiceException(
    message: String,
    cause: Throwable? = null,
    val reason: NewsFailureReason = NewsFailureReason.NETWORK,
) : Exception(message, cause)

enum class NewsFailureReason {
    NETWORK,
    HTTP,
    PARSING,
}
