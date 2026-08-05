package com.example.silverageassistant.ui.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.silverageassistant.domain.news.NewsItem
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.theme.ElderSpacing

@Composable
fun NewsRoute(
    onBack: () -> Unit,
    viewModel: NewsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.onPageOpened() }
    DisposableEffect(Unit) {
        onDispose { viewModel.onPageClosed() }
    }
    NewsScreen(
        state = state,
        onBack = {
            viewModel.onPageClosed()
            onBack()
        },
        onRefresh = viewModel::refresh,
        onSpeak = viewModel::speakNews,
        onStopSpeaking = viewModel::stopSpeaking,
        modifier = modifier,
    )
}

@Composable
fun NewsScreen(
    state: NewsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSpeak: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(
        title = "新闻播报",
        onBack = onBack,
        modifier = modifier,
    ) { paddingValues ->
        when {
            state.feed == null && state.isLoading -> {
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxWidth()
                        .padding(ElderSpacing.large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
                ) {
                    CircularProgressIndicator()
                    Text("正在获取今日热搜", style = MaterialTheme.typography.titleMedium)
                }
            }

            state.feed == null -> {
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxWidth()
                        .padding(ElderSpacing.large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
                ) {
                    Text(
                        state.message ?: "暂时没有获取到新闻。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    RefreshButton(onClick = onRefresh, enabled = !state.isLoading)
                }
            }

            else -> {
                val feed = state.feed
                LazyColumn(
                    modifier = Modifier.padding(paddingValues),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = ElderSpacing.medium,
                        vertical = ElderSpacing.medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(ElderSpacing.small)) {
                            Text(
                                "今日热搜前 ${feed.count} 条",
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                "来源：${feed.source}。语音播报读取前 5 条，页面继续显示前 ${feed.count} 条。",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            RefreshButton(onClick = onRefresh, enabled = !state.isLoading)
                            if (state.voiceEnabled) {
                                Button(
                                    onClick = if (state.isSpeaking) onStopSpeaking else onSpeak,
                                ) {
                                    Icon(
                                        if (state.isSpeaking) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                    )
                                    Text(if (state.isSpeaking) "停止播报" else "播报前五条")
                                }
                            }
                        }
                    }
                    itemsIndexed(feed.items, key = { index, item -> "$index-${item.title}" }) {
                            index,
                            item,
                        ->
                        NewsCard(rank = index + 1, item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsCard(rank: Int, item: NewsItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.small),
        ) {
            Text(
                "$rank. ${item.title}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                item.summary.ifBlank { "暂无摘要" },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun RefreshButton(onClick: () -> Unit, enabled: Boolean) {
    Button(onClick = onClick, enabled = enabled) {
        Icon(Icons.Rounded.Refresh, contentDescription = null)
        Text(if (enabled) "刷新新闻" else "正在刷新")
    }
}
