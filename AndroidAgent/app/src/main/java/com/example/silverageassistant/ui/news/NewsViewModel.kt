package com.example.silverageassistant.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.domain.news.NewsFeed
import com.example.silverageassistant.domain.news.NewsFailureReason
import com.example.silverageassistant.domain.news.NewsRepository
import com.example.silverageassistant.domain.news.NewsServiceException
import com.example.silverageassistant.domain.voice.VoiceFeature
import com.example.silverageassistant.domain.voice.VoiceInteractionCoordinator
import com.example.silverageassistant.domain.voice.VoicePriority
import com.example.silverageassistant.domain.voice.VoiceRequestContext
import com.example.silverageassistant.domain.voice.VoiceSpeakingState
import java.util.UUID
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewsUiState(
    val feed: NewsFeed? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val voiceEnabled: Boolean = false,
    val isSpeaking: Boolean = false,
)

class NewsViewModel(
    private val repository: NewsRepository,
    private val voiceCoordinator: VoiceInteractionCoordinator? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NewsUiState())
    val uiState: StateFlow<NewsUiState> = _uiState.asStateFlow()
    private var pageActive = false
    private var autoSpokenThisVisit = false

    init {
        voiceCoordinator?.let { voice ->
            viewModelScope.launch {
                voice.enabled.collect { enabled ->
                    _uiState.update { it.copy(voiceEnabled = enabled) }
                    if (enabled && pageActive && !autoSpokenThisVisit) {
                        _uiState.value.feed?.let { feed ->
                            autoSpokenThisVisit = true
                            speakFeed(feed)
                        }
                    }
                }
            }
            viewModelScope.launch {
                voice.speakingState.collect { state ->
                    _uiState.update { it.copy(isSpeaking = state == VoiceSpeakingState.SPEAKING) }
                }
            }
        }
        refresh()
    }

    fun refresh() {
        if (_uiState.value.isLoading) return
        // 榜单没有端侧持久缓存；刷新时清空旧榜单，避免请求失败后把旧内容当作实时新闻。
        _uiState.update { it.copy(feed = null, isLoading = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.getTopNews() }
                .onSuccess { feed ->
                    _uiState.update { it.copy(feed = feed, isLoading = false, message = null) }
                    if (
                        pageActive &&
                        !autoSpokenThisVisit &&
                        voiceCoordinator?.enabled?.value == true
                    ) {
                        autoSpokenThisVisit = true
                        speakFeed(feed)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = error.toUserMessage(),
                        )
                    }
                }
        }
    }

    fun speakNews() {
        _uiState.value.feed?.let(::speakFeed)
    }

    fun onPageOpened() {
        pageActive = true
        autoSpokenThisVisit = false
        if (voiceCoordinator?.enabled?.value == true) {
            _uiState.value.feed?.let { feed ->
                autoSpokenThisVisit = true
                speakFeed(feed)
            }
        }
    }

    fun onPageClosed() {
        pageActive = false
        stopSpeaking()
    }

    fun stopSpeaking() {
        viewModelScope.launch { voiceCoordinator?.stopSpeaking() }
    }

    private fun speakFeed(feed: NewsFeed) {
        val items = feed.items.take(5)
        if (items.isEmpty()) return
        val text = buildString {
            val now = ZonedDateTime.now()
            append(
                "当前是${now.year}年${now.monthValue}月${now.dayOfMonth}日" +
                    "${now.hour}点${now.minute}分，今天的新闻如下。",
            )
            items.forEachIndexed { index, item ->
                append("第${NEWS_ORDINALS[index]}条新闻的内容如下：")
                append(item.title.trim())
                item.summary.trim().takeIf(String::isNotBlank)?.let { summary ->
                    append("。")
                    append(summary.take(500))
                }
                append("。")
            }
        }.take(20_000)
        voiceCoordinator?.speak(
            VoiceRequestContext(
                feature = VoiceFeature.NEWS,
                correlationId = "news-${UUID.randomUUID()}",
                priority = VoicePriority.NEWS,
            ),
            text,
        )
    }

    private fun Throwable.toUserMessage(): String = when ((this as? NewsServiceException)?.reason) {
        NewsFailureReason.HTTP -> "新闻服务暂时不可用，请稍后重试。"
        NewsFailureReason.PARSING -> "已连接百度热搜，但网页格式发生变化。"
        NewsFailureReason.NETWORK, null -> "无法连接新闻服务，请检查网络后重试。"
    }

    class Factory(
        private val repository: NewsRepository,
        private val voiceCoordinator: VoiceInteractionCoordinator? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NewsViewModel::class.java))
            return NewsViewModel(repository, voiceCoordinator) as T
        }
    }

    private companion object {
        val NEWS_ORDINALS = listOf("一", "二", "三", "四", "五")
    }
}
