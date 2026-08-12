package com.example.silverageassistant.domain.gui

import com.example.silverageassistant.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GuiGroundingMode {
    HYBRID_NODE_FIRST,
    COORDINATE_ONLY,
}

/**
 * 仅供 Debug 真机对照实验使用。设置只存在于当前进程，不写 DataStore、Room 或中台；
 * Release 构建固定使用节点优先混合模式。
 */
object GuiDebugSettings {
    private val mutableGroundingMode = MutableStateFlow(GuiGroundingMode.HYBRID_NODE_FIRST)
    val groundingMode: StateFlow<GuiGroundingMode> = mutableGroundingMode.asStateFlow()

    fun currentGroundingMode(): GuiGroundingMode = if (BuildConfig.GUI_DEBUG_ENABLED) {
        mutableGroundingMode.value
    } else {
        GuiGroundingMode.HYBRID_NODE_FIRST
    }

    fun setGroundingMode(mode: GuiGroundingMode) {
        if (!BuildConfig.GUI_DEBUG_ENABLED || mutableGroundingMode.value == mode) return
        mutableGroundingMode.value = mode
        GuiDebugTrace.record(
            source = "settings",
            stage = "grounding_mode_changed",
            message = when (mode) {
                GuiGroundingMode.HYBRID_NODE_FIRST -> "已切换为节点优先混合定位"
                GuiGroundingMode.COORDINATE_ONLY -> "已切换为纯坐标定位实验"
            },
        )
    }
}
