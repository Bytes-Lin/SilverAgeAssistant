package com.example.silverageassistant.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.data.middleserver.FamilyDailyModelUsage
import com.example.silverageassistant.data.middleserver.FamilyModelUsageSummary
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing
import java.text.NumberFormat

@Composable
fun FamilyUsageRoute(
    elderId: String?,
    onBack: () -> Unit,
    viewModel: FamilyUsageViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(elderId) {
        viewModel.load(elderId)
    }
    FamilyUsageScreen(
        state = state,
        onRefresh = { viewModel.load(elderId, force = true) },
        onBack = onBack,
    )
}

@Composable
fun FamilyUsageScreen(
    state: FamilyUsageUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(
        title = "模型用量",
        onBack = onBack,
        modifier = modifier,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            Text(
                text = "客户端用量统计",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "老人手机每小时自动汇报；点刷新时会请求在线的老人手机立即汇报。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.summary?.let { summary ->
                TodayUsageContent(state.dailyUsage, state.elderCurrentDate)
                if (state.dailyTimeZoneSource == "SYSTEM_FALLBACK") {
                    TimeZoneFallbackCard()
                }
                MonthUsageSummary(summary)
                if (state.dailyBreakdownAvailable) {
                    MonthUsageCharts(state.dailyUsage)
                } else {
                    DailyBreakdownUnavailableCard()
                }
            }
            state.statusMessage?.let { StatusCard(it) }
            state.errorMessage?.let { ErrorCard(it) }
            LargeActionButton(
                text = if (state.isLoading) "正在刷新用量" else "立即刷新用量",
                icon = Icons.Rounded.Refresh,
                onClick = onRefresh,
                enabled = !state.isLoading,
                outlined = true,
            )
            Spacer(modifier = Modifier.height(ElderSpacing.medium))
        }
    }
}

