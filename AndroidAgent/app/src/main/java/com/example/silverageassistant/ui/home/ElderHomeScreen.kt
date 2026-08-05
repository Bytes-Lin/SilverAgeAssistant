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
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Sos
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
    val subtitle: String,
    val icon: ImageVector,
    val isEmergency: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun ElderHomeRoute(
    onConversation: () -> Unit,
    onReminders: () -> Unit,
    onLifeAssistant: () -> Unit,
    onFamilyContacts: () -> Unit,
    onNews: () -> Unit,
    onSos: () -> Unit,
    onSettings: () -> Unit,
    elderName: String,
    sessionConnectionStatus: SessionConnectionStatus,
    sessionMessage: String?,
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
        onLifeAssistant = onLifeAssistant,
        onFamilyContacts = onFamilyContacts,
        onNews = onNews,
        onSos = onSos,
        onSettings = onSettings,
        modifier = modifier,
        elderName = elderName,
        sessionConnectionStatus = sessionConnectionStatus,
        sessionMessage = sessionMessage,
        weatherState = weatherState,
        onWeatherAction = requestOrRefreshWeather,
    )
}

@Composable
fun ElderHomeScreen(
    onConversation: () -> Unit,
    onReminders: () -> Unit,
    onLifeAssistant: () -> Unit,
    onFamilyContacts: () -> Unit,
    onNews: () -> Unit,
    onSos: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    elderName: String = "",
    sessionConnectionStatus: SessionConnectionStatus = SessionConnectionStatus.Unknown,
    sessionMessage: String? = null,
    weatherState: HomeWeatherUiState = HomeWeatherUiState(),
    onWeatherAction: () -> Unit = {},
) {
    val formattedDate = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
    }
    val actions = listOf(
        HomeAction("和我说话", "问问题、聊聊天", Icons.AutoMirrored.Rounded.Chat, onClick = onConversation),
        HomeAction("今日提醒", "查看今天要做的事", Icons.Rounded.NotificationsActive, onClick = onReminders),
        HomeAction("生活助手", "购物、出行和查询", Icons.Rounded.ShoppingBag, onClick = onLifeAssistant),
        HomeAction("联系家人", "给家人打电话", Icons.Rounded.FamilyRestroom, onClick = onFamilyContacts),
        HomeAction("新闻播报", "查看今日热点新闻", Icons.AutoMirrored.Rounded.Article, onClick = onNews),
        HomeAction("紧急求助", "需要帮助时按这里", Icons.Rounded.Sos, isEmergency = true, onClick = onSos),
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
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(modifier = Modifier.height(ElderSpacing.small))
                if (sessionConnectionStatus != SessionConnectionStatus.Unknown) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Column(modifier = Modifier.padding(ElderSpacing.medium)) {
                            Text(
                                text = when (sessionConnectionStatus) {
                                    SessionConnectionStatus.Syncing -> "正在确认家人绑定"
                                    SessionConnectionStatus.Online -> "家人绑定正常"
                                    SessionConnectionStatus.Offline -> "暂时离线"
                                    SessionConnectionStatus.Invalid -> "家人绑定已失效"
                                    SessionConnectionStatus.Unknown -> ""
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (sessionMessage != null) {
                                Text(sessionMessage, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(ElderSpacing.small))
                }
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
                        Text(
                            text = "上午 8:00 服药",
                            style = MaterialTheme.typography.bodyLarge,
                        )
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
    val containerColor = if (action.isEmergency) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (action.isEmergency) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 154.dp)
            .semantics { contentDescription = "${action.title}，${action.subtitle}" }
            .clickable(onClickLabel = "打开${action.title}", onClick = action.onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
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
                tint = if (action.isEmergency) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(ElderSpacing.small))
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = action.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ElderHomePreview() {
    SilverAgeAssistantTheme(darkTheme = false) {
        ElderHomeScreen(onConversation = {}, onReminders = {}, onLifeAssistant = {}, onFamilyContacts = {}, onNews = {}, onSos = {}, onSettings = {})
    }
}
