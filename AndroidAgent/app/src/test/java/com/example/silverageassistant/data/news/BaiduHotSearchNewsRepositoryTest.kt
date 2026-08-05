package com.example.silverageassistant.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduHotSearchNewsRepositoryTest {
    private val repository = BaiduHotSearchNewsRepository()

    @Test
    fun parsePage_returnsFirstFifteenNewsItems() {
        val content = (1..20).joinToString(",") { index ->
            """{"word":"新闻$index","desc":"摘要$index"}"""
        }
        val html = """
            <html><div id="sanRoot"><!--s-data:{"data":{"cards":[
            {"component":"other","content":[]},
            {"component":"hotList","content":[$content]}
            ]}}--></div></html>
        """.trimIndent()

        val result = repository.parsePage(html)

        assertTrue(result.success)
        assertEquals("百度热搜", result.source)
        assertEquals(BaiduHotSearchNewsRepository.SOURCE_URL, result.sourceUrl)
        assertEquals(15, result.count)
        assertEquals("新闻1", result.items.first().title)
        assertEquals("摘要15", result.items.last().summary)
    }

    @Test
    fun parsePage_acceptsMobileTabTextListFallback() {
        val html = """
            <html><!--s-data:{"cards":[{"component":"tabTextList","content":[
            {"content":[{"word":"移动新闻一"},{"word":"移动新闻二"}]}
            ]}]}--></html>
        """.trimIndent()

        val result = repository.parsePage(html)

        assertEquals(2, result.count)
        assertEquals("移动新闻一", result.items.first().title)
        assertEquals("", result.items.first().summary)
    }

    @Test(expected = com.example.silverageassistant.domain.news.NewsServiceException::class)
    fun parsePage_rejectsPageWithoutEmbeddedData() {
        repository.parsePage("<html>changed page</html>")
    }
}
