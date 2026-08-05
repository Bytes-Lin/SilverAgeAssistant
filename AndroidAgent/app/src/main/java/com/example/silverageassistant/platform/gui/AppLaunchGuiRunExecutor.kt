package com.example.silverageassistant.platform.gui

import com.example.silverageassistant.domain.agent.AgentToolRegistry
import com.example.silverageassistant.domain.gui.GuiRunControl
import com.example.silverageassistant.domain.gui.GuiRunExecutor
import com.example.silverageassistant.domain.gui.GuiRunOutcome
import com.example.silverageassistant.domain.gui.GuiRunPhase
import com.example.silverageassistant.domain.gui.GuiRunRequest
import com.example.silverageassistant.domain.gui.GuiTargetApp
import com.example.silverageassistant.domain.gui.GuiTargetAppLauncher
import com.example.silverageassistant.domain.gui.GuiTargetLaunchResult
import kotlinx.coroutines.awaitCancellation

/**
 * GUI Agent 的第一段真机执行能力。
 *
 * 当前只负责通过包名打开已支持的目标 App。仅“打开 App”的任务会立即完成；需要继续点击、
 * 搜索或下单的任务会保持为等待态，让老人可以验证暂停、继续、返回任务和取消控制。后续
 * Accessibility + MLLM ReAct 执行器接入时替换等待段，不改变主 Agent Tool 协议。
 */
class AppLaunchGuiRunExecutor(
    private val launcher: GuiTargetAppLauncher,
) : GuiRunExecutor {
    override suspend fun execute(
        request: GuiRunRequest,
        control: GuiRunControl,
        sharedTools: AgentToolRegistry,
    ): GuiRunOutcome {
        control.awaitRunning()
        val target = launcher.resolve(request.content)
            ?: return GuiRunOutcome.Unavailable(
                "当前 GUI Agent 还不能识别要打开的应用。",
            )
        return when (val result = launcher.launch(request.todoId, target)) {
            is GuiTargetLaunchResult.Unavailable ->
                GuiRunOutcome.Unavailable(result.message)

            is GuiTargetLaunchResult.Launched -> {
                if (request.content.isOpenOnlyRequest(target)) {
                    launcher.clear(request.todoId)
                    GuiRunOutcome.Completed
                } else {
                    control.reportPhase(
                        phase = GuiRunPhase.WAITING_USER_INPUT,
                        statusMessage = "已打开${target.displayName}；自动点击能力尚未接入，可使用控制条暂停或取消。",
                    )
                    try {
                        awaitCancellation()
                    } finally {
                        launcher.clear(request.todoId)
                    }
                }
            }
        }
    }

    private fun String.isOpenOnlyRequest(target: GuiTargetApp): Boolean {
        val normalized = lowercase()
            .replace(Regex("[\\s，。！？,.!?]"), "")
            .replace("app", "")
            .replace("应用", "")
        val requests = setOf(
            "打开${target.displayName}",
            "启动${target.displayName}",
            "进入${target.displayName}",
            "帮我打开${target.displayName}",
            "请打开${target.displayName}",
            "打开一下${target.displayName}",
            "帮我启动${target.displayName}",
            "请帮我打开${target.displayName}",
        )
        return normalized in requests
    }
}
