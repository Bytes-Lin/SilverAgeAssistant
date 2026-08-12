package com.example.silverageassistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Surface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.example.silverageassistant.BuildConfig
import com.example.silverageassistant.domain.gui.GuiDeviceAction
import com.example.silverageassistant.domain.gui.GuiDeviceActionAuthorization
import com.example.silverageassistant.domain.gui.GuiDeviceActionResult
import com.example.silverageassistant.domain.gui.GuiDeviceController
import com.example.silverageassistant.domain.gui.GuiDebugEvent
import com.example.silverageassistant.domain.gui.GuiDebugTrace
import com.example.silverageassistant.domain.gui.GuiObserveResult
import com.example.silverageassistant.domain.gui.GuiPauseReason
import com.example.silverageassistant.domain.gui.GuiRunPhase
import com.example.silverageassistant.domain.gui.GuiScrollDirection
import com.example.silverageassistant.domain.gui.GuiTaskSnapshot
import com.example.silverageassistant.domain.voice.VoiceListeningState
import com.example.silverageassistant.domain.gui.vision.AffineTransform2D
import com.example.silverageassistant.domain.gui.vision.GuiNodeSnapshot
import com.example.silverageassistant.domain.gui.vision.GuiScreenObservation
import com.example.silverageassistant.domain.gui.vision.ModelCoordinateMapper
import com.example.silverageassistant.domain.gui.vision.PixelRect
import com.example.silverageassistant.domain.gui.vision.PixelSize
import com.example.silverageassistant.domain.gui.vision.PointD
import com.example.silverageassistant.domain.gui.vision.ScreenFrameGeometry
import com.example.silverageassistant.domain.gui.vision.ScreenshotPixelBudget
import com.example.silverageassistant.domain.gui.vision.ScreenshotResizePlanner
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 经用户在系统设置中明确启用后，使用 TYPE_ACCESSIBILITY_OVERLAY 在第三方目标 App 上方
 * 展示 GUI 任务控制条，并提供仅限当前进程使用的截图、节点和单步动作能力。
 */
