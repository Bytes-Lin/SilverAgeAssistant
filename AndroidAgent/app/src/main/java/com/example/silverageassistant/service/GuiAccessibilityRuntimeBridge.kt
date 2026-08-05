package com.example.silverageassistant.service

import com.example.silverageassistant.domain.gui.GuiDeviceController
import com.example.silverageassistant.domain.gui.GuiDeviceControllerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 只桥接当前进程内已连接的 AccessibilityService。不会保存 Context、截图或节点。
 */
object GuiAccessibilityRuntimeBridge {
    private val controller = MutableStateFlow<GuiDeviceController?>(null)

    val provider = GuiDeviceControllerProvider {
        withTimeoutOrNull(CONTROLLER_WAIT_MILLIS) {
            controller.filterNotNull().first()
        }
    }

    fun bind(value: GuiDeviceController) {
        controller.value = value
    }

    fun unbind(value: GuiDeviceController) {
        if (controller.value === value) controller.value = null
    }

    private const val CONTROLLER_WAIT_MILLIS = 5_000L
}
