package com.example.silverageassistant.platform.gui

import com.example.silverageassistant.domain.agent.AgentToolRegistry
import com.example.silverageassistant.domain.gui.GuiDeviceAction
import com.example.silverageassistant.domain.gui.GuiDeviceActionResult
import com.example.silverageassistant.domain.gui.GuiDeviceActionAuthorization
import com.example.silverageassistant.domain.gui.GuiConfirmationScope
import com.example.silverageassistant.domain.gui.GuiDeviceController
import com.example.silverageassistant.domain.gui.GuiDeviceControllerProvider
import com.example.silverageassistant.domain.gui.GuiDebugTrace
import com.example.silverageassistant.domain.gui.GuiObserveResult
import com.example.silverageassistant.domain.gui.GuiPlannedAction
import com.example.silverageassistant.domain.gui.GuiPlanningRequest
import com.example.silverageassistant.domain.gui.GuiRunControl
import com.example.silverageassistant.domain.gui.GuiRunExecutor
import com.example.silverageassistant.domain.gui.GuiRunOutcome
import com.example.silverageassistant.domain.gui.GuiRunPhase
import com.example.silverageassistant.domain.gui.GuiRunRequest
import com.example.silverageassistant.domain.gui.GuiStepRecord
import com.example.silverageassistant.domain.gui.GuiTargetApp
import com.example.silverageassistant.domain.gui.GuiTargetAppLauncher
import com.example.silverageassistant.domain.gui.GuiTargetLaunchResult
import com.example.silverageassistant.domain.gui.GuiVisionPlanner
import com.example.silverageassistant.domain.gui.vision.GuiScreenObservation
import com.example.silverageassistant.domain.gui.vision.ScreenshotPixelBudget
import com.example.silverageassistant.domain.voice.VoiceInteractionCoordinator
import kotlinx.coroutines.delay

/**
 * GUI Worker 的轻量级 ReAct 循环。一次只允许一个真实动作，然后强制重新截图。正常页面推进
 * 不设固定总步数上限；只有同一页面上的同一步骤已经尝试五次、再次重复仍无进展，才把本次
 * GuiRun 交给任务管理器计为一次失败。
 */