class GuiAccessibilityControlService : AccessibilityService(), GuiDeviceController {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var taskCollectionJob: Job? = null
    private var targetLeftPauseJob: Job? = null
    private var overlayView: View? = null
    private var debugSummaryView: TextView? = null
    private var renderedTask: GuiTaskSnapshot? = null
    private var cancelFallbackArmed = false
    private var currentFrameId: String? = null
    private var currentFrameTargetPackage: String? = null
    private var automationSuppressionUntilEpochMillis = 0L
    private var agentTouchSuppressionUntilEpochMillis = 0L
    private var observedTodoId: String? = null
    private var hasSeenTargetWindow = false
    private var voicePressActive = false
    private var voiceStartJob: Job? = null
    private var voiceCompletionJob: Job? = null
    private var voicePhaseBeforeRecording: GuiRunPhase? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        GuiAccessibilityRuntimeBridge.bind(this)
        if (BuildConfig.GUI_DEBUG_ENABLED) {
            serviceScope.launch {
                GuiDebugTrace.events.collect { events ->
                    debugSummaryView?.text = events.lastOrNull().debugSummaryText()
                        ?: "GUI 调试：等待事件"
                }
            }
        }
        serviceScope.launch {
            GuiTaskRuntimeBridge.registration.collect { registration ->
                taskCollectionJob?.cancel()
                if (registration == null) {
                    removeOverlay()
                } else {
                    taskCollectionJob = launch {
                        registration.controller.activeTask
                            .combine(
                                registration.targetAppLauncher.activeSession,
                            ) { task, session ->
                                task to session
                            }
                            .combine(registration.voiceCoordinator.enabled) {
                                    taskAndSession, voiceEnabled ->
                                val (task, session) = taskAndSession
                                Triple(task, session, voiceEnabled)
                            }
                            .collect { (task, session, voiceEnabled) ->
                                renderedTask = task
                                if (
                                    task == null ||
                                    task.phase.isTerminal() ||
                                    session?.todoId != task.todoId ||
                                    session?.isForegroundVerified != true
                                ) {
                                    removeOverlay()
                                } else {
                                    showOrUpdateOverlay(task, voiceEnabled)
                                }
                            }
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentEvent = event ?: return
        val registration = GuiTaskRuntimeBridge.registration.value ?: return
        val task = registration.controller.activeTask.value ?: return
        if (task.phase.isTerminal() || task.phase == GuiRunPhase.PAUSED) return
        val eventPackage = currentEvent.packageName?.toString() ?: return
        if (eventPackage == packageName) return
        val targetSession = registration.targetAppLauncher.activeSession.value
            ?.takeIf { it.todoId == task.todoId }
            ?: return
        val targetPackage = targetSession.targetApp.packageName
        val now = System.currentTimeMillis()

        if (observedTodoId != task.todoId) {
            observedTodoId = task.todoId
            hasSeenTargetWindow = false
            automationSuppressionUntilEpochMillis = maxOf(
                automationSuppressionUntilEpochMillis,
                now + APP_START_EVENT_GRACE_MILLIS,
            )
        }
        if (eventPackage == targetPackage) {
            hasSeenTargetWindow = true
            targetLeftPauseJob?.cancel()
            targetLeftPauseJob = null
            if (!targetSession.isForegroundVerified) {
                registration.targetAppLauncher.markForegroundVerified(task.todoId)
                GuiDebugTrace.record(
                    source = "accessibility",
                    stage = "foreground_event_verified",
                    message = "已由目标 App 窗口事件确认前台，显示任务控制条",
                    details = targetPackage,
                )
            }
        }
        if (currentEvent.eventType in FRAME_INVALIDATING_EVENTS) {
            invalidateFrame()
        }

        when (currentEvent.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> if (
                eventPackage == targetPackage &&
                now > agentTouchSuppressionUntilEpochMillis
            ) {
                GuiDebugTrace.record(
                    source = "accessibility",
                    stage = "pause_requested",
                    message = "检测到目标 App 内的人工触摸，暂停自动化",
                    details = "event=${currentEvent.eventType}\npackage=$eventPackage",
                )
                serviceScope.launch {
                    registration.controller.pause(GuiPauseReason.HUMAN_INTERVENTION)
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (
                eventPackage == targetPackage &&
                now > automationSuppressionUntilEpochMillis
            ) {
                GuiDebugTrace.record(
                    source = "accessibility",
                    stage = "pause_requested",
                    message = "检测到目标 App 内的人工点击，暂停自动化",
                    details = "event=${currentEvent.eventType}\npackage=$eventPackage",
                )
                serviceScope.launch {
                    registration.controller.pause(GuiPauseReason.HUMAN_INTERVENTION)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (
                eventPackage != targetPackage && hasSeenTargetWindow
            ) {
                scheduleTargetLeftPause(
                    todoId = task.todoId,
                    targetPackage = targetPackage,
                    eventPackage = eventPackage,
                )
            }

            else -> Unit
        }
    }

    /**
     * App 内 Activity、WebView 或系统过渡窗口可能短暂成为 active window。只有目标包持续离开
     * 一小段时间后才暂停，避免正常页面跳转打断 ReAct。
     */
    private fun scheduleTargetLeftPause(
        todoId: String,
        targetPackage: String,
        eventPackage: String,
    ) {
        targetLeftPauseJob?.cancel()
        targetLeftPauseJob = serviceScope.launch {
            delay(TARGET_LEFT_DEBOUNCE_MILLIS)
            val registration = GuiTaskRuntimeBridge.registration.value ?: return@launch
            val task = registration.controller.activeTask.value ?: return@launch
            val session = registration.targetAppLauncher.activeSession.value ?: return@launch
            if (
                task.todoId != todoId ||
                task.phase.isTerminal() ||
                task.phase == GuiRunPhase.PAUSED ||
                session.todoId != todoId ||
                session.targetApp.packageName != targetPackage
            ) {
                return@launch
            }
            val foregroundPackage = rootInActiveWindow?.packageName?.toString()
            if (
                foregroundPackage == null ||
                foregroundPackage == targetPackage ||
                foregroundPackage == packageName
            ) {
                return@launch
            }
            GuiDebugTrace.record(
                source = "accessibility",
                stage = "pause_requested",
                message = "目标 App 持续离开前台，暂停自动化",
                details = "eventPackage=$eventPackage\nforeground=$foregroundPackage",
            )
            registration.controller.pause(GuiPauseReason.TARGET_APP_LEFT)
        }
    }

    override fun onInterrupt() {
        stopVoiceForActiveGuiTask()
    }

    override fun onDestroy() {
        stopVoiceForActiveGuiTask()
        GuiAccessibilityRuntimeBridge.unbind(this)
        targetLeftPauseJob?.cancel()
        invalidateFrame()
        removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopVoiceForActiveGuiTask() {
        val registration = GuiTaskRuntimeBridge.registration.value ?: return
        val task = registration.controller.activeTask.value ?: return
        if (!task.phase.isTerminal()) registration.voiceCoordinator.stopAll()
    }

    override suspend fun observe(
        targetPackage: String,
        pixelBudget: ScreenshotPixelBudget,
    ): GuiObserveResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return GuiObserveResult.Unavailable(
                "当前 Android 版本暂不支持 GUI Agent 截图；需要 Android 11 或更高版本。",
            )
        }
        val windowState = withContext(Dispatchers.Main.immediate) {
            readTargetWindow(targetPackage)
        } ?: return GuiObserveResult.TargetNotForeground(
            foregroundPackage = withContext(Dispatchers.Main.immediate) {
                rootInActiveWindow?.packageName?.toString()
            },
        )
        hasSeenTargetWindow = true
        if (isSensitiveScreen(windowState.nodes)) {
            invalidateFrame()
            return GuiObserveResult.SensitiveScreen(
                "这是付款或安全验证页面。请老人亲自完成操作，完成后点击“继续”。",
            )
        }
        return runCatching {
            val screenshot = captureWithoutOverlay()
            try {
                val latestPackage = withContext(Dispatchers.Main.immediate) {
                    rootInActiveWindow?.packageName?.toString()
                }
                if (latestPackage != targetPackage) {
                    invalidateFrame()
                    return GuiObserveResult.TargetNotForeground(latestPackage)
                }
                buildObservation(
                    bitmap = screenshot,
                    targetPackage = targetPackage,
                    windowTitle = windowState.title,
                    targetBounds = windowState.bounds,
                    nodes = windowState.nodes,
                    pixelBudget = pixelBudget,
                ).also {
                    currentFrameId = it.geometry.frameId
                    currentFrameTargetPackage = targetPackage
                }.let(GuiObserveResult::Captured)
            } finally {
                screenshot.recycle()
            }
        }.getOrElse {
            invalidateFrame()
            GuiObserveResult.Unavailable("暂时无法截取当前页面，请稍后重试。")
        }
    }

    override suspend fun perform(
        targetPackage: String,
        observation: GuiScreenObservation,
        action: GuiDeviceAction,
        authorization: GuiDeviceActionAuthorization,
    ): GuiDeviceActionResult {
        if (
            action.frameId != observation.geometry.frameId ||
            currentFrameId != action.frameId ||
            currentFrameTargetPackage != targetPackage
        ) {
            return GuiDeviceActionResult.Rejected("页面已经变化，需要重新观察")
        }
        val activePackage = withContext(Dispatchers.Main.immediate) {
            rootInActiveWindow?.packageName?.toString()
        }
        if (activePackage != targetPackage) {
            invalidateFrame()
            return GuiDeviceActionResult.Rejected("目标应用已不在前台")
        }
        val actionStartedAt = System.currentTimeMillis()
        automationSuppressionUntilEpochMillis =
            actionStartedAt + AGENT_EVENT_SUPPRESSION_MILLIS
        agentTouchSuppressionUntilEpochMillis =
            actionStartedAt + AGENT_EVENT_SUPPRESSION_MILLIS
        invalidateFrame()
        val result = when (action) {
            is GuiDeviceAction.ClickNode -> performNodeClick(
                targetPackage,
                observation,
                action.nodeId,
                authorization,
            )

            is GuiDeviceAction.ClickPoint -> {
                val mapped = runCatching {
                    ModelCoordinateMapper.toScreen(action.point, observation.geometry)
                }.getOrElse { error ->
                    GuiDebugTrace.record(
                        source = "device_click",
                        stage = "coordinate_mapping_rejected",
                        message = "模型坐标无法映射到当前物理屏幕",
                        details = "prediction=${action.point.x},${action.point.y}\n" +
                            "reason=${error.message}",
                    )
                    return GuiDeviceActionResult.Rejected("模型返回的点击坐标无效")
                }
                GuiDebugTrace.record(
                    source = "device_click",
                    stage = "coordinate_mapped",
                    message = "模型坐标已映射到物理屏幕",
                    details = "normalized=${action.point.x},${action.point.y}\n" +
                        "upload=${mapped.uploadPoint.x.toInt()},${mapped.uploadPoint.y.toInt()}\n" +
                        "capture=${mapped.capturePoint.x.toInt()},${mapped.capturePoint.y.toInt()}\n" +
                        "screen=${mapped.screenPoint.x.toInt()},${mapped.screenPoint.y.toInt()}\n" +
                        "screenSize=${observation.geometry.screenSize.width}x" +
                        observation.geometry.screenSize.height,
                )
                validatePointRisk(
                    point = mapped.screenPoint,
                    observation = observation,
                    authorization = authorization,
                )?.let { return it }
                val performed = withOverlayHidden {
                    dispatchTap(mapped.screenPoint)
                }
                if (performed) {
                    GuiDeviceActionResult.Success("已点击屏幕")
                } else {
                    GuiDeviceActionResult.Failed("系统没有执行点击")
                }
            }

            is GuiDeviceAction.InputText -> performTextInput(
                targetPackage,
                action.nodeId,
                action.text,
            )

            is GuiDeviceAction.InputTextFocused -> performFocusedTextInput(
                targetPackage,
                action.text,
            )

            is GuiDeviceAction.Scroll -> performScroll(
                targetPackage,
                action.nodeId,
                action.direction,
            )

            is GuiDeviceAction.Back -> {
                if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                    GuiDeviceActionResult.Success("已返回上一页")
                } else {
                    GuiDeviceActionResult.Failed("系统没有执行返回")
                }
            }
        }
        if (result is GuiDeviceActionResult.Success) {
            automationSuppressionUntilEpochMillis = maxOf(
                automationSuppressionUntilEpochMillis,
                System.currentTimeMillis() + AGENT_POST_ACTION_GRACE_MILLIS,
            )
        }
        return result
    }

    private fun readTargetWindow(targetPackage: String): TargetWindowState? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != targetPackage) return null
        val screenSize = currentScreenSize()
        val targetWindow = windows
            .asSequence()
            .filter { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.root?.packageName?.toString() == targetPackage
            }
            .maxByOrNull { window ->
                Rect().also(window::getBoundsInScreen).let { bounds ->
                    bounds.width().coerceAtLeast(0).toLong() *
                        bounds.height().coerceAtLeast(0).toLong()
                }
            }
        val nodes = mutableListOf<GuiNodeSnapshot>()
        collectNodes(
            node = root,
            path = "0",
            output = nodes,
            screenSize = screenSize,
            depth = 0,
        )
        return TargetWindowState(
            title = targetWindow?.title?.toString()?.take(MAX_NODE_TEXT_LENGTH),
            // 同一 App 可能暴露多个局部 application window。用包名验证前台即可，
            // MLLM 必须看到完整显示区域，否则首页入口可能被错误裁掉。
            bounds = PixelRect.full(screenSize),
            nodes = nodes,
        )
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        path: String,
        output: MutableList<GuiNodeSnapshot>,
        screenSize: PixelSize,
        depth: Int,
    ) {
        if (
            depth > MAX_NODE_DEPTH ||
            output.size >= MAX_NODE_COUNT ||
            !node.isVisibleToUser
        ) {
            return
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        val clippedBounds = bounds.toPixelRect(screenSize)
        val text = node.text?.toString()?.sanitizeNodeText()
        val description = node.contentDescription?.toString()?.sanitizeNodeText()
        val relevant = node.isClickable ||
            node.isEditable ||
            node.isScrollable ||
            !text.isNullOrBlank() ||
            !description.isNullOrBlank()
        if (relevant && clippedBounds != null) {
            output += GuiNodeSnapshot(
                nodeId = path,
                text = text,
                contentDescription = description,
                className = node.className?.toString()?.take(MAX_CLASS_NAME_LENGTH),
                viewId = node.viewIdResourceName?.take(MAX_VIEW_ID_LENGTH),
                boundsInScreen = clippedBounds,
                clickable = node.isClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                password = node.isPassword,
            )
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectNodes(
                node = child,
                path = "$path.$index",
                output = output,
                screenSize = screenSize,
                depth = depth + 1,
            )
            if (output.size >= MAX_NODE_COUNT) break
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureWithoutOverlay(): Bitmap =
        withOverlayHidden {
            captureDisplayBitmap()
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureDisplayBitmap(): Bitmap {
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                DEFAULT_DISPLAY_ID,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                buffer,
                                screenshot.colorSpace,
                            )
                            val softwareBitmap = hardwareBitmap?.copy(
                                Bitmap.Config.ARGB_8888,
                                false,
                            )
                            if (softwareBitmap == null) {
                                continuation.resumeWithException(
                                    IllegalStateException("无法读取系统截图"),
                                )
                            } else {
                                continuation.resume(softwareBitmap)
                            }
                        } finally {
                            buffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        continuation.resumeWithException(
                            IllegalStateException("系统截图失败：$errorCode"),
                        )
                    }
                },
            )
        }
    }

    private suspend fun buildObservation(
        bitmap: Bitmap,
        targetPackage: String,
        windowTitle: String?,
        targetBounds: PixelRect,
        nodes: List<GuiNodeSnapshot>,
        pixelBudget: ScreenshotPixelBudget,
    ): GuiScreenObservation = withContext(Dispatchers.Default) {
        val captureSize = PixelSize(bitmap.width, bitmap.height)
        val screenSize = currentScreenSize()
        val captureToScreen = AffineTransform2D.scale(
            screenSize.width.toDouble() / captureSize.width,
            screenSize.height.toDouble() / captureSize.height,
        )
        val screenToCapture = captureToScreen.inverse()
        val captureTopLeft = screenToCapture.map(
            PointD(targetBounds.left, targetBounds.top),
        )
        val captureBottomRight = screenToCapture.map(
            PointD(targetBounds.right, targetBounds.bottom),
        )
        val cropLeft = floor(captureTopLeft.x).toInt().coerceIn(0, bitmap.width - 1)
        val cropTop = floor(captureTopLeft.y).toInt().coerceIn(0, bitmap.height - 1)
        val cropRight = ceil(captureBottomRight.x).toInt()
            .coerceIn(cropLeft + 1, bitmap.width)
        val cropBottom = ceil(captureBottomRight.y).toInt()
            .coerceIn(cropTop + 1, bitmap.height)
        val crop = PixelRect(
            left = cropLeft.toDouble(),
            top = cropTop.toDouble(),
            right = cropRight.toDouble(),
            bottom = cropBottom.toDouble(),
        )
        val modelImage = ScreenshotResizePlanner.plan(
            captureSize = captureSize,
            cropInCapture = crop,
            budget = pixelBudget,
        )
        val cropped = Bitmap.createBitmap(
            bitmap,
            cropLeft,
            cropTop,
            cropRight - cropLeft,
            cropBottom - cropTop,
        )
        val resized = if (
            cropped.width == modelImage.uploadSize.width &&
            cropped.height == modelImage.uploadSize.height
        ) {
            cropped
        } else {
            Bitmap.createScaledBitmap(
                cropped,
                modelImage.uploadSize.width,
                modelImage.uploadSize.height,
                true,
            )
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            check(resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                "无法压缩截图"
            }
            stream.toByteArray()
        }
        GuiDebugTrace.record(
            source = "screen",
            stage = "captured",
            message = "截图已按完整显示区域缩放和压缩，不保存原图",
            details = "capture=${captureSize.width}x${captureSize.height}\n" +
                "screen=${screenSize.width}x${screenSize.height}\n" +
                "captureToScreen=" +
                "${screenSize.width.toDouble() / captureSize.width}," +
                "${screenSize.height.toDouble() / captureSize.height}\n" +
                "crop=${cropRight - cropLeft}x${cropBottom - cropTop}" +
                "@($cropLeft,$cropTop)\n" +
                "upload=${modelImage.uploadSize.width}x${modelImage.uploadSize.height}\n" +
                "bytes=${bytes.size}\nnodes=${nodes.size}",
        )
        if (resized !== cropped) resized.recycle()
        if (cropped !== bitmap) cropped.recycle()
        val frameId = UUID.randomUUID().toString()
        GuiScreenObservation(
            geometry = ScreenFrameGeometry(
                frameId = frameId,
                capturedAtEpochMillis = System.currentTimeMillis(),
                displayId = DEFAULT_DISPLAY_ID,
                rotationDegrees = currentRotationDegrees(),
                screenSize = screenSize,
                targetWindowInScreen = targetBounds,
                modelImage = modelImage,
                captureToScreen = captureToScreen,
            ),
            uploadBytes = bytes,
            mimeType = "image/jpeg",
            targetPackage = targetPackage,
            windowTitle = windowTitle,
            nodes = nodes,
        )
    }

    private suspend fun performNodeClick(
        targetPackage: String,
        observation: GuiScreenObservation,
        nodeId: String,
        authorization: GuiDeviceActionAuthorization,
    ): GuiDeviceActionResult = withContext(Dispatchers.Main.immediate) {
        val snapshot = observation.nodes.firstOrNull { it.nodeId == nodeId }
            ?: return@withContext GuiDeviceActionResult.Rejected("模型引用了不存在的页面控件")
        if (snapshot.password) {
            return@withContext GuiDeviceActionResult.Rejected("禁止操作密码输入控件")
        }
        validateClickRisk(snapshot.text, snapshot.contentDescription, authorization)
            ?.let { return@withContext it }

        val pathCandidate = resolveNode(targetPackage, nodeId)
        val exactCandidate = pathCandidate?.takeIf { nodeMatchesSnapshot(it, snapshot) }
        val candidate = exactCandidate ?: findSemanticNode(targetPackage, snapshot)
        val resolutionMode = when {
            exactCandidate != null -> "exact_node"
            candidate != null -> "semantic_rematch"
            else -> "unresolved"
        }

        if (candidate == null) {
            GuiDebugTrace.record(
                source = "device_click",
                stage = "node_unresolved",
                message = "节点路径已变化，且未找到可信的同语义控件",
                details = "node=$nodeId\nlabel=${snapshot.debugLabel()}",
            )
            return@withContext GuiDeviceActionResult.Rejected("页面控件已经变化，需要重新观察")
        }

        if (exactCandidate == null) {
            GuiDebugTrace.record(
                source = "device_click",
                stage = "semantic_rematch",
                message = "原节点路径已变化，已重新匹配当前页面控件",
                details = "node=$nodeId\nlabel=${snapshot.debugLabel()}",
            )
        }

        clickNodeOrAncestor(candidate, authorization)?.let { result ->
            GuiDebugTrace.record(
                source = "device_click",
                stage = "${resolutionMode}_action",
                message = result.debugClickMessage(),
                details = "node=$nodeId\nlabel=${snapshot.debugLabel()}",
            )
            return@withContext result
        }

        val liveBounds = Rect().also(candidate::getBoundsInScreen)
            .toPixelRect(observation.geometry.screenSize)
            ?: return@withContext GuiDeviceActionResult.Rejected("当前控件没有有效点击区域")
        val point = PointD(
            x = (liveBounds.left + liveBounds.right) / 2.0,
            y = (liveBounds.top + liveBounds.bottom) / 2.0,
        )
        if (!observation.geometry.targetWindowInScreen.contains(point)) {
            return@withContext GuiDeviceActionResult.Rejected("当前控件已移出目标应用窗口")
        }
        validatePointRisk(point, observation, authorization)
            ?.let { return@withContext it }
        val performed = withOverlayHidden { dispatchTap(point) }
        val result = if (performed) {
            GuiDeviceActionResult.Success("已通过当前控件位置点击页面")
        } else {
            GuiDeviceActionResult.Failed("系统没有执行控件位置点击")
        }
        GuiDebugTrace.record(
            source = "device_click",
            stage = "coordinate_fallback",
            message = result.debugClickMessage(),
            details = "node=$nodeId\nmode=$resolutionMode\n" +
                "point=${point.x.toInt()},${point.y.toInt()}\n" +
                "label=${snapshot.debugLabel()}",
        )
        result
    }

    /**
     * 返回 null 表示节点没有可用的 ACTION_CLICK，需要使用该实时节点的中心位置降级。
     * 安全拒绝和成功结果直接返回，调用方不得绕过。
     */
    private fun clickNodeOrAncestor(
        initialNode: AccessibilityNodeInfo,
        authorization: GuiDeviceActionAuthorization,
    ): GuiDeviceActionResult? {
        var candidate: AccessibilityNodeInfo? = initialNode
        repeat(MAX_CLICK_PARENT_SEARCH) {
            if (candidate?.isPassword == true) {
                return GuiDeviceActionResult.Rejected("禁止操作密码输入控件")
            }
            candidate?.let { node ->
                validateClickRisk(node.text, node.contentDescription, authorization)
                    ?.let { return it }
            }
            if (candidate?.isClickable == true) {
                val clicked = candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                return if (clicked) {
                    GuiDeviceActionResult.Success("已点击页面控件")
                } else {
                    null
                }
            }
            candidate = candidate?.parent
        }
        return null
    }

    private fun nodeMatchesSnapshot(
        node: AccessibilityNodeInfo,
        snapshot: GuiNodeSnapshot,
    ): Boolean {
        if (!node.isVisibleToUser || node.isPassword != snapshot.password) return false
        if (nodeSemanticScore(node, snapshot) != null) return true
        val sameClass = node.className?.toString() == snapshot.className
        if (!sameClass) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        val centerX = (bounds.left + bounds.right) / 2.0
        val centerY = (bounds.top + bounds.bottom) / 2.0
        val snapshotCenterX = (snapshot.boundsInScreen.left + snapshot.boundsInScreen.right) / 2.0
        val snapshotCenterY = (snapshot.boundsInScreen.top + snapshot.boundsInScreen.bottom) / 2.0
        return abs(centerX - snapshotCenterX) <= MAX_EXACT_PATH_CENTER_DRIFT_PX &&
            abs(centerY - snapshotCenterY) <= MAX_EXACT_PATH_CENTER_DRIFT_PX
    }

    private fun findSemanticNode(
        targetPackage: String,
        snapshot: GuiNodeSnapshot,
    ): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != targetPackage) return null
        var visited = 0
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (
                depth > MAX_NODE_DEPTH ||
                visited >= MAX_SEMANTIC_SEARCH_NODES ||
                !node.isVisibleToUser
            ) {
                return
            }
            visited++
            nodeSemanticScore(node, snapshot)?.let { score ->
                if (score > bestScore) {
                    bestScore = score
                    bestNode = node
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                visit(child, depth + 1)
                if (visited >= MAX_SEMANTIC_SEARCH_NODES) break
            }
        }

        visit(root, 0)
        return bestNode
    }

