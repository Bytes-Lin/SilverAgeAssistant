package com.example.silverageassistant.domain.gui

import com.example.silverageassistant.domain.agent.AgentToolRegistry
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class GuiRunPhase {
    RUNNING,
    PAUSED,
    RETRYING,
    WAITING_USER_INPUT,
    WAITING_ELDER_CONFIRMATION,
    WAITING_MANUAL_PAYMENT,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNAVAILABLE,
}

enum class GuiPauseReason {
    ELDER_REQUEST,
    MAIN_AGENT_REQUEST,
    TARGET_APP_LEFT,
    HUMAN_INTERVENTION,
    VOICE_INPUT,
}

data class GuiTaskSnapshot(
    val todoId: String,
    val content: String,
    val runAttempt: Int,
    val phase: GuiRunPhase,
    val pauseReason: GuiPauseReason? = null,
    val failureMessage: String? = null,
    val statusMessage: String? = null,
)

data class GuiRunRequest(
    val todoId: String,
    val content: String,
    val attempt: Int,
)

sealed interface GuiRunOutcome {
    data object Completed : GuiRunOutcome

    /**
     * 当前设备或当前实现不具备所需能力，不计入两次完整运行失败，也不通知家属。
     */
    data class Unavailable(val message: String) : GuiRunOutcome

    /**
     * 一个 Failure 表示整个 GuiRun 已耗尽自己的 ReAct/重规划预算，而不是单步失败。
     */
    data class Failed(val message: String) : GuiRunOutcome
}

enum class GuiRunPermission {
    RUNNING,
    PAUSED,
    CANCELLED,
}

interface GuiRunControl {
    val permission: StateFlow<GuiRunPermission>

    /**
     * GUI Executor 必须在观察、模型规划和每个真实动作前调用，确保人工暂停和取消立即生效。
     */
    suspend fun awaitRunning()

    /**
     * 执行器只可上报非终态进度。暂停、继续、取消和最终结果仍由任务管理器统一裁决。
     */
    suspend fun reportPhase(
        phase: GuiRunPhase,
        statusMessage: String? = null,
    )

    /**
     * 进入必须由老人明确操作才能继续的门。实现会暂停执行许可；悬浮控制条调用 resume()
     * 后才返回。支付门恢复后仍需重新观察页面，不能沿用门前截图。
     */
    suspend fun awaitUserGate(
        phase: GuiRunPhase,
        statusMessage: String,
    )
}

internal class MutableGuiRunControl(
    private val onPhaseReported: suspend (GuiRunPhase, String?) -> Unit,
) : GuiRunControl {
    private val mutablePermission = MutableStateFlow(GuiRunPermission.RUNNING)
    override val permission: StateFlow<GuiRunPermission> = mutablePermission.asStateFlow()

    override suspend fun awaitRunning() {
        when (
            permission.first {
                it == GuiRunPermission.RUNNING || it == GuiRunPermission.CANCELLED
            }
        ) {
            GuiRunPermission.RUNNING -> Unit
            GuiRunPermission.CANCELLED -> throw CancellationException("GUI task was cancelled")
            GuiRunPermission.PAUSED -> error("Paused state cannot pass the await predicate")
        }
    }

    override suspend fun reportPhase(
        phase: GuiRunPhase,
        statusMessage: String?,
    ) {
        require(
            phase in setOf(
                GuiRunPhase.RUNNING,
                GuiRunPhase.WAITING_USER_INPUT,
                GuiRunPhase.WAITING_ELDER_CONFIRMATION,
                GuiRunPhase.WAITING_MANUAL_PAYMENT,
            ),
        ) { "Executor cannot report a controlled or terminal GUI phase" }
        if (mutablePermission.value != GuiRunPermission.CANCELLED) {
            onPhaseReported(phase, statusMessage)
        }
    }

    override suspend fun awaitUserGate(
        phase: GuiRunPhase,
        statusMessage: String,
    ) {
        require(
            phase in setOf(
                GuiRunPhase.WAITING_USER_INPUT,
                GuiRunPhase.WAITING_ELDER_CONFIRMATION,
                GuiRunPhase.WAITING_MANUAL_PAYMENT,
            ),
        )
        reportPhase(phase, statusMessage)
        pause()
        awaitRunning()
    }

    fun pause() {
        if (mutablePermission.value != GuiRunPermission.CANCELLED) {
            mutablePermission.value = GuiRunPermission.PAUSED
        }
    }

    fun resume() {
        if (mutablePermission.value != GuiRunPermission.CANCELLED) {
            mutablePermission.value = GuiRunPermission.RUNNING
        }
    }

    fun cancel() {
        mutablePermission.value = GuiRunPermission.CANCELLED
    }
}

fun interface GuiRunExecutor {
    suspend fun execute(
        request: GuiRunRequest,
        control: GuiRunControl,
        sharedTools: AgentToolRegistry,
    ): GuiRunOutcome
}

fun interface GuiTaskNoticeSink {
    /**
     * 第一次完整运行失败后必须先告知老人，再自动开始第二次完整运行。
     */
    suspend fun notifySecondAttemptStarting(todo: GuiTodo)
}

fun interface GuiFailureEscalationSink {
    /**
     * 只有第二次完整 GuiRun 失败后调用。实现负责使用同一事件 ID 做可靠幂等重试。
     */
    suspend fun escalate(todo: GuiTodo, failureMessage: String): String?
}

fun interface GuiTerminalTaskSink {
    /** 第二次完整失败进入终态后，退出目标 App 并把老人带回聊天页面。 */
    suspend fun onFinalFailure(todo: GuiTodo)
}

object NoOpGuiTaskNoticeSink : GuiTaskNoticeSink {
    override suspend fun notifySecondAttemptStarting(todo: GuiTodo) = Unit
}

object NoOpGuiFailureEscalationSink : GuiFailureEscalationSink {
    override suspend fun escalate(todo: GuiTodo, failureMessage: String): String? = null
}

object NoOpGuiTerminalTaskSink : GuiTerminalTaskSink {
    override suspend fun onFinalFailure(todo: GuiTodo) = Unit
}