class AccessibilityGuiRunExecutor(
    private val launcher: GuiTargetAppLauncher,
    private val controllerProvider: GuiDeviceControllerProvider,
    private val planner: GuiVisionPlanner,
    private val voiceCoordinator: VoiceInteractionCoordinator? = null,
    private val pixelBudget: ScreenshotPixelBudget = ScreenshotPixelBudget(
        maxLongEdgePx = 1_280,
        maxPixelCount = 1_200_000,
    ),
) : GuiRunExecutor {
    override suspend fun execute(
        request: GuiRunRequest,
        control: GuiRunControl,
        sharedTools: AgentToolRegistry,
    ): GuiRunOutcome {
        control.awaitRunning()
        val target = launcher.resolve(request.content)
        if (target == null) {
            GuiDebugTrace.record(
                "executor",
                "resolve_target_failed",
                "无法从任务中解析目标 App",
                request.content,
            )
            return GuiRunOutcome.Unavailable("当前 GUI Agent 还不能识别要操作的应用。")
        }
        val openOnly = request.content.isOpenOnlyRequest(target)
        GuiDebugTrace.record(
            "executor",
            "target_resolved",
            "目标=${target.displayName}，纯打开=$openOnly",
            "package=${target.packageName}\ntask=${request.content}",
        )
        val isRetryRun = request.attempt > 1
        if (isRetryRun) {
            GuiDebugTrace.record(
                "executor",
                "retry_app_reset_started",
                "第 ${request.attempt} 次完整运行前重置${target.displayName}任务栈",
                target.packageName,
            )
        }
        val launchResult = if (isRetryRun) {
            launcher.restart(request.todoId, target)
        } else {
            launcher.launch(request.todoId, target)
        }
        if (launchResult is GuiTargetLaunchResult.Unavailable) {
            GuiDebugTrace.record(
                "executor",
                "launch_failed",
                launchResult.message,
            )
            return GuiRunOutcome.Unavailable(launchResult.message)
        }
        GuiDebugTrace.record(
            "executor",
            if (isRetryRun) "retry_app_relaunched" else "launch_intent_sent",
            if (isRetryRun) {
                "已清理旧页面并重新请求启动${target.displayName}，尚未验证前台"
            } else {
                "已向系统请求启动${target.displayName}，尚未验证前台"
            },
            target.packageName,
        )
        return try {
            val controller = controllerProvider.awaitController()
            if (controller == null) {
                GuiDebugTrace.record(
                    "executor",
                    "accessibility_unavailable",
                    "启动请求已发送，但无障碍控制器没有连接",
                )
                return GuiRunOutcome.Unavailable(
                    "已请求打开${target.displayName}，但无障碍服务未连接，无法确认结果。",
                )
            }
            control.reportPhase(GuiRunPhase.RUNNING, "正在打开${target.displayName}")
            val firstObservation = awaitTargetObservation(controller, target, control)
            GuiDebugTrace.record(
                "executor",
                "launch_observation",
                firstObservation.debugSummary(),
            )
            when (firstObservation) {
                is GuiObserveResult.TargetNotForeground -> GuiRunOutcome.Unavailable(
                    "没有成功打开${target.displayName}，请确认应用可以正常启动。",
                )

                is GuiObserveResult.Unavailable ->
                    GuiRunOutcome.Unavailable(firstObservation.message)

                is GuiObserveResult.Captured,
                is GuiObserveResult.SensitiveScreen,
                -> if (openOnly) {
                    GuiDebugTrace.record(
                        "executor",
                        "task_completed",
                        "纯打开任务已由前台观察验证",
                    )
                    GuiRunOutcome.Completed
                } else {
                    launcher.markForegroundVerified(request.todoId)
                    runReAct(
                        request = request,
                        target = target,
                        controller = controller,
                        control = control,
                        sharedTools = sharedTools,
                        firstObservation = firstObservation,
                    )
                }
            }
        } finally {
            launcher.clear(request.todoId)
        }
    }

    private suspend fun runReAct(
        request: GuiRunRequest,
        target: GuiTargetApp,
        controller: GuiDeviceController,
        control: GuiRunControl,
        sharedTools: AgentToolRegistry,
        firstObservation: GuiObserveResult,
    ): GuiRunOutcome {
        val history = ArrayDeque<GuiStepRecord>()
        val repeatedStepGuard = RepeatedStepGuard()
        var orderSubmissionApproved = false
        var successfulDeviceActions = 0
        var awaitingPostActionObservation = false
        var hasVerifiedAfterAction = false
        var pendingObservation: GuiObserveResult? = firstObservation
        var step = 1
        while (true) {
            control.awaitRunning()
            control.reportPhase(
                GuiRunPhase.RUNNING,
                "正在查看${target.displayName}页面（第 $step 步）",
            )
            val captured = pendingObservation ?: awaitTargetObservation(
                controller = controller,
                target = target,
                control = control,
            )
            pendingObservation = null
            if (
                captured is GuiObserveResult.Captured ||
                captured is GuiObserveResult.SensitiveScreen
            ) {
                launcher.markForegroundVerified(request.todoId)
            }
            GuiDebugTrace.record(
                "react",
                "observation_$step",
                captured.debugSummary(),
            )
            when (captured) {
                is GuiObserveResult.Unavailable ->
                    return GuiRunOutcome.Unavailable(captured.message)

                is GuiObserveResult.TargetNotForeground ->
                    return GuiRunOutcome.Failed("目标应用没有保持在前台")

                is GuiObserveResult.SensitiveScreen -> {
                    voiceCoordinator?.speakGuiAgentConfirmation(
                        request.todoId,
                        captured.message,
                    )
                    control.awaitUserGate(
                        GuiRunPhase.WAITING_MANUAL_PAYMENT,
                        captured.message,
                    )
                    addHistory(
                        history,
                        GuiStepRecord("安全页面", "已交给老人亲自操作"),
                    )
                    repeatedStepGuard.resetForNewUserInput()
                    step++
                    continue
                }

                is GuiObserveResult.Captured -> {
                    if (awaitingPostActionObservation) {
                        awaitingPostActionObservation = false
                        hasVerifiedAfterAction = true
                    }
                    control.consumeVoiceInput()?.let { transcript ->
                        repeatedStepGuard.resetForNewUserInput()
                        addHistory(
                            history,
                            GuiStepRecord("老人语音补充", transcript),
                        )
                    }
                    control.awaitRunning()
                    val planned = runCatching {
                        planner.plan(
                            GuiPlanningRequest(
                                task = request.content,
                                attempt = request.attempt,
                                step = step,
                                observation = captured.observation,
                                history = history.toList(),
                                sharedTools = sharedTools,
                            ),
                        )
                    }.getOrElse { error ->
                        val repeatedAttempts = repeatedStepGuard.register(
                            pageFingerprint = captured.observation.repeatPageFingerprint(),
                            stepFingerprint = "planner_error",
                        )
                        addHistory(
                            history,
                            GuiStepRecord(
                                "分析页面",
                                error.message?.take(120) ?: "模型分析失败",
                            ),
                        )
                        if (repeatedAttempts > MAX_REPEATED_STEP_ATTEMPTS) {
                            return repeatedStepFailure()
                        }
                        delay(RETRY_DELAY_MILLIS)
                        step++
                        continue
                    }
                    GuiDebugTrace.record(
                        "react",
                        "planned_action_$step",
                        planned::class.simpleName ?: "unknown",
                        planned.toString(),
                    )
                    control.awaitRunning()
                    val repeatedAttempts = repeatedStepGuard.register(
                        pageFingerprint = captured.observation.repeatPageFingerprint(),
                        stepFingerprint = planned.repeatStepFingerprint(
                            captured.observation,
                        ),
                    )
                    if (repeatedAttempts > 1) {
                        GuiDebugTrace.record(
                            "react",
                            "repeated_step_attempt",
                            "同一页面步骤第 $repeatedAttempts 次尝试",
                            "step=$step\naction=${planned::class.simpleName}",
                        )
                    }
                    if (repeatedAttempts > MAX_REPEATED_STEP_ATTEMPTS) {
                        GuiDebugTrace.record(
                            "react",
                            "repeated_step_limit",
                            "同一页面步骤已尝试 $MAX_REPEATED_STEP_ATTEMPTS 次仍无进展",
                            "step=$step\naction=${planned::class.simpleName}",
                        )
                        return repeatedStepFailure()
                    }
                    when (planned) {
                        is GuiPlannedAction.Complete -> {
                            if (successfulDeviceActions == 0 || !hasVerifiedAfterAction) {
                                GuiDebugTrace.record(
                                    "react",
                                    "complete_rejected",
                                    "没有真实动作或动作后新截图，拒绝 complete",
                                    planned.summary,
                                )
                                addHistory(
                                    history,
                                    GuiStepRecord(
                                        "模型尝试结束任务",
                                        "拒绝：必须先执行真实操作，再用新截图验证结果",
                                    ),
                                )
                                step++
                                continue
                            }
                            GuiDebugTrace.record(
                                "react",
                                "complete_accepted",
                                "任务完成判断已通过",
                                planned.summary,
                            )
                            return GuiRunOutcome.Completed
                        }

                        is GuiPlannedAction.Fail -> {
                            addHistory(
                                history,
                                GuiStepRecord(
                                    "模型认为当前步骤无法继续",
                                    "尚未达到重复失败阈值，将重新观察并尝试其他方案",
                                ),
                            )
                            delay(RETRY_DELAY_MILLIS)
                        }

                        is GuiPlannedAction.Wait -> {
                            addHistory(
                                history,
                                GuiStepRecord("等待", planned.reason),
                            )
                            delay(planned.milliseconds)
                        }

                        is GuiPlannedAction.AskElder -> {
                            if (
                                request.content.isNavigationOnlyTask() ||
                                successfulDeviceActions == 0 &&
                                !captured.observation.hasVisibleUserBlocker()
                            ) {
                                addHistory(
                                    history,
                                    GuiStepRecord(
                                        "模型尝试询问老人",
                                        "拒绝：当前页面没有需要老人决定的阻塞项，请继续按原目标操作",
                                    ),
                                )
                                step++
                                continue
                            }
                            voiceCoordinator?.speakGuiAgentConfirmation(
                                request.todoId,
                                planned.message,
                            )
                            control.awaitUserGate(
                                GuiRunPhase.WAITING_ELDER_CONFIRMATION,
                                planned.message,
                            )
                            addHistory(
                                history,
                                GuiStepRecord("询问老人", "老人已点击确认并继续"),
                            )
                            orderSubmissionApproved =
                                planned.confirmationScope ==
                                GuiConfirmationScope.ORDER_SUBMISSION
                        }

                        is GuiPlannedAction.ReadyForPayment -> {
                            if (successfulDeviceActions == 0) {
                                addHistory(
                                    history,
                                    GuiStepRecord(
                                        "模型尝试进入付款等待",
                                        "拒绝：尚未执行页面操作，当前没有付款完成证据",
                                    ),
                                )
                                step++
                                continue
                            }
                            voiceCoordinator?.speakGuiAgentConfirmation(
                                request.todoId,
                                planned.message,
                            )
                            control.awaitUserGate(
                                GuiRunPhase.WAITING_MANUAL_PAYMENT,
                                planned.message,
                            )
                            addHistory(
                                history,
                                GuiStepRecord("等待亲自付款", "老人已完成操作并继续"),
                            )
                        }

                        is GuiPlannedAction.UseTool -> {
                            val tool = sharedTools.find(planned.toolName)
                            val result = if (tool == null) {
                                "没有这个共享工具"
                            } else {
                                runCatching {
                                    tool.execute(planned.argumentsJson)
                                }.getOrElse { "工具执行失败" }
                            }
                            addHistory(
                                history,
                                GuiStepRecord(
                                    "调用 ${planned.toolName}：${planned.reason}",
                                    result.take(MAX_HISTORY_RESULT_LENGTH),
                                ),
                            )
                            GuiDebugTrace.record(
                                "react",
                                "shared_tool_result",
                                "共享工具 ${planned.toolName} 已返回",
                                result,
                            )
                        }

                        is GuiPlannedAction.Device -> {
                            voiceCoordinator?.speakGuiAgentStep(
                                request.todoId,
                                planned.summary,
                            )
                            control.awaitRunning()
                            val result = controller.perform(
                                targetPackage = target.packageName,
                                observation = captured.observation,
                                action = planned.action,
                                authorization = GuiDeviceActionAuthorization(
                                    allowOrderSubmission = orderSubmissionApproved,
                                ),
                            )
                            GuiDebugTrace.record(
                                "react",
                                "device_action_result",
                                result::class.simpleName ?: "unknown",
                                "action=${planned.action}\nresult=$result",
                            )
                            orderSubmissionApproved = false
                            when (result) {
                                is GuiDeviceActionResult.Success -> {
                                    successfulDeviceActions++
                                    awaitingPostActionObservation = true
                                    hasVerifiedAfterAction = false
                                    addHistory(
                                        history,
                                        GuiStepRecord(planned.summary, result.summary),
                                    )
                                    delay(AFTER_ACTION_DELAY_MILLIS)
                                }

                                is GuiDeviceActionResult.Rejected -> {
                                    addHistory(
                                        history,
                                        GuiStepRecord(planned.summary, result.message),
                                    )
                                }

                                is GuiDeviceActionResult.Failed -> {
                                    addHistory(
                                        history,
                                        GuiStepRecord(planned.summary, result.message),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            step++
        }
    }

    private fun repeatedStepFailure(): GuiRunOutcome.Failed =
        GuiRunOutcome.Failed(
            "同一页面步骤已尝试 $MAX_REPEATED_STEP_ATTEMPTS 次仍未解决",
        )

    private suspend fun awaitTargetObservation(
        controller: GuiDeviceController,
        target: GuiTargetApp,
        control: GuiRunControl,
    ): GuiObserveResult {
        var latest: GuiObserveResult = GuiObserveResult.TargetNotForeground(null)
        repeat(TARGET_FOREGROUND_POLLS) {
            control.awaitRunning()
            latest = controller.observe(target.packageName, pixelBudget)
            if (latest !is GuiObserveResult.TargetNotForeground) return latest
            delay(TARGET_FOREGROUND_POLL_MILLIS)
        }
        return latest
    }

    private fun addHistory(
        history: ArrayDeque<GuiStepRecord>,
        record: GuiStepRecord,
    ) {
        history.addLast(record)
        while (history.size > MAX_LOCAL_HISTORY_STEPS) history.removeFirst()
    }

    private fun String.isOpenOnlyRequest(target: GuiTargetApp): Boolean {
        val normalized = lowercase()
            .replace(Regex("[\\s，。！？,.!?]"), "")
            .replace("app", "")
            .replace("应用", "")
        return normalized in setOf(
            "打开${target.displayName}",
            "启动${target.displayName}",
            "进入${target.displayName}",
            "帮我打开${target.displayName}",
            "请打开${target.displayName}",
            "打开一个${target.displayName}",
            "帮我启动${target.displayName}",
            "请帮我打开${target.displayName}",
        )
    }

    private fun String.isNavigationOnlyTask(): Boolean {
        val normalized = lowercase()
        val asksForNavigation = listOf("点", "点击", "进入").any(normalized::contains)
        val asksForDownstreamWork = listOf(
            "买",
            "购买",
            "下单",
            "选择",
            "搜索",
            "查找",
            "输入",
            "发送",
            "订",
        ).any(normalized::contains)
        return asksForNavigation && !asksForDownstreamWork
    }

    private fun GuiScreenObservation.hasVisibleUserBlocker(): Boolean {
        val visibleText = nodes.asSequence()
            .flatMap { sequenceOf(it.text, it.contentDescription) }
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
        return VISIBLE_USER_BLOCKERS.any(visibleText::contains)
    }

    private fun GuiScreenObservation.repeatPageFingerprint(): String = buildString {
        append(targetPackage)
        append('|')
        append(windowTitle.orEmpty().normalizeForRepeatFingerprint())
        nodes.asSequence()
            .filter {
                it.clickable ||
                    it.editable ||
                    it.scrollable ||
                    !it.text.isNullOrBlank() ||
                    !it.contentDescription.isNullOrBlank() ||
                    !it.viewId.isNullOrBlank()
            }
            .map { node ->
                buildString {
                    append(node.text.orEmpty().normalizeForRepeatFingerprint())
                    append('~')
                    append(node.contentDescription.orEmpty().normalizeForRepeatFingerprint())
                    append('~')
                    append(node.viewId.orEmpty())
                    append('~')
                    append(node.className.orEmpty())
                    append('~')
                    append((node.boundsInScreen.left / REPEAT_BOUNDS_BUCKET_PX).toInt())
                    append(',')
                    append((node.boundsInScreen.top / REPEAT_BOUNDS_BUCKET_PX).toInt())
                    append('~')
                    append(if (node.clickable) 'c' else '-')
                    append(if (node.editable) 'e' else '-')
                    append(if (node.scrollable) 's' else '-')
                }
            }
            .sorted()
            .take(MAX_REPEAT_FINGERPRINT_NODES)
            .forEach { nodeFingerprint ->
                append('|')
                append(nodeFingerprint)
            }
    }.hashCode().toString()

    private fun GuiPlannedAction.repeatStepFingerprint(
        observation: GuiScreenObservation,
    ): String = when (this) {
        is GuiPlannedAction.Complete ->
            "complete:${summary.normalizeForRepeatFingerprint()}"
        is GuiPlannedAction.Fail ->
            "fail:${message.normalizeForRepeatFingerprint()}"
        is GuiPlannedAction.Wait ->
            "wait:${reason.normalizeForRepeatFingerprint()}"
        is GuiPlannedAction.AskElder ->
            "ask_elder:$confirmationScope:${message.normalizeForRepeatFingerprint()}"
        is GuiPlannedAction.ReadyForPayment ->
            "ready_for_payment:${message.normalizeForRepeatFingerprint()}"
        is GuiPlannedAction.UseTool -> "use_tool:$toolName:${argumentsJson.hashCode()}"
        is GuiPlannedAction.Device -> when (val deviceAction = action) {
            is GuiDeviceAction.ClickNode -> {
                val target = observation.nodes.firstOrNull {
                    it.nodeId == deviceAction.nodeId
                }
                "click_node:" + listOfNotNull(
                    target?.text,
                    target?.contentDescription,
                    target?.viewId,
                    summary,
                ).joinToString("|") { it.normalizeForRepeatFingerprint() }
            }
            is GuiDeviceAction.ClickPoint -> {
                val point = deviceAction.point
                "click_point:${point.coordinateSpace}:" +
                    "${(point.x / REPEAT_COORDINATE_BUCKET).toInt()}:" +
                    "${(point.y / REPEAT_COORDINATE_BUCKET).toInt()}:" +
                    summary.normalizeForRepeatFingerprint()
            }
            is GuiDeviceAction.InputText ->
                "input_text:${deviceAction.text.hashCode()}:" +
                    summary.normalizeForRepeatFingerprint()
            is GuiDeviceAction.InputTextFocused ->
                "input_text_focused:${deviceAction.text.hashCode()}"
            is GuiDeviceAction.Scroll ->
                "scroll:${deviceAction.direction}:" + summary.normalizeForRepeatFingerprint()
            is GuiDeviceAction.Back -> "back"
        }
    }

    private fun String.normalizeForRepeatFingerprint(): String =
        lowercase().replace(Regex("\\s+"), " ").trim().take(MAX_REPEAT_TEXT_LENGTH)

    private fun GuiObserveResult.debugSummary(): String = when (this) {
        is GuiObserveResult.Captured ->
            "已捕获 frame=${observation.geometry.frameId.take(8)}，" +
                "image=${observation.geometry.modelImage.uploadSize.width}x" +
                "${observation.geometry.modelImage.uploadSize.height}，" +
                "bytes=${observation.uploadBytes.size}，nodes=${observation.nodes.size}"

        is GuiObserveResult.TargetNotForeground ->
            "目标 App 不在前台，foreground=${foregroundPackage ?: "unknown"}"

        is GuiObserveResult.SensitiveScreen -> "检测到敏感页面：$message"
        is GuiObserveResult.Unavailable -> "观察不可用：$message"
    }

    private companion object {
        const val MAX_REPEATED_STEP_ATTEMPTS = 5
        const val MAX_LOCAL_HISTORY_STEPS = 8
        const val MAX_HISTORY_RESULT_LENGTH = 300
        const val MAX_REPEAT_FINGERPRINT_NODES = 120
        const val MAX_REPEAT_TEXT_LENGTH = 120
        const val MAX_TRACKED_PAGE_STEPS = 64
        const val REPEAT_BOUNDS_BUCKET_PX = 24.0
        const val REPEAT_COORDINATE_BUCKET = 25.0
        const val TARGET_FOREGROUND_POLLS = 12
        const val TARGET_FOREGROUND_POLL_MILLIS = 500L
        const val AFTER_ACTION_DELAY_MILLIS = 700L
        const val RETRY_DELAY_MILLIS = 500L
        val VISIBLE_USER_BLOCKERS = listOf(
            "登录",
            "授权",
            "验证码",
            "隐私协议",
            "同意并继续",
        )
    }

    private class RepeatedStepGuard {
        private val attemptsByPageAndStep = linkedMapOf<String, Int>()

        fun register(pageFingerprint: String, stepFingerprint: String): Int {
            val key = "$pageFingerprint|$stepFingerprint"
            if (
                key !in attemptsByPageAndStep &&
                attemptsByPageAndStep.size >= MAX_TRACKED_PAGE_STEPS
            ) {
                val oldestKey = attemptsByPageAndStep.keys.firstOrNull()
                if (oldestKey != null) attemptsByPageAndStep.remove(oldestKey)
            }
            val attempts = attemptsByPageAndStep.getOrDefault(key, 0) + 1
            attemptsByPageAndStep[key] = attempts
            return attempts
        }

        fun resetForNewUserInput() {
            attemptsByPageAndStep.clear()
        }
    }
}