    /** 只有 viewId、文字或描述至少一项精确匹配时才允许跨路径重匹配。 */
    private fun nodeSemanticScore(
        node: AccessibilityNodeInfo,
        snapshot: GuiNodeSnapshot,
    ): Int? {
        if (node.isPassword || !node.isVisibleToUser) return null
        val liveViewId = node.viewIdResourceName?.trim().orEmpty()
        val liveText = node.text?.toString().normalizedNodeIdentity()
        val liveDescription = node.contentDescription?.toString().normalizedNodeIdentity()
        val snapshotViewId = snapshot.viewId?.trim().orEmpty()
        val snapshotText = snapshot.text.normalizedNodeIdentity()
        val snapshotDescription = snapshot.contentDescription.normalizedNodeIdentity()
        var score = 0
        var hasStableMatch = false
        val viewIdMatches = snapshotViewId.isNotEmpty() && liveViewId == snapshotViewId
        val textMatches = snapshotText.isNotEmpty() && liveText == snapshotText
        val descriptionMatches = snapshotDescription.isNotEmpty() &&
            liveDescription == snapshotDescription
        if (
            snapshotText.isNotEmpty() &&
            liveText.isNotEmpty() &&
            !textMatches &&
            !descriptionMatches
        ) {
            return null
        }
        if (
            snapshotDescription.isNotEmpty() &&
            liveDescription.isNotEmpty() &&
            !descriptionMatches &&
            !textMatches
        ) {
            return null
        }
        if (viewIdMatches) {
            score += 180
            hasStableMatch = true
        }
        if (textMatches) {
            score += 140
            hasStableMatch = true
        }
        if (descriptionMatches) {
            score += 140
            hasStableMatch = true
        }
        if (!hasStableMatch) return null
        if (node.className?.toString() == snapshot.className) score += 20
        if (node.isClickable == snapshot.clickable) score += 10

        val bounds = Rect().also(node::getBoundsInScreen)
        val centerX = (bounds.left + bounds.right) / 2.0
        val centerY = (bounds.top + bounds.bottom) / 2.0
        val snapshotCenterX = (snapshot.boundsInScreen.left + snapshot.boundsInScreen.right) / 2.0
        val snapshotCenterY = (snapshot.boundsInScreen.top + snapshot.boundsInScreen.bottom) / 2.0
        val distancePenalty = (
            (abs(centerX - snapshotCenterX) + abs(centerY - snapshotCenterY)) /
                SEMANTIC_DISTANCE_PENALTY_PX
            ).toInt().coerceAtMost(MAX_SEMANTIC_DISTANCE_PENALTY)
        return score - distancePenalty
    }

