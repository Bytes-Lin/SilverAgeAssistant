package com.example.silverageassistant.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingThinkingFilterTest {
    @Test
    fun splitThinkingTags_areRemovedFromVisibleText() {
        val filter = StreamingThinkingFilter()

        val outputs = listOf(
            filter.feed("<thi"),
            filter.feed("nk>内部思考"),
            filter.feed("</th"),
            filter.feed("ink>您好"),
            filter.finish(),
        )

        assertEquals("您好", outputs.joinToString("") { it.visibleText })
        assertTrue(outputs.any { it.reasoningDetected })
    }

    @Test
    fun normalStreamingText_isReturnedImmediately() {
        val filter = StreamingThinkingFilter()

        assertEquals("您好", filter.feed("您好").visibleText)
        assertEquals("", filter.finish().visibleText)
    }
}
