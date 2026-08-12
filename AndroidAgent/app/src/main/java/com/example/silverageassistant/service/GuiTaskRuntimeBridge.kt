package com.example.silverageassistant.service

import com.example.silverageassistant.domain.gui.GuiTargetAppLauncher
import com.example.silverageassistant.domain.gui.GuiTaskController
import com.example.silverageassistant.domain.voice.VoiceInteractionCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GuiTaskRuntimeRegistration(
    val controller: GuiTaskController,
    val targetAppLauncher: GuiTargetAppLauncher,
    val voiceCoordinator: VoiceInteractionCoordinator,
)

/**
 * 同进程 AccessibilityService 与 Compose 组合根之间的最小桥。
 *
 * 不保存 Context、截图、页面节点或任务历史；Activity 组合释放时必须解绑。
 */
object GuiTaskRuntimeBridge {
    private val mutableRegistration =
        MutableStateFlow<GuiTaskRuntimeRegistration?>(null)
    val registration: StateFlow<GuiTaskRuntimeRegistration?> =
        mutableRegistration.asStateFlow()

    fun bind(
        controller: GuiTaskController,
        targetAppLauncher: GuiTargetAppLauncher,
        voiceCoordinator: VoiceInteractionCoordinator,
    ) {
        mutableRegistration.value = GuiTaskRuntimeRegistration(
            controller = controller,
            targetAppLauncher = targetAppLauncher,
            voiceCoordinator = voiceCoordinator,
        )
    }

    fun unbind(controller: GuiTaskController) {
        if (mutableRegistration.value?.controller === controller) {
            mutableRegistration.value = null
        }
    }
}
