package com.example.silverageassistant.data.model

internal data class ThinkingFilterResult(
    val visibleText: String = "",
    val reasoningDetected: Boolean = false,
)

internal class StreamingThinkingFilter {
    private var buffer = ""
    private var insideThinking = false

    fun feed(chunk: String): ThinkingFilterResult {
        if (chunk.isEmpty()) return ThinkingFilterResult()
        buffer += chunk
        val visible = StringBuilder()
        var reasoningDetected = false

        while (buffer.isNotEmpty()) {
            val marker = if (insideThinking) CLOSING_TAG else OPENING_TAG
            val markerIndex = buffer.indexOf(marker)
            if (markerIndex >= 0) {
                if (insideThinking) {
                    reasoningDetected = reasoningDetected || markerIndex > 0
                } else {
                    visible.append(buffer.substring(0, markerIndex))
                    reasoningDetected = true
                }
                buffer = buffer.substring(markerIndex + marker.length)
                insideThinking = !insideThinking
                continue
            }

            val retainedLength = longestSuffixMatchingMarkerPrefix(buffer, marker)
            val resolvedLength = buffer.length - retainedLength
            if (resolvedLength > 0) {
                if (insideThinking) {
                    reasoningDetected = true
                } else {
                    visible.append(buffer.substring(0, resolvedLength))
                }
                buffer = buffer.substring(resolvedLength)
            }
            break
        }

        return ThinkingFilterResult(
            visibleText = visible.toString(),
            reasoningDetected = reasoningDetected,
        )
    }

    fun finish(): ThinkingFilterResult {
        val result = if (insideThinking) {
            ThinkingFilterResult(reasoningDetected = buffer.isNotEmpty())
        } else {
            ThinkingFilterResult(visibleText = buffer)
        }
        buffer = ""
        insideThinking = false
        return result
    }

    private fun longestSuffixMatchingMarkerPrefix(value: String, marker: String): Int {
        val maxLength = minOf(value.length, marker.length - 1)
        for (length in maxLength downTo 1) {
            if (value.endsWith(marker.substring(0, length))) return length
        }
        return 0
    }

    private companion object {
        const val OPENING_TAG = "<think>"
        const val CLOSING_TAG = "</think>"
    }
}
