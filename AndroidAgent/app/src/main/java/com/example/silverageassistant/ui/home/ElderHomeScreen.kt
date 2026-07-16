package com.example.silverageassistant.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HomeAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val isEmergency: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun ElderHomeScreen(
    onConversation: () -> Unit,
    onReminders: () -> Unit,
    onLifeAssistant: () -> Unit,
    onFamilyContacts: () -> Unit,
    onMusic: () -> Unit,
    onSos: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    elderName: String = "",
) {
    val formattedDate = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
    }
    val actions = listOf(
        HomeAction("和我说话", "问问题、聊聊天", Icons.AutoMirrored.Rounded.Chat, onClick = onConversation),
        HomeAction("今日提醒", "查看今天要做的事", Icons.Rounded.NotificationsActive, onClick = onReminders),
        HomeAction("生活助手", "购物、出行和查询", Icons.Rounded.ShoppingBag, onClick = onLifeAssistant),
        HomeAction("联系家人", "给家人打电话", Icons.Rounded.FamilyRestroom, onClick = onFamilyContacts),
        HomeAction("听音乐", "播放手机里的音乐", Icons.Rounded.MusicNote, onClick = onMusic),
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
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                LargeActionButton(
                    text = "点这里和我说话",
                    contentDescription = "打开语音对话",
                    icon = Icons.Rounded.Mic,
                    onClick = onConversation,
                    modifier = Modifier.padding(ElderSpacing.medium),
                )
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(modifier = Modifier.padding(ElderSpacing.medium)) {
                        Text(
                            text = "天气暂未更新",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "最近提醒：上午 8:00 服药",
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
        ElderHomeScreen(onConversation = {}, onReminders = {}, onLifeAssistant = {}, onFamilyContacts = {}, onMusic = {}, onSos = {}, onSettings = {})
    }
}
