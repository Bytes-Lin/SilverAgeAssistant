package com.example.silverageassistant.domain.gui.vision

/**
 * Android 平台截图边界。实现必须在每个 GUI 动作前产生新 frameId，并只把经过预算处理的
 * uploadBytes 交给 MLLM；原始截图不得进入日志、Room、中台或 Agent 长期记忆。
 */
data class GuiScreenObservation(
    val geometry: ScreenFrameGeometry,
    val uploadBytes: ByteArray,
    val mimeType: String,
    val targetPackage: String,
    val windowTitle: String?,
    val nodes: List<GuiNodeSnapshot>,
)

fun interface GuiScreenObserver {
    suspend fun captureForNextAction(): GuiScreenObservation
}

/**
 * 单帧内可供模型引用的无障碍节点。nodeId 是当前 frameId 下的树路径，只能用于紧随其后的
 * 一个动作；页面或窗口变化后必须重新观察。
 */
data class GuiNodeSnapshot(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val boundsInScreen: PixelRect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
)