@Composable
private fun TodayUsageContent(
    days: List<FamilyDailyModelUsage>,
    elderCurrentDate: String,
) {
    val today = days.firstOrNull { it.date == elderCurrentDate }
        ?: FamilyDailyModelUsage(
            date = elderCurrentDate,
            inputTokens = 0,
            outputTokens = 0,
            mllmRequestCount = 0,
            asrRequestCount = 0,
            ttsRequestCount = 0,
            containsEstimatedValues = false,
        )
    val formatter = NumberFormat.getIntegerInstance()
    Column(verticalArrangement = Arrangement.spacedBy(ElderSpacing.small)) {
        Text("今日用量", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ElderSpacing.small),
        ) {
            UsageMetricCard(
                label = "聊天 Token",
                value = formatter.format(today.inputTokens + today.outputTokens),
                modifier = Modifier.weight(1f),
            )
            UsageMetricCard(
                label = "模型调用",
                value = "${formatter.format(today.mllmRequestCount)} 次",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ElderSpacing.small),
        ) {
            UsageMetricCard(
                label = "ASR 识别",
                value = "${formatter.format(today.asrRequestCount)} 次",
                modifier = Modifier.weight(1f),
            )
            UsageMetricCard(
                label = "TTS 播报",
                value = "${formatter.format(today.ttsRequestCount)} 次",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthUsageSummary(summary: FamilyModelUsageSummary) {
    val formatter = NumberFormat.getIntegerInstance()
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
            Text("本月汇总", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "聊天 Token ${formatter.format(summary.inputTokens + summary.outputTokens)}" +
                    "（输入 ${formatter.format(summary.inputTokens)}，" +
                    "输出 ${formatter.format(summary.outputTokens)}）",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "模型 ${formatter.format(summary.mllmRequestCount)} 次 · " +
                    "ASR ${formatter.format(summary.asrRequestCount)} 次 · " +
                    "TTS ${formatter.format(summary.ttsRequestCount)} 次",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = buildString {
                    append("最后汇报：")
                    append(summary.lastReportedAt ?: "尚未汇报")
                    if (summary.containsEstimatedValues) append(" · 含本地估算")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthUsageCharts(days: List<FamilyDailyModelUsage>) {
    Column(verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium)) {
        TokenDailyChart(days)
        SpeechDailyChart(days)
    }
}

@Composable
private fun TokenDailyChart(days: List<FamilyDailyModelUsage>) {
    val inputColor = MaterialTheme.colorScheme.primary
    val outputColor = MaterialTheme.colorScheme.tertiary
    val maxTotal = days.maxOfOrNull { it.inputTokens + it.outputTokens }
        ?.coerceAtLeast(1) ?: 1
    UsageChartCard(
        title = "本月每日聊天 Token",
        legends = listOf("输入" to inputColor, "输出" to outputColor),
    ) {
        DailyChartRow(days) { day ->
            val inputHeight = scaledHeight(day.inputTokens, maxTotal, CHART_BAR_HEIGHT)
            val outputHeight = scaledHeight(day.outputTokens, maxTotal, CHART_BAR_HEIGHT)
            Column(
                modifier = Modifier
                    .height(CHART_BAR_HEIGHT)
                    .width(18.dp)
                    .semantics {
                        contentDescription = "${day.date}，输入 ${day.inputTokens} Token，" +
                            "输出 ${day.outputTokens} Token"
                    },
                verticalArrangement = Arrangement.Bottom,
            ) {
                UsageBar(height = outputHeight, color = outputColor)
                UsageBar(height = inputHeight, color = inputColor)
            }
        }
    }
}

@Composable
private fun SpeechDailyChart(days: List<FamilyDailyModelUsage>) {
    val asrColor = MaterialTheme.colorScheme.primary
    val ttsColor = MaterialTheme.colorScheme.tertiary
    val maxCount = days.maxOfOrNull { maxOf(it.asrRequestCount, it.ttsRequestCount) }
        ?.coerceAtLeast(1) ?: 1
    UsageChartCard(
        title = "本月每日语音调用",
        legends = listOf("ASR" to asrColor, "TTS" to ttsColor),
    ) {
        DailyChartRow(days) { day ->
            Row(
                modifier = Modifier
                    .height(CHART_BAR_HEIGHT)
                    .width(24.dp)
                    .semantics {
                        contentDescription = "${day.date}，ASR ${day.asrRequestCount} 次，" +
                            "TTS ${day.ttsRequestCount} 次"
                    },
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                UsageBar(
                    height = scaledHeight(day.asrRequestCount, maxCount, CHART_BAR_HEIGHT),
                    color = asrColor,
                    width = 10.dp,
                )
                UsageBar(
                    height = scaledHeight(day.ttsRequestCount, maxCount, CHART_BAR_HEIGHT),
                    color = ttsColor,
                    width = 10.dp,
                )
            }
        }
    }
}

@Composable
private fun UsageChartCard(
    title: String,
    legends: List<Pair<String, Color>>,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.small),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(ElderSpacing.medium)) {
                legends.forEach { (label, color) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            content()
            Text(
                text = "左右滑动查看每天数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DailyChartRow(
    days: List<FamilyDailyModelUsage>,
    bar: @Composable (FamilyDailyModelUsage) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            Column(
                modifier = Modifier.width(30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                bar(day)
                Text(
                    text = day.date.takeLast(2).trimStart('0'),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun UsageBar(
    height: Dp,
    color: Color,
    width: Dp = 18.dp,
) {
    if (height > 0.dp) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height.coerceAtLeast(3.dp))
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(color),
        )
    }
}

private fun scaledHeight(value: Long, maximum: Long, available: Dp): Dp {
    if (value <= 0 || maximum <= 0) return 0.dp
    return available * (value.toFloat() / maximum.toFloat())
}

@Composable
private fun DailyBreakdownUnavailableCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "中台尚未提供每日用量明细，当前先显示本月汇总。",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(ElderSpacing.medium),
        )
    }
}

@Composable
private fun TimeZoneFallbackCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "老人手机尚未取得位置时区，当前暂按设备系统时区统计。",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(ElderSpacing.medium),
        )
    }
}

@Composable
private fun StatusCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(ElderSpacing.medium),
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(ElderSpacing.medium),
        )
    }
}

@Composable
private fun UsageMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(ElderSpacing.medium),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

private val CHART_BAR_HEIGHT = 112.dp
