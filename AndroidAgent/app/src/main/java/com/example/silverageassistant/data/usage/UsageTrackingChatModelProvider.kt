package com.example.silverageassistant.data.usage

import com.example.silverageassistant.data.model.ModelConfigurationStore
import com.example.silverageassistant.domain.model.ChatModelProvider
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatStreamEvent
import com.example.silverageassistant.domain.model.ChatUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class UsageTrackingChatModelProvider(
    private val delegate: ChatModelProvider,
    private val configurationStore: ModelConfigurationStore,
    private val recorder: ModelUsageRecorder,
    private val feature: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatModelProvider {
    override fun stream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        val startedAt = clock()
        val visibleOutput = StringBuilder()
        var reportedUsage: ChatUsage? = null
        var estimateUsed = false
        try {
            delegate.stream(request).collect { event ->
                when (event) {
                    is ChatStreamEvent.TextDelta -> visibleOutput.append(event.text)
                    is ChatStreamEvent.Usage -> reportedUsage = merge(reportedUsage, event.usage)
                    else -> Unit
                }
                emit(event)
            }
            val estimate = LocalTokenEstimator.estimate(request, visibleOutput.toString())
            val finalUsage = reportedUsage.orEmpty(
                fallbackPromptTokens = estimate.inputTokens,
                fallbackCompletionTokens = estimate.outputTokens,
            ).also {
                estimateUsed = reportedUsage?.promptTokens == null ||
                    reportedUsage?.completionTokens == null
            }
            if (reportedUsage?.promptTokens == null || reportedUsage?.completionTokens == null) {
                emit(ChatStreamEvent.Usage(finalUsage))
            }
            safelyRecord(
                startedAt = startedAt,
                usage = finalUsage,
                estimated = estimateUsed,
                successful = true,
            )
        } catch (error: CancellationException) {
            val estimate = LocalTokenEstimator.estimate(request, visibleOutput.toString())
            safelyRecord(
                startedAt = startedAt,
                usage = reportedUsage.orEmpty(estimate.inputTokens, estimate.outputTokens),
                estimated = true,
                successful = false,
            )
            throw error
        } catch (error: Exception) {
            val estimate = LocalTokenEstimator.estimate(request, visibleOutput.toString())
            safelyRecord(
                startedAt = startedAt,
                usage = reportedUsage.orEmpty(estimate.inputTokens, estimate.outputTokens),
                estimated = true,
                successful = false,
            )
            throw error
        }
    }

    private suspend fun record(
        startedAt: Long,
        usage: ChatUsage,
        estimated: Boolean,
        successful: Boolean,
    ) {
        val configuration = configurationStore.configuration.value
        recorder.recordMllm(
            provider = "openai_compatible",
            model = configuration.model,
            feature = feature,
            startedAtEpochMillis = startedAt,
            finishedAtEpochMillis = clock(),
            usage = usage,
            estimated = estimated,
            successful = successful,
        )
    }

    private suspend fun safelyRecord(
        startedAt: Long,
        usage: ChatUsage,
        estimated: Boolean,
        successful: Boolean,
    ) {
        runCatching {
            record(
                startedAt = startedAt,
                usage = usage,
                estimated = estimated,
                successful = successful,
            )
        }
    }

    private fun merge(current: ChatUsage?, incoming: ChatUsage): ChatUsage = ChatUsage(
        promptTokens = incoming.promptTokens ?: current?.promptTokens,
        completionTokens = incoming.completionTokens ?: current?.completionTokens,
        totalTokens = incoming.totalTokens ?: current?.totalTokens,
    )

    private fun ChatUsage?.orEmpty(
        fallbackPromptTokens: Long,
        fallbackCompletionTokens: Long,
    ): ChatUsage {
        val prompt = this?.promptTokens ?: fallbackPromptTokens
        val completion = this?.completionTokens ?: fallbackCompletionTokens
        return ChatUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = this?.totalTokens ?: prompt + completion,
        )
    }
}
