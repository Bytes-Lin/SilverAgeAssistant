package com.example.silverageassistant.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.onboarding.SessionConnectionStatus
import com.example.silverageassistant.ui.reminders.ReminderItemUi
import com.example.silverageassistant.ui.reminders.ReminderStatus
import com.example.silverageassistant.ui.theme.ElderSpacing
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

data class HomeAction(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun ElderHomeRoute(
    onConversation: () -> Unit,
    onReminders: () -> Unit,
    onFamilyContacts: () -> Unit,
    onNews: () -> Unit,
    onSettings: () -> Unit,
    elderName: String,
    sessionConnectionStatus: SessionConnectionStatus,
    sessionMessage: String?,
    todayReminders: List<ReminderItemUi>,
    weatherViewModel: HomeWeatherViewModel,
    modifier: Modifier = Modifier,
) {
    val weatherState by weatherViewModel.uiState.collectAsState()
    val context = LocalContext.current
    var automaticPermissionRequestAttempted by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        weatherViewModel.onPermissionResult(granted)
    }
    val hasLocationPermission = {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val requestOrRefreshWeather = {
        if (hasLocationPermission()) {
            weatherViewModel.refreshWeather()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    LaunchedEffect(Unit) {
        if (hasLocationPermission()) {
            weatherViewModel.refreshWeather()
        } else if (!automaticPermissionRequestAttempted) {
            automaticPermissionRequestAttempted = true
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    ElderHomeScreen(
        onConversation = onConversation,
        onReminders = onReminders,
        onFamilyContacts = onFamilyContacts,
        onNews = onNews,
        onSettings = onSettings,
        modifier = modifier,
        elderName = elderName,
        sessionConnectionStatus = sessionConnectionStatus,
        sessionMessage = sessionMessage,
        todayReminders = todayReminders,
        weatherState = weatherState,
        onWeatherAction = requestOrRefreshWeather,
    )
}

@Composable
fun ElderHomeScreen(
    onConversation: () -> Unit,
    onReminders: () -> Unit,
    onFamilyContacts: () -> Unit,
    onNews: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    elderName: String = "",
    sessionConnectionStatus: SessionConnectionStatus = SessionConnectionStatus.Unknown,
    sessionMessage: String? = null,
    todayReminders: List<ReminderItemUi> = emptyList(),
    weatherState: HomeWeatherUiState = HomeWeatherUiState(),
    onWeatherAction: () -> Unit = {},
) {
    val formattedDate = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
    }
    var showBindingStatusDialog by rememberSaveable { mutableStateOf(false) }
    val bindingIsHealthy = sessionConnectionStatus == SessionConnectionStatus.Online
    val bindingStatusTitle = when (sessionConnectionStatus) {
        SessionConnectionStatus.Syncing -> "正在确认家人绑定"
        SessionConnectionStatus.Online -> "家人绑定正常"
        SessionConnectionStatus.Offline -> "当前无法连接服务"
        SessionConnectionStatus.Invalid -> "家人绑定已失效"
        SessionConnectionStatus.Unknown -> "暂时无法确认家人绑定"
    }
    val bindingStatusDetail = sessionMessage ?: when (sessionConnectionStatus) {
        SessionConnectionStatus.Syncing -> "正在向中台确认绑定状态，请稍等。"
        SessionConnectionStatus.Online -> "老人端与家属端的绑定信息有效。"
        SessionConnectionStatus.Offline -> "请检查网络，恢复连接后系统会再次确认。"
        SessionConnectionStatus.Invalid -> "请联系家属重新完成绑定。"
        SessionConnectionStatus.Unknown -> "系统尚未取得绑定状态，请稍后再查看。"
    }
    // 首页只提示仍需老人处理的待办；已确认完成的提醒继续保留在今日提醒页，
    // 但不能再占用首页的最近提醒卡片。稍后提醒仍属于未完成待办。
    val latestReminder = todayReminders
        .asSequence()
        .filter { it.status != ReminderStatus.Completed }
        .maxByOrNull(ReminderItemUi::eventTimeEpochMillis)
    val actions = listOf(
        HomeAction("和我说话", Icons.AutoMirrored.Rounded.Chat, onClick = onConversation),
        HomeAction("今日提醒", Icons.Rounded.NotificationsActive, onClick = onReminders),
        HomeAction("联系家人", Icons.Rounded.FamilyRestroom, onClick = onFamilyContacts),
        HomeAction("新闻播报", Icons.AutoMirrored.Rounded.Article, onClick = onNews),
    )

    ElderScreenScaffold(
        title = if (elderName.isBlank()) "您好" else "${elderName}，您好",
        onBack = null,
        modifier = modifier,
        actions = {
            IconButton(onClick = onSettings) {
                Icon(imageVector = Icons.Rounded.Settings, contentDescription = "打开设置")
            }
        },
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            val columnCount = if (maxWidth >= 720.dp) 3 else 2
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ElderSpacing.medium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    IconButton(
                        onClick = { showBindingStatusDialog = true },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = if (bindingIsHealthy) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.Cancel
                            },
                            contentDescription = if (bindingIsHealthy) {
                                "家人绑定正常，点击查看详情"
                            } else {
                                "家人绑定异常，点击查看详情"
                            },
                            modifier = Modifier.size(36.dp),
                            tint = if (bindingIsHealthy) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(ElderSpacing.small))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(modifier = Modifier.padding(ElderSpacing.medium)) {
                        HomeWeatherSummary(
                            state = weatherState,
                            onWeatherAction = onWeatherAction,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(ElderSpacing.small))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(modifier = Modifier.padding(ElderSpacing.medium)) {
                        Text(
                            text = "最近提醒",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (latestReminder == null) {
                            Text(
                                text = "暂无要完成的提醒待办",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        } else {
                            Text(
                                text = "${latestReminder.time} ${latestReminder.title}",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (latestReminder.detail.isNotBlank()) {
                                Text(
                                    text = latestReminder.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(ElderSpacing.medium))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columnCount),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
                ) {
                    items(actions, key = { it.title }) { action ->
                        HomeActionCard(action = action)
                    }
                }
            }
        }
    }

    if (showBindingStatusDialog) {
        AlertDialog(
            onDismissRequest = { showBindingStatusDialog = false },
            title = { Text(bindingStatusTitle) },
            text = { Text(bindingStatusDetail) },
            confirmButton = {
                TextButton(onClick = { showBindingStatusDialog = false }) {
                    Text("知道了")
                }
            },
        )
    }
}

@Composable
private fun HomeWeatherSummary(
    state: HomeWeatherUiState,
    onWeatherAction: () -> Unit,
) {
    val snapshot = state.snapshot
    when {
        snapshot != null -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = snapshot.locationName ?: "当前位置",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${snapshot.current.condition}  " +
                        "${snapshot.current.temperatureCelsius.roundToInt()}℃",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            snapshot.daily.firstOrNull()?.let { today ->
                Text(
                    text = "今天 ${today.minimumTemperatureCelsius.roundToInt()}～" +
                        "${today.maximumTemperatureCelsius.roundToInt()}℃，" +
                        "降雨 ${today.precipitationProbabilityPercent}%",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            snapshot.daily.drop(1).take(3).forEachIndexed { index, day ->
                val dayLabel = when (index) {
                    0 -> "明天"
                    1 -> "后天"
                    else -> day.date.dayOfWeek.getDisplayName(
                        TextStyle.SHORT,
                        Locale.SIMPLIFIED_CHINESE,
                    )
                }
                Text(
                    text = "$dayLabel ${day.condition}  " +
                        "${day.minimumTemperatureCelsius.roundToInt()}～" +
                        "${day.maximumTemperatureCelsius.roundToInt()}℃",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            snapshot.advisories.firstOrNull()?.let { advisory ->
                Text(
                    text = advisory,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val updateTime = snapshot.fetchedAt
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            Text(
                text = if (state.isStale) {
                    "上次更新：$updateTime"
                } else {
                    "更新于 $updateTime · 天气数据：Open-Meteo"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.message != null) {
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.isLoading -> {
            Text("正在更新天气", style = MaterialTheme.typography.titleMedium)
            Text("请稍等一下。", style = MaterialTheme.typography.bodyLarge)
        }
        state.needsLocationPermission -> {
            Text("允许定位后查看天气", style = MaterialTheme.typography.titleMedium)
            Text(
                state.message ?: "只获取大致位置，用于查询当地天气。",
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = onWeatherAction) { Text("允许定位") }
        }
        else -> {
            Text("天气暂未更新", style = MaterialTheme.typography.titleMedium)
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            TextButton(onClick = onWeatherAction) { Text("重新获取") }
        }
    }
}

@Composable
private fun HomeActionCard(action: HomeAction) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 112.dp)
            .semantics { contentDescription = action.title }
            .clickable(onClickLabel = "打开${action.title}", onClick = action.onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ElderSpacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(ElderSpacing.small))
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ElderHomePreview() {
    SilverAgeAssistantTheme(darkTheme = false) {
        ElderHomeScreen(onConversation = {}, onReminders = {}, onFamilyContacts = {}, onNews = {}, onSettings = {})
    }
}
