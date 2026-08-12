package com.example.silverageassistant.domain.gui

import com.example.silverageassistant.domain.agent.AgentToolRegistry
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface GuiTaskStartResult {
    data class Accepted(val snapshot: GuiTaskSnapshot) : GuiTaskStartResult
    data class Busy(val snapshot: GuiTaskSnapshot) : GuiTaskStartResult
    data class Invalid(val message: String) : GuiTaskStartResult
}

sealed interface GuiTaskControlResult {
    data class Updated(val snapshot: GuiTaskSnapshot) : GuiTaskControlResult
    data object NoActiveTask : GuiTaskControlResult
    data object AlreadyFinished : GuiTaskControlResult
}

interface GuiTaskController {
    val activeTask: StateFlow<GuiTaskSnapshot?>

    suspend fun startTask(content: String): GuiTaskStartResult

    suspend fun pause(reason: GuiPauseReason): GuiTaskControlResult

    suspend fun resume(): GuiTaskControlResult

    suspend fun cancel(): GuiTaskControlResult

    /** 暂停执行许可但不重建覆盖层，避免 ACTION_DOWN 后丢失 ACTION_UP。 */
    suspend fun beginVoiceInput(): GuiTaskControlResult = GuiTaskControlResult.NoActiveTask

    /** 把 ASR 文本交给当前 GuiRun，并恢复执行；文本不会写入持久化 Todo。 */
    suspend fun submitVoiceInput(transcript: String): GuiTaskControlResult =
        GuiTaskControlResult.NoActiveTask
}

/**
 * GUI Agent 的进程内单任务调度器。
 *
 * - 同一时刻只运行一个 GUI Todo；
 * - 每个 Todo 最多自动执行两个完整 GuiRun；
 * - 单个 GuiRun 内部的 ReAct 次数由 GuiRunExecutor 自己管理；
 * - 暂停/继续不增加完整失败次数；
 * - 第二次完整失败后才调用家属协助事件出口。
 */
