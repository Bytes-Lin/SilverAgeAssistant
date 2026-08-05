package com.example.silverageassistant.ui.gui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.silverageassistant.domain.gui.GuiPauseReason
import com.example.silverageassistant.domain.gui.GuiRunPhase
import com.example.silverageassistant.domain.gui.GuiTargetAppLauncher
import com.example.silverageassistant.domain.gui.GuiTaskController
import com.example.silverageassistant.domain.gui.GuiTaskSnapshot
import kotlinx.coroutines.launch

object GuiTaskControlTestTags {
    const val CONTROL_BAR = "gui_task_control_bar"
    const val PRIMARY_BUTTON = "gui_task_primary_button"
    const val VOICE_BUTTON = "gui_task_voice_button"
    const val CANCEL_BUTTON = "gui_task_cancel_button"
    const val CANCEL_DIALOG = "gui_task_cancel_dialog"
}

@Composable
fun GuiTaskControlHost(
    controller: GuiTaskController,
    targetAppLauncher: GuiTargetAppLauncher,
    modifier: Modifier = Modifier,
) {
    val task by controller.activeTask.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner, controller, targetAppLauncher) {
        val observer = LifecycleEventObserver { _, event ->
            val current = controller.activeTask.value
            val targetSession = targetAppLauncher.activeSession.value
            if (
                event == Lifecycle.Event.ON_RESUME &&
                current != null &&
                targetSession?.todoId == current.todoId &&
                current.phase.isRunningOutsideAssistant()
            ) {
                scope.launch {
                    controller.pause(GuiPauseReason.TARGET_APP_LEFT)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentTask = task?.takeIf { it.phase.isControllable() } ?: return
    GuiTaskControlBar(
        task = currentTask,
        onPause = {
            scope.launch { controller.pause(GuiPauseReason.ELDER_REQUEST) }
        },
        onResume = {
            scope.launch {
                val canResume = if (currentTask.pauseReason == GuiPauseReason.TARGET_APP_LEFT) {
                    targetAppLauncher.returnToTask(currentTask.todoId)
                } else {
                    true
                }
                if (canResume) controller.resume()
            }
        },
        onCancel = {
            scope.launch { controller.cancel() }
        },
        modifier = modifier,
    )
}

@Composable
fun GuiTaskControlBar(
    task: GuiTaskSnapshot,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    voiceAvailable: Boolean = false,
    onVoicePressed: () -> Unit = {},
) {
    var showCancelConfirmation by remember(task.todoId) { mutableStateOf(false) }
    val isPaused = task.phase == GuiRunPhase.PAUSED
    val isUserGate = task.phase.isUserGate()
    val primaryLabel = when {
        task.phase == GuiRunPhase.WAITING_ELDER_CONFIRMATION -> "确认并继续"
        task.phase == GuiRunPhase.WAITING_MANUAL_PAYMENT -> "付款完成后继续"
        task.phase == GuiRunPhase.WAITING_USER_INPUT -> "继续"
        !isPaused -> "暂停"
        task.pauseReason == GuiPauseReason.TARGET_APP_LEFT -> "返回任务"
        else -> "继续"
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .zIndex(10f)
            .testTag(GuiTaskControlTestTags.CONTROL_BAR)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "GUI 辅助任务控制条，当前状态${task.phase.displayName()}"
            },
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = task.content,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = task.statusMessage ?: task.phase.displayName(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = if (isPaused || isUserGate) onResume else onPause,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag(GuiTaskControlTestTags.PRIMARY_BUTTON),
                ) {
                    Icon(
                        imageVector = if (isPaused || isUserGate) {
                            Icons.Rounded.PlayArrow
                        } else {
                            Icons.Rounded.Pause
                        },
                        contentDescription = null,
                    )
                    Text(primaryLabel)
                }
                if (voiceAvailable) {
                    IconButton(
                        onClick = onVoicePressed,
                        modifier = Modifier
                            .size(52.dp)
                            .testTag(GuiTaskControlTestTags.VOICE_BUTTON)
                            .semantics { contentDescription = "按住说话" },
                        colors = IconButtonDefaults.filledIconButtonColors(),
                        shape = CircleShape,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showCancelConfirmation = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag(GuiTaskControlTestTags.CANCEL_BUTTON),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                    Text("取消")
                }
            }
        }
    }

    if (showCancelConfirmation) {
        AlertDialog(
            modifier = Modifier.testTag(GuiTaskControlTestTags.CANCEL_DIALOG),
            onDismissRequest = { showCancelConfirmation = false },
            title = { Text("确认取消任务？") },
            text = { Text("取消后，本次手机辅助任务将立即停止。") },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelConfirmation = false
                        onCancel()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("确认取消")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCancelConfirmation = false }) {
                    Text("不取消")
                }
            },
        )
    }
}

private fun GuiRunPhase.isControllable(): Boolean = this !in setOf(
    GuiRunPhase.COMPLETED,
    GuiRunPhase.FAILED,
    GuiRunPhase.CANCELLED,
    GuiRunPhase.UNAVAILABLE,
)

private fun GuiRunPhase.isUserGate(): Boolean = this in setOf(
    GuiRunPhase.WAITING_USER_INPUT,
    GuiRunPhase.WAITING_ELDER_CONFIRMATION,
    GuiRunPhase.WAITING_MANUAL_PAYMENT,
)

private fun GuiRunPhase.isRunningOutsideAssistant(): Boolean = this !in setOf(
    GuiRunPhase.PAUSED,
    GuiRunPhase.COMPLETED,
    GuiRunPhase.FAILED,
    GuiRunPhase.CANCELLED,
    GuiRunPhase.UNAVAILABLE,
)

private fun GuiRunPhase.displayName(): String = when (this) {
    GuiRunPhase.RUNNING -> "正在执行"
    GuiRunPhase.PAUSED -> "任务已暂停"
    GuiRunPhase.RETRYING -> "正在开始第二次尝试"
    GuiRunPhase.WAITING_USER_INPUT -> "等待老人操作或说明"
    GuiRunPhase.WAITING_ELDER_CONFIRMATION -> "等待老人确认"
    GuiRunPhase.WAITING_MANUAL_PAYMENT -> "等待老人亲自付款"
    GuiRunPhase.COMPLETED -> "任务已完成"
    GuiRunPhase.FAILED -> "任务失败"
    GuiRunPhase.CANCELLED -> "任务已取消"
    GuiRunPhase.UNAVAILABLE -> "当前无法执行"
}
