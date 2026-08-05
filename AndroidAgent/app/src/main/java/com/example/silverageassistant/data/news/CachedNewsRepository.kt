package com.example.silverageassistant.data.news

import com.example.silverageassistant.domain.news.NewsFeed
import com.example.silverageassistant.domain.news.NewsRepository
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程内新闻暂存。缓存期内进入页面或点击刷新都直接复用同一份榜单，不重复访问百度。
 */
class CachedNewsRepository(
    private val remoteRepository: NewsRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtlMillis: Long = NEWS_CACHE_TTL_MILLIS,
) : NewsRepository {
    private val mutex = Mutex()
    private var cachedFeed: NewsFeed? = null
    private var cachedAt: Instant? = null

    override suspend fun getTopNews(): NewsFeed = mutex.withLock {
        val now = clock.instant()
        val feed = cachedFeed
        val storedAt = cachedAt
        if (feed != null && storedAt != null && now.toEpochMilli() - storedAt.toEpochMilli() < cacheTtlMillis) {
            return feed
        }

        remoteRepository.getTopNews().also { fresh ->
            cachedFeed = fresh
            cachedAt = now
        }
    }

    companion object {
        // 天气和新闻统一暂存 2 小时，需要调整时只修改此常量。
        val NEWS_CACHE_TTL_MILLIS: Long = TimeUnit.HOURS.toMillis(2)
    }
}