class GuiTaskManager(
    private val repository: GuiTodoRepository,
    private val executor: GuiRunExecutor,
    private val sharedTools: AgentToolRegistry,
    private val scope: CoroutineScope,
    private val noticeSink: GuiTaskNoticeSink = NoOpGuiTaskNoticeSink,
    private val escalationSink: GuiFailureEscalationSink = NoOpGuiFailureEscalationSink,
    private val terminalTaskSink: GuiTerminalTaskSink = NoOpGuiTerminalTaskSink,
    private val chatFeedbackSink: GuiTaskChatFeedbackSink = NoOpGuiTaskChatFeedbackSink,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : GuiTaskController {
    private val stateMutex = Mutex()
    private val mutableActiveTask = MutableStateFlow<GuiTaskSnapshot?>(null)
    override val activeTask: StateFlow<GuiTaskSnapshot?> = mutableActiveTask.asStateFlow()

    private var activeControl: MutableGuiRunControl? = null
    private var activeJob: Job? = null
    private var phaseBeforePause: GuiRunPhase = GuiRunPhase.RUNNING

    suspend fun recoverInterruptedTodos() {
        repository.markUnfinishedAsInterrupted(nowEpochMillis())
    }

    override suspend fun startTask(content: String): GuiTaskStartResult {
        val normalizedContent = content.trim()
        if (normalizedContent.isBlank()) {
            return GuiTaskStartResult.Invalid("GUI 任务内容不能为空")
        }
        if (normalizedContent.length > MAX_TODO_CONTENT_LENGTH) {
            return GuiTaskStartResult.Invalid("GUI 任务内容过长")
        }

        lateinit var acceptedSnapshot: GuiTaskSnapshot
        lateinit var jobToStart: Job
        stateMutex.withLock {
            val current = mutableActiveTask.value
            if (current != null && !current.phase.isTerminal()) {
                return GuiTaskStartResult.Busy(current)
            }

            val now = nowEpochMillis()
            val todo = GuiTodo(
                id = idFactory(),
                content = normalizedContent,
                status = GuiTodoStatus.RUNNING,
                failedRunCount = 0,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            repository.save(todo)
            val control = MutableGuiRunControl { phase, statusMessage ->
                reportExecutorPhase(todo.id, phase, statusMessage)
            }
            acceptedSnapshot = GuiTaskSnapshot(
                todoId = todo.id,
                content = todo.content,
                runAttempt = 1,
                phase = GuiRunPhase.RUNNING,
            )
            activeControl = control
            phaseBeforePause = GuiRunPhase.RUNNING
            mutableActiveTask.value = acceptedSnapshot
            jobToStart = scope.launch(start = CoroutineStart.LAZY) {
                runTodo(todo, control)
            }
            activeJob = jobToStart
        }
        GuiDebugTrace.record(
            source = "task_manager",
            stage = "task_started",
            message = "GUI Todo 已创建，等待后台执行",
            details = "todo=${acceptedSnapshot.todoId}\ntask=${acceptedSnapshot.content}",
        )
        jobToStart.start()
        return GuiTaskStartResult.Accepted(acceptedSnapshot)
    }

    override suspend fun pause(reason: GuiPauseReason): GuiTaskControlResult =
        stateMutex.withLock {
            val current = mutableActiveTask.value ?: return GuiTaskControlResult.NoActiveTask
            if (current.phase.isTerminal()) return GuiTaskControlResult.AlreadyFinished
            if (current.phase != GuiRunPhase.PAUSED) {
                phaseBeforePause = current.phase
            }
            activeControl?.pause()
            val updated = current.copy(
                phase = GuiRunPhase.PAUSED,
                pauseReason = reason,
            )
            mutableActiveTask.value = updated
            updateTodoStatus(current.todoId, GuiTodoStatus.PAUSED)
            GuiDebugTrace.record(
                source = "task_manager",
                stage = "task_paused",
                message = "GUI 任务已暂停",
                details = "reason=$reason\ntodo=${current.todoId}",
            )
            GuiTaskControlResult.Updated(updated)
        }

    override suspend fun resume(): GuiTaskControlResult = stateMutex.withLock {
        val current = mutableActiveTask.value ?: return GuiTaskControlResult.NoActiveTask
        if (current.phase.isTerminal()) return GuiTaskControlResult.AlreadyFinished
        activeControl?.resume()
        val updated = current.copy(
            phase = phaseBeforePause.takeUnless { it == GuiRunPhase.PAUSED }
                ?: GuiRunPhase.RUNNING,
            pauseReason = null,
        )
        mutableActiveTask.value = updated
        updateTodoStatus(current.todoId, GuiTodoStatus.RUNNING)
        GuiDebugTrace.record(
            source = "task_manager",
            stage = "task_resumed",
            message = "GUI 任务已继续",
            details = "todo=${current.todoId}",
        )
        GuiTaskControlResult.Updated(updated)
    }

    override suspend fun cancel(): GuiTaskControlResult = stateMutex.withLock {
        val current = mutableActiveTask.value ?: return GuiTaskControlResult.NoActiveTask
        if (current.phase.isTerminal()) return GuiTaskControlResult.AlreadyFinished
        activeControl?.cancel()
        val updated = current.copy(
            phase = GuiRunPhase.CANCELLED,
            pauseReason = null,
        )
        mutableActiveTask.value = updated
        updateTodoStatus(current.todoId, GuiTodoStatus.CANCELLED)
        activeJob?.cancel()
        GuiTaskControlResult.Updated(updated)
    }

    override suspend fun beginVoiceInput(): GuiTaskControlResult = stateMutex.withLock {
        val current = mutableActiveTask.value ?: return GuiTaskControlResult.NoActiveTask
        if (current.phase.isTerminal()) return GuiTaskControlResult.AlreadyFinished
        activeControl?.pause()
        GuiTaskControlResult.Updated(current)
    }

    override suspend fun submitVoiceInput(transcript: String): GuiTaskControlResult =
        stateMutex.withLock {
            val current = mutableActiveTask.value ?: return GuiTaskControlResult.NoActiveTask
            if (current.phase.isTerminal()) return GuiTaskControlResult.AlreadyFinished
            val normalized = transcript
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_VOICE_INPUT_LENGTH)
            if (normalized.isBlank()) {
                return GuiTaskControlResult.Updated(current)
            }
            activeControl?.submitVoiceInput(normalized)
            activeControl?.resume()
            phaseBeforePause = GuiRunPhase.RUNNING
            val updated = current.copy(
                phase = GuiRunPhase.RUNNING,
                pauseReason = null,
                statusMessage = "已收到语音说明，正在继续",
            )
            mutableActiveTask.value = updated
            updateTodoStatus(current.todoId, GuiTodoStatus.RUNNING)
            GuiDebugTrace.record(
                source = "task_manager",
                stage = "voice_input_received",
                message = "已收到 GUI 语音补充并恢复任务",
                details = "todo=${current.todoId}; chars=${normalized.length}",
            )
            GuiTaskControlResult.Updated(updated)
        }

    private suspend fun runTodo(
        initialTodo: GuiTodo,
        control: MutableGuiRunControl,
    ) {
        var todo = initialTodo
        try {
            for (attempt in 1..MAX_COMPLETE_RUN_ATTEMPTS) {
                control.awaitRunning()
                updateRuntime(
                    todo = todo,
                    attempt = attempt,
                    phase = GuiRunPhase.RUNNING,
                )
                val outcome = try {
                    executor.execute(
                        request = GuiRunRequest(
                            todoId = todo.id,
                            content = todo.content,
                            attempt = attempt,
                        ),
                        control = control,
                        sharedTools = sharedTools,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    GuiDebugTrace.record(
                        source = "task_manager",
                        stage = "executor_exception",
                        message = error.message ?: "GUI 执行器发生异常",
                        details = error::class.java.name,
                    )
                    GuiRunOutcome.Failed("GUI 任务执行发生错误")
                }
                GuiDebugTrace.record(
                    source = "task_manager",
                    stage = "run_outcome",
                    message = "第 $attempt 次完整运行结束：${outcome::class.simpleName}",
                    details = outcome.toString(),
                )

                when (outcome) {
                    GuiRunOutcome.Completed -> {
                        todo = todo.copy(
                            status = GuiTodoStatus.COMPLETED,
                            updatedAtEpochMillis = nowEpochMillis(),
                        )
                        repository.save(todo)
                        updateRuntime(todo, attempt, GuiRunPhase.COMPLETED)
                        chatFeedbackSink.publish(
                            GuiTaskChatFeedback.Completed(todo.id),
                        )
                        return
                    }

                    is GuiRunOutcome.Unavailable -> {
                        val unavailableTodo = todo.copy(
                            status = GuiTodoStatus.INTERRUPTED,
                            updatedAtEpochMillis = nowEpochMillis(),
                        )
                        repository.save(unavailableTodo)
                        updateRuntime(
                            todo = unavailableTodo,
                            attempt = attempt,
                            phase = GuiRunPhase.UNAVAILABLE,
                            failureMessage = outcome.message,
                        )
                        chatFeedbackSink.publish(
                            GuiTaskChatFeedback.Failed(
                                todoId = todo.id,
                                familyNotified = false,
                            ),
                        )
                        return
                    }

                    is GuiRunOutcome.Failed -> {
                        todo = todo.copy(
                            failedRunCount = attempt,
                            updatedAtEpochMillis = nowEpochMillis(),
                        )
                        if (attempt < MAX_COMPLETE_RUN_ATTEMPTS) {
                            repository.save(todo.copy(status = GuiTodoStatus.RUNNING))
                            updateRuntime(
                                todo = todo,
                                attempt = attempt + 1,
                                phase = GuiRunPhase.RETRYING,
                                failureMessage = outcome.message,
                            )
                            runCatching { noticeSink.notifySecondAttemptStarting(todo) }
                            control.awaitRunning()
                        } else {
                            finishAfterSecondFailure(todo, outcome.message, attempt)
                            return
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            // 显式取消已在 cancel() 中落库。进程/Scope 异常退出时保留运行态，
            // 下次启动由 recoverInterruptedTodos() 转换成 INTERRUPTED 提醒。
        }
    }

    private suspend fun finishAfterSecondFailure(
        todo: GuiTodo,
        failureMessage: String,
        attempt: Int,
    ) {
        val failedTodo = todo.copy(
            status = GuiTodoStatus.FAILED,
            failedRunCount = MAX_COMPLETE_RUN_ATTEMPTS,
            updatedAtEpochMillis = nowEpochMillis(),
        )
        repository.save(failedTodo)
        updateRuntime(
            todo = failedTodo,
            attempt = attempt,
            phase = GuiRunPhase.FAILED,
            failureMessage = failureMessage,
        )
        GuiDebugTrace.record(
            source = "task_manager",
            stage = "terminal_cleanup_started",
            message = "第二次完整运行失败，正在退出目标 App 并返回聊天页面",
            details = "todo=${todo.id}",
        )
        runCatching { terminalTaskSink.onFinalFailure(failedTodo) }
            .onFailure { error ->
                GuiDebugTrace.record(
                    source = "task_manager",
                    stage = "terminal_cleanup_failed",
                    message = error.message ?: "返回聊天页面失败",
                    details = error::class.java.name,
                )
            }

        GuiDebugTrace.record(
            source = "task_manager",
            stage = "family_escalation_started",
            message = "第二次完整运行失败，开始上报家属紧急事件",
            details = "todo=${todo.id}",
        )
        val escalationResult = runCatching {
            escalationSink.escalate(failedTodo, failureMessage)
        }
        val eventId = escalationResult.getOrNull()
        if (eventId != null) {
            runCatching {
                repository.save(
                    failedTodo.copy(
                        status = GuiTodoStatus.ESCALATED,
                        updatedAtEpochMillis = nowEpochMillis(),
                        familyEscalationEventId = eventId,
                    ),
                )
            }
            GuiDebugTrace.record(
                source = "task_manager",
                stage = "family_escalation_succeeded",
                message = "家属紧急事件已由中台保存",
                details = "todo=${todo.id}\nevent=$eventId",
            )
        } else {
            GuiDebugTrace.record(
                source = "task_manager",
                stage = "family_escalation_failed",
                message = escalationResult.exceptionOrNull()?.message
                    ?: "家属紧急事件出口不可用",
                details = escalationResult.exceptionOrNull()?.javaClass?.name,
            )
        }
        chatFeedbackSink.publish(
            GuiTaskChatFeedback.Failed(
                todoId = todo.id,
                familyNotified = eventId != null,
            ),
        )
    }

    private suspend fun updateRuntime(
        todo: GuiTodo,
        attempt: Int,
        phase: GuiRunPhase,
        failureMessage: String? = null,
    ) {
        stateMutex.withLock {
            val current = mutableActiveTask.value
            if (current?.todoId == todo.id && current.phase != GuiRunPhase.CANCELLED) {
                val effectivePhase = if (
                    current.phase == GuiRunPhase.PAUSED && !phase.isTerminal()
                ) {
                    GuiRunPhase.PAUSED
                } else {
                    phase
                }
                mutableActiveTask.value = current.copy(
                    runAttempt = attempt,
                    phase = effectivePhase,
                    pauseReason = current.pauseReason.takeIf {
                        effectivePhase == GuiRunPhase.PAUSED
                    },
                    failureMessage = failureMessage,
                    statusMessage = null,
                )
            }
        }
    }

    private suspend fun reportExecutorPhase(
        todoId: String,
        phase: GuiRunPhase,
        statusMessage: String?,
    ) {
        stateMutex.withLock {
            val current = mutableActiveTask.value
            if (
                current?.todoId == todoId &&
                !current.phase.isTerminal() &&
                current.phase != GuiRunPhase.PAUSED
            ) {
                mutableActiveTask.value = current.copy(
                    phase = phase,
                    pauseReason = null,
                    statusMessage = statusMessage,
                )
                phaseBeforePause = phase
            }
        }
    }

    private suspend fun updateTodoStatus(todoId: String, status: GuiTodoStatus) {
        val todo = repository.get(todoId) ?: return
        repository.save(
            todo.copy(
                status = status,
                updatedAtEpochMillis = nowEpochMillis(),
            ),
        )
    }

    private fun GuiRunPhase.isTerminal(): Boolean = when (this) {
        GuiRunPhase.COMPLETED,
        GuiRunPhase.FAILED,
        GuiRunPhase.CANCELLED,
        GuiRunPhase.UNAVAILABLE,
        -> true

        else -> false
    }

    private companion object {
        const val MAX_COMPLETE_RUN_ATTEMPTS = 2
        const val MAX_TODO_CONTENT_LENGTH = 200
        const val MAX_VOICE_INPUT_LENGTH = 300
    }
}
