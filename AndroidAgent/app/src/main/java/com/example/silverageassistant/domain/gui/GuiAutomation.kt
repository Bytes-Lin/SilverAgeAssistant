package com.example.silverageassistant.domain.gui

import com.example.silverageassistant.domain.agent.AgentToolRegistry
import com.example.silverageassistant.domain.gui.vision.GuiScreenObservation
import com.example.silverageassistant.domain.gui.vision.ModelPointPrediction
import com.example.silverageassistant.domain.gui.vision.ScreenshotPixelBudget

sealed interface GuiObserveResult {
    data class Captured(val observation: GuiScreenObservation) : GuiObserveResult
    data class TargetNotForeground(val foregroundPackage: String?) : GuiObserveResult
    data class SensitiveScreen(val message: String) : GuiObserveResult
    data class Unavailable(val message: String) : GuiObserveResult
}

enum class GuiScrollDirection {
    FORWARD,
    BACKWARD,
}

sealed interface GuiDeviceAction {
    val frameId: String

    data class ClickNode(
        override val frameId: String,
        val nodeId: String,
    ) : GuiDeviceAction

    data class ClickPoint(
        override val frameId: String,
        val point: ModelPointPrediction,
    ) : GuiDeviceAction

    data class InputText(
        override val frameId: String,
        val nodeId: String,
        val text: String,
    ) : GuiDeviceAction

    /** 纯坐标实验中，输入目标必须由上一步坐标点击取得焦点。 */
    data class InputTextFocused(
        override val frameId: String,
        val text: String,
    ) : GuiDeviceAction

    data class Scroll(
        override val frameId: String,
        val nodeId: String?,
        val direction: GuiScrollDirection,
    ) : GuiDeviceAction

    data class Back(
        override val frameId: String,
    ) : GuiDeviceAction
}

sealed interface GuiDeviceActionResult {
    data class Success(val summary: String) : GuiDeviceActionResult
    data class Rejected(val message: String) : GuiDeviceActionResult
    data class Failed(val message: String) : GuiDeviceActionResult
}

data class GuiDeviceActionAuthorization(
    val allowOrderSubmission: Boolean = false,
)

interface GuiDeviceController {
    suspend fun observe(
        targetPackage: String,
        pixelBudget: ScreenshotPixelBudget,
    ): GuiObserveResult

    suspend fun perform(
        targetPackage: String,
        observation: GuiScreenObservation,
        action: GuiDeviceAction,
        authorization: GuiDeviceActionAuthorization = GuiDeviceActionAuthorization(),
    ): GuiDeviceActionResult
}

fun interface GuiDeviceControllerProvider {
    suspend fun awaitController(): GuiDeviceController?
}

data class GuiStepRecord(
    val actionSummary: String,
    val resultSummary: String,
)

data class GuiPlanningRequest(
    val task: String,
    val attempt: Int,
    val step: Int,
    val observation: GuiScreenObservation,
    val history: List<GuiStepRecord>,
    val sharedTools: AgentToolRegistry,
)

enum class GuiConfirmationScope {
    GENERAL,
    ORDER_SUBMISSION,
}

sealed interface GuiPlannedAction {
    data class Device(val action: GuiDeviceAction, val summary: String) : GuiPlannedAction
    data class Wait(val milliseconds: Long, val reason: String) : GuiPlannedAction
    data class AskElder(
        val message: String,
        val confirmationScope: GuiConfirmationScope,
    ) : GuiPlannedAction
    data class ReadyForPayment(val message: String) : GuiPlannedAction
    data class UseTool(
        val toolName: String,
        val argumentsJson: String,
        val reason: String,
    ) : GuiPlannedAction
    data class Complete(val summary: String) : GuiPlannedAction
    data class Fail(val message: String) : GuiPlannedAction
}

fun interface GuiVisionPlanner {
    suspend fun plan(request: GuiPlanningRequest): GuiPlannedAction
}
