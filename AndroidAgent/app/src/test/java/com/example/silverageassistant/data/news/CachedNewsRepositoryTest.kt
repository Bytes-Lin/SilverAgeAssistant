package com.example.silverageassistant.data.news

import com.example.silverageassistant.domain.news.NewsFeed
import com.example.silverageassistant.domain.news.NewsItem
import com.example.silverageassistant.domain.news.NewsRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CachedNewsRepositoryTest {
    @Test
    fun getTopNews_reusesFeedUntilCacheExpires() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-08-04T00:00:00Z"))
        var requests = 0
        val remote = NewsRepository {
            requests += 1
            feed("新闻$requests")
        }
        val repository = CachedNewsRepository(
            remoteRepository = remote,
            clock = clock,
            cacheTtlMillis = 60_000,
        )

        assertEquals("新闻1", repository.getTopNews().items.single().title)
        clock.now = clock.now.plusSeconds(59)
        assertEquals("新闻1", repository.getTopNews().items.single().title)
        assertEquals(1, requests)

        clock.now = clock.now.plusSeconds(2)
        assertEquals("新闻2", repository.getTopNews().items.single().title)
        assertEquals(2, requests)
    }

    private fun feed(title: String) = NewsFeed(
        success = true,
        source = "百度热搜",
        sourceUrl = BaiduHotSearchNewsRepository.SOURCE_URL,
        count = 1,
        items = listOf(NewsItem(title, "摘要")),
    )

    private class MutableClock(
        var now: Instant,
        private val zone: ZoneId = ZoneId.of("UTC"),
    ) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
        override fun instant(): Instant = now
    }
}
