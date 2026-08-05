package com.example.silverageassistant.ui.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.domain.gui.GuiDebugEvent
import com.example.silverageassistant.domain.gui.GuiDebugSettings
import com.example.silverageassistant.domain.gui.GuiDebugTrace
import com.example.silverageassistant.domain.gui.GuiGroundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GuiDebugPanelHost(modifier: Modifier = Modifier) {
    val events by GuiDebugTrace.events.collectAsState()
    val groundingMode by GuiDebugSettings.groundingMode.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    ExtendedFloatingActionButton(
        onClick = { showDialog = true },
        modifier = modifier.padding(16.dp),
        icon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
        text = {
            Text(
                "GUI 调试·" + when (groundingMode) {
                    GuiGroundingMode.HYBRID_NODE_FIRST -> "节点"
                    GuiGroundingMode.COORDINATE_ONLY -> "坐标"
                } + " ${events.size}",
            )
        },
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("GUI Agent 内存追踪") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (groundingMode) {
                            GuiGroundingMode.HYBRID_NODE_FIRST ->
                                "定位模式：节点优先（默认）"
                            GuiGroundingMode.COORDINATE_ONLY ->
                                "定位模式：纯坐标实验（模型不接收节点）"
                        },
                    )
                    OutlinedButton(
                        onClick = {
                            GuiDebugSettings.setGroundingMode(
                                when (groundingMode) {
                                    GuiGroundingMode.HYBRID_NODE_FIRST ->
                                        GuiGroundingMode.COORDINATE_ONLY
                                    GuiGroundingMode.COORDINATE_ONLY ->
                                        GuiGroundingMode.HYBRID_NODE_FIRST
                                },
                            )
                        },
                    ) {
                        Text(
                            when (groundingMode) {
                                GuiGroundingMode.HYBRID_NODE_FIRST -> "切换为纯坐标实验"
                                GuiGroundingMode.COORDINATE_ONLY -> "切回节点优先"
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SelectionContainer {
                            Text(
                                if (events.isEmpty()) {
                                    "还没有追踪事件。请先发送一条 GUI 操作指令。"
                                } else {
                                    events.joinToString("\n\n") { it.render() }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { showDialog = false }) {
                    Text("关闭")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = GuiDebugTrace::clear) {
                    Text("清空")
                }
            },
        )
    }
}

private fun GuiDebugEvent.render(): String = buildString {
    append(TIME_FORMATTER.format(Instant.ofEpochMilli(timestampEpochMillis)))
    append("  [")
    append(source)
    append('/')
    append(stage)
    append("]\n")
    append(message)
    details?.takeIf(String::isNotBlank)?.let {
        append("\n")
        append(it)
    }
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    .withZone(ZoneId.systemDefault())