    private fun String?.normalizedNodeIdentity(): String = this
        ?.trim()
        ?.lowercase()
        ?.replace(NODE_WHITESPACE_REGEX, " ")
        .orEmpty()

    private fun GuiNodeSnapshot.debugLabel(): String =
        listOfNotNull(text, contentDescription, viewId)
            .firstOrNull(String::isNotBlank)
            ?.take(MAX_NODE_TEXT_LENGTH)
            ?: "<无标签>"

    private fun GuiDeviceActionResult.debugClickMessage(): String = when (this) {
        is GuiDeviceActionResult.Success -> summary
        is GuiDeviceActionResult.Failed -> message
        is GuiDeviceActionResult.Rejected -> message
    }

    private fun validatePointRisk(
        point: PointD,
        observation: GuiScreenObservation,
        authorization: GuiDeviceActionAuthorization,
    ): GuiDeviceActionResult.Rejected? {
        val matchingNode = observation.nodes
            .filter { it.boundsInScreen.contains(point) }
            .minByOrNull { it.boundsInScreen.width * it.boundsInScreen.height }
            ?: return null
        return validateClickRisk(
            matchingNode.text,
            matchingNode.contentDescription,
            authorization,
        )
    }

    private fun validateClickRisk(
        text: CharSequence?,
        description: CharSequence?,
        authorization: GuiDeviceActionAuthorization,
    ): GuiDeviceActionResult.Rejected? {
        val label = listOfNotNull(text, description).joinToString(" ").lowercase()
        if (PAYMENT_ACTION_KEYWORDS.any(label::contains)) {
            return GuiDeviceActionResult.Rejected("支付操作必须由老人亲自完成")
        }
        if (
            ORDER_SUBMISSION_KEYWORDS.any(label::contains) &&
            !authorization.allowOrderSubmission
        ) {
            return GuiDeviceActionResult.Rejected("提交订单前必须先取得老人明确确认")
        }
        return null
    }

    private suspend fun performTextInput(
        targetPackage: String,
        nodeId: String,
        text: String,
    ): GuiDeviceActionResult = withContext(Dispatchers.Main.immediate) {
        val node = resolveNode(targetPackage, nodeId)
            ?: return@withContext GuiDeviceActionResult.Rejected("输入框已经失效")
        val hint = listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ")
            .lowercase()
        if (
            !node.isEditable ||
            node.isPassword ||
            SENSITIVE_INPUT_KEYWORDS.any(hint::contains)
        ) {
            return@withContext GuiDeviceActionResult.Rejected(
                "禁止向密码、验证码或安全验证输入框写入内容",
            )
        }
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text.take(MAX_INPUT_TEXT_LENGTH),
            )
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            GuiDeviceActionResult.Success("已输入文字")
        } else {
            GuiDeviceActionResult.Failed("页面输入框拒绝了文字输入")
        }
    }

    private suspend fun performFocusedTextInput(
        targetPackage: String,
        text: String,
    ): GuiDeviceActionResult = withContext(Dispatchers.Main.immediate) {
        val root = rootInActiveWindow
            ?.takeIf { it.packageName?.toString() == targetPackage }
            ?: return@withContext GuiDeviceActionResult.Rejected("目标应用已不在前台")
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable && it.isFocused }
            ?: findFocusedEditableNode(root)
            ?: return@withContext GuiDeviceActionResult.Rejected(
                "当前没有已聚焦的普通输入框，请先用坐标点击输入框",
            )
        val hint = listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ")
            .lowercase()
        if (
            node.isPassword ||
            SENSITIVE_INPUT_KEYWORDS.any(hint::contains)
        ) {
            return@withContext GuiDeviceActionResult.Rejected(
                "禁止向密码、验证码或安全验证输入框写入内容",
            )
        }
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text.take(MAX_INPUT_TEXT_LENGTH),
            )
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            GuiDeviceActionResult.Success("已向当前聚焦输入框输入文字")
        } else {
            GuiDeviceActionResult.Failed("当前输入框拒绝了文字输入")
        }
    }

    private fun findFocusedEditableNode(
        node: AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isVisibleToUser && node.isEditable && node.isFocused) return node
        for (index in 0 until node.childCount) {
            findFocusedEditableNode(node.getChild(index))?.let { return it }
        }
        return null
    }

    private suspend fun performScroll(
        targetPackage: String,
        nodeId: String?,
        direction: GuiScrollDirection,
    ): GuiDeviceActionResult {
        val nodeScrolled = withContext(Dispatchers.Main.immediate) {
            val requested = nodeId?.let { resolveNode(targetPackage, it) }
            val scrollable = requested?.takeIf(AccessibilityNodeInfo::isScrollable)
                ?: findScrollableNode(rootInActiveWindow)
            val action = if (direction == GuiScrollDirection.FORWARD) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            scrollable?.performAction(action) == true
        }
        if (nodeScrolled) return GuiDeviceActionResult.Success("已滚动页面")
        val screen = currentScreenSize()
        val performed = withOverlayHidden {
            dispatchSwipe(screen, direction)
        }
        return if (performed) {
            GuiDeviceActionResult.Success("已滑动页面")
        } else {
            GuiDeviceActionResult.Failed("系统没有执行滑动")
        }
    }

    private fun resolveNode(
        targetPackage: String,
        nodeId: String,
    ): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != targetPackage) return null
        val indexes = nodeId.split('.').mapNotNull(String::toIntOrNull)
        if (indexes.firstOrNull() != 0) return null
        var node = root
        for (index in indexes.drop(1)) {
            node = node.getChild(index) ?: return null
        }
        return node
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isVisibleToUser && node.isScrollable) return node
        for (index in 0 until node.childCount) {
            findScrollableNode(node.getChild(index))?.let { return it }
        }
        return null
    }

    private suspend fun dispatchTap(point: PointD): Boolean =
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(point.x.toFloat(), point.y.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MILLIS))
                .build()
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

    private suspend fun dispatchSwipe(
        screen: PixelSize,
        direction: GuiScrollDirection,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val x = screen.width * 0.5f
        val highY = screen.height * 0.72f
        val lowY = screen.height * 0.35f
        val path = Path().apply {
            if (direction == GuiScrollDirection.FORWARD) {
                moveTo(x, highY)
                lineTo(x, lowY)
            } else {
                moveTo(x, lowY)
                lineTo(x, highY)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    SWIPE_DURATION_MILLIS,
                ),
            )
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
            null,
        )
        if (!accepted && continuation.isActive) continuation.resume(false)
    }

    private suspend fun <T> withOverlayHidden(block: suspend () -> T): T {
        val hiddenView = withContext(Dispatchers.Main.immediate) {
            overlayView?.also { it.visibility = View.INVISIBLE }
        }
        if (hiddenView != null) delay(OVERLAY_COMPOSITOR_DELAY_MILLIS)
        return try {
            block()
        } finally {
            withContext(Dispatchers.Main.immediate) {
                if (overlayView === hiddenView) hiddenView?.visibility = View.VISIBLE
            }
        }
    }

    private fun isSensitiveScreen(nodes: List<GuiNodeSnapshot>): Boolean {
        if (nodes.any(GuiNodeSnapshot::password)) return true
        val visibleText = nodes.asSequence()
            .flatMap { sequenceOf(it.text, it.contentDescription) }
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
        if (SENSITIVE_SCREEN_KEYWORDS.any(visibleText::contains)) return true
        return PAYMENT_SCREEN_SIGNALS.count(visibleText::contains) >= 2
    }

    private fun invalidateFrame() {
        currentFrameId = null
        currentFrameTargetPackage = null
    }

    @Suppress("DEPRECATION")
    private fun currentScreenSize(): PixelSize {
        // Accessibility 截图和 dispatchGesture 都使用默认显示器的物理像素坐标。
        // resources.displayMetrics 可能扣除状态栏/导航栏，不能作为手势坐标空间。
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return PixelSize(metrics.widthPixels, metrics.heightPixels)
    }

    @Suppress("DEPRECATION")
    private fun currentRotationDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun Rect.toPixelRect(screenSize: PixelSize): PixelRect? {
        val clippedLeft = left.coerceIn(0, screenSize.width - 1)
        val clippedTop = top.coerceIn(0, screenSize.height - 1)
        val clippedRight = right.coerceIn(clippedLeft + 1, screenSize.width)
        val clippedBottom = bottom.coerceIn(clippedTop + 1, screenSize.height)
        if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
        return PixelRect(
            clippedLeft.toDouble(),
            clippedTop.toDouble(),
            clippedRight.toDouble(),
            clippedBottom.toDouble(),
        )
    }

    private fun String.sanitizeNodeText(): String =
        replace(Regex("\\s+"), " ").trim().take(MAX_NODE_TEXT_LENGTH)

    private fun showOrUpdateOverlay(
        task: GuiTaskSnapshot,
        voiceEnabled: Boolean,
    ) {
        removeOverlay()
        cancelFallbackArmed = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(0xFFF3F0FF.toInt(), dp(18).toFloat())
            contentDescription = "GUI 辅助任务控制条"
            elevation = dp(10).toFloat()
        }
        root.addView(TextView(this).apply {
            text = task.content
            textSize = 18f
            setTextColor(Color.BLACK)
            maxLines = 1
        })
        root.addView(TextView(this).apply {
            text = task.statusMessage ?: task.phase.displayName()
            textSize = 15f
            setTextColor(0xFF303030.toInt())
            maxLines = 2
        })
        if (BuildConfig.GUI_DEBUG_ENABLED) {
            root.addView(TextView(this).apply {
                text = GuiDebugTrace.events.value.lastOrNull().debugSummaryText()
                    ?: "GUI 调试：等待事件"
                textSize = 13f
                setTextColor(0xFF4A4458.toInt())
                maxLines = 2
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { showDebugTraceDialog() }
                contentDescription = "打开 GUI Agent 调试追踪"
                debugSummaryView = this
            })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(controlButton(primaryLabel(task)) {
                handlePrimaryAction(task)
            }, weightedButtonParams())
            addView(createVoiceButton(task, voiceEnabled), voiceButtonParams())
            addView(controlButton("取消") {
                requestCancelConfirmation()
            }.apply {
                setTextColor(0xFFB3261E.toInt())
            }, weightedButtonParams())
        })

        val parameters = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(24)
        }
        runCatching {
            windowManager.addView(root, parameters)
            overlayView = root
        }
    }

    private fun createVoiceButton(
        task: GuiTaskSnapshot,
        voiceEnabled: Boolean,
    ): Button = Button(this).apply {
        text = if (voiceEnabled) "按住\n说话" else "语音\n已关"
        textSize = 13f
        isAllCaps = false
        isEnabled = voiceEnabled
        alpha = if (voiceEnabled) 1f else 0.55f
        contentDescription = if (voiceEnabled) "按住说话" else "全局语音开关已关闭"
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF6750A4.toInt())
        }
        setTextColor(Color.WHITE)
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startGuiVoiceInput(task, this)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    finishGuiVoiceInput(task, this)
                    performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    finishGuiVoiceInput(task, this)
                    true
                }

                else -> true
            }
        }
    }

    private fun startGuiVoiceInput(task: GuiTaskSnapshot, button: Button) {
        val registration = GuiTaskRuntimeBridge.registration.value ?: return
        if (!registration.voiceCoordinator.enabled.value || voicePressActive) return
        voicePressActive = true
        voicePhaseBeforeRecording = task.phase
        button.text = "松开\n识别"
        voiceCompletionJob?.cancel()
        voiceStartJob = serviceScope.launch {
            runCatching {
                registration.controller.beginVoiceInput()
                registration.voiceCoordinator.startGuiAgentRecording(task.todoId)
            }.onFailure { error ->
                voicePressActive = false
                button.text = "按住\n说话"
                resumeAfterEmptyVoiceInput(task.phase)
                Toast.makeText(
                    this@GuiAccessibilityControlService,
                    error.message ?: "无法开始语音识别",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun finishGuiVoiceInput(task: GuiTaskSnapshot, button: Button) {
        if (!voicePressActive) return
        voicePressActive = false
        button.text = "正在\n识别"
        val registration = GuiTaskRuntimeBridge.registration.value ?: return
        val pendingStart = voiceStartJob
        voiceCompletionJob?.cancel()
        voiceCompletionJob = serviceScope.launch {
            pendingStart?.join()
            if (registration.voiceCoordinator.listeningState.value == VoiceListeningState.IDLE) {
                button.text = "按住\n说话"
                resumeAfterEmptyVoiceInput(voicePhaseBeforeRecording ?: task.phase)
                voicePhaseBeforeRecording = null
                return@launch
            }
            runCatching { registration.voiceCoordinator.stopGuiAgentRecording() }
                .onSuccess { result ->
                    val transcript = result.transcript.trim()
                    if (transcript.isBlank()) {
                        resumeAfterEmptyVoiceInput(voicePhaseBeforeRecording ?: task.phase)
                        Toast.makeText(
                            this@GuiAccessibilityControlService,
                            "没有听清，请按住再说一次",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        registration.controller.submitVoiceInput(transcript)
                    }
                }
                .onFailure { error ->
                    resumeAfterEmptyVoiceInput(voicePhaseBeforeRecording ?: task.phase)
                    if (error !is kotlinx.coroutines.CancellationException) {
                        Toast.makeText(
                            this@GuiAccessibilityControlService,
                            error.message ?: "语音识别失败，请再试一次",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            button.text = "按住\n说话"
            voicePhaseBeforeRecording = null
        }
    }

    private suspend fun resumeAfterEmptyVoiceInput(previousPhase: GuiRunPhase) {
        if (previousPhase !in USER_GATE_PHASES) {
            GuiTaskRuntimeBridge.registration.value?.controller?.resume()
        }
    }

    private fun handlePrimaryAction(task: GuiTaskSnapshot) {
        val registration = GuiTaskRuntimeBridge.registration.value ?: return
        serviceScope.launch {
            if (task.phase in USER_GATE_PHASES) {
                registration.controller.resume()
            } else if (task.phase == GuiRunPhase.PAUSED) {
                val canResume = if (task.pauseReason == GuiPauseReason.TARGET_APP_LEFT) {
                    registration.targetAppLauncher.returnToTask(task.todoId)
                } else {
                    true
                }
                if (canResume) registration.controller.resume()
            } else {
                registration.controller.pause(GuiPauseReason.ELDER_REQUEST)
            }
        }
    }

    private fun requestCancelConfirmation() {
        if (cancelFallbackArmed) {
            cancelCurrentTask()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("确认取消任务？")
            .setMessage("取消后，本次手机辅助任务将立即停止。")
            .setPositiveButton("确认取消") { _, _ -> cancelCurrentTask() }
            .setNegativeButton("不取消", null)
            .create()
        runCatching {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.show()
        }.onFailure {
            cancelFallbackArmed = true
            Toast.makeText(
                this,
                "请再次点击“取消”确认停止任务",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showDebugTraceDialog() {
        if (!BuildConfig.GUI_DEBUG_ENABLED) return
        val content = TextView(this).apply {
            text = GuiDebugTrace.events.value
                .joinToString("\n\n") { it.renderForDebug() }
                .ifBlank { "还没有追踪事件。" }
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("GUI Agent 内存追踪")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ -> GuiDebugTrace.clear() }
            .create()
        runCatching {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.show()
        }
    }

    private fun cancelCurrentTask() {
        val registration = GuiTaskRuntimeBridge.registration.value ?: return
        serviceScope.launch { registration.controller.cancel() }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
        debugSummaryView = null
        renderedTask = null
        cancelFallbackArmed = false
    }

    private fun controlButton(
        label: String,
        onClick: () -> Unit,
    ) = Button(this).apply {
        text = label
        textSize = 17f
        minHeight = dp(52)
        setOnClickListener { onClick() }
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(
        0,
        dp(52),
        1f,
    )

    private fun voiceButtonParams() = LinearLayout.LayoutParams(
        dp(64),
        dp(64),
    ).apply {
        marginStart = dp(8)
        marginEnd = dp(8)
    }

    private fun primaryLabel(task: GuiTaskSnapshot): String = when {
        task.phase == GuiRunPhase.WAITING_ELDER_CONFIRMATION -> "确认并继续"
        task.phase == GuiRunPhase.WAITING_MANUAL_PAYMENT -> "付款完成后继续"
        task.phase == GuiRunPhase.WAITING_USER_INPUT -> "继续"
        task.phase != GuiRunPhase.PAUSED -> "暂停"
        task.pauseReason == GuiPauseReason.TARGET_APP_LEFT -> "返回任务"
        else -> "继续"
    }

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

    private fun GuiRunPhase.isTerminal(): Boolean = this in setOf(
        GuiRunPhase.COMPLETED,
        GuiRunPhase.FAILED,
        GuiRunPhase.CANCELLED,
        GuiRunPhase.UNAVAILABLE,
    )

    private fun roundedBackground(color: Int, radius: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1), 0xFF6750A4.toInt())
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun GuiDebugEvent?.debugSummaryText(): String = this?.let {
        "调试 ${it.source}/${it.stage}：${it.message}（点此查看）"
    } ?: "GUI 调试：等待事件"

    private fun GuiDebugEvent.renderForDebug(): String = buildString {
        append(DEBUG_TIME_FORMAT.format(Date(timestampEpochMillis)))
        append("  [")
        append(source)
        append('/')
        append(stage)
        append("]\n")
        append(message)
        details?.takeIf(String::isNotBlank)?.let {
            append('\n')
            append(it)
        }
    }

    private data class TargetWindowState(
        val title: String?,
        val bounds: PixelRect,
        val nodes: List<GuiNodeSnapshot>,
    )

    private companion object {
        const val DEFAULT_DISPLAY_ID = 0
        const val MAX_NODE_COUNT = 120
        const val MAX_NODE_DEPTH = 14
        const val MAX_NODE_TEXT_LENGTH = 120
        const val MAX_CLASS_NAME_LENGTH = 120
        const val MAX_VIEW_ID_LENGTH = 160
        const val MAX_INPUT_TEXT_LENGTH = 200
        const val MAX_CLICK_PARENT_SEARCH = 5
        const val MAX_SEMANTIC_SEARCH_NODES = 500
        const val MAX_EXACT_PATH_CENTER_DRIFT_PX = 48.0
        const val SEMANTIC_DISTANCE_PENALTY_PX = 48.0
        const val MAX_SEMANTIC_DISTANCE_PENALTY = 120
        const val JPEG_QUALITY = 82
        const val TAP_DURATION_MILLIS = 80L
        const val SWIPE_DURATION_MILLIS = 350L
        const val OVERLAY_COMPOSITOR_DELAY_MILLIS = 100L
        const val AGENT_EVENT_SUPPRESSION_MILLIS = 1_200L
        const val AGENT_POST_ACTION_GRACE_MILLIS = 5_000L
        const val APP_START_EVENT_GRACE_MILLIS = 5_000L
        const val TARGET_LEFT_DEBOUNCE_MILLIS = 1_200L
        val NODE_WHITESPACE_REGEX = Regex("\\s+")
        val USER_GATE_PHASES = setOf(
            GuiRunPhase.WAITING_USER_INPUT,
            GuiRunPhase.WAITING_ELDER_CONFIRMATION,
            GuiRunPhase.WAITING_MANUAL_PAYMENT,
        )
        val FRAME_INVALIDATING_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
        )
        val SENSITIVE_INPUT_KEYWORDS = listOf(
            "密码",
            "验证码",
            "校验码",
            "短信码",
            "动态码",
            "生物识别",
        )
        val SENSITIVE_SCREEN_KEYWORDS = listOf(
            "支付密码",
            "付款密码",
            "短信验证码",
            "请输入验证码",
            "生物识别",
            "指纹验证",
            "人脸验证",
        )
        val PAYMENT_SCREEN_SIGNALS = listOf(
            "收银台",
            "确认支付",
            "立即支付",
            "支付方式",
            "应付",
            "付款",
        )
        val PAYMENT_ACTION_KEYWORDS = listOf(
            "确认支付",
            "立即支付",
            "去支付",
            "确认付款",
            "立即付款",
        )
        val ORDER_SUBMISSION_KEYWORDS = listOf(
            "提交订单",
            "确认下单",
            "立即下单",
        )
        val DEBUG_TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    }
}
