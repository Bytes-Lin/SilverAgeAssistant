package com.example.silverageassistant.domain.gui

import com.example.silverageassistant.domain.agent.AgentToolCatalog
import com.example.silverageassistant.domain.agent.AgentToolRegistry
import com.example.silverageassistant.domain.agent.CurrentTimeTool
import com.example.silverageassistant.domain.agent.SharedAgentToolCapabilities
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiTaskManagerTest {
    @Test
    fun firstCompleteFailure_notifiesAndAutomaticallyStartsSecondRun() = runBlocking {
        val repository = FakeGuiTodoRepository()
        val attempts = mutableListOf<Int>()
        var retryNotices = 0
        val manager = manager(
            repository = repository,
            scope = this,
            executor = GuiRunExecutor { request, control, _ ->
                control.awaitRunning()
                attempts += request.attempt
                if (request.attempt == 1) {
                    GuiRunOutcome.Failed("第一次完整执行失败")
                } else {
                    GuiRunOutcome.Completed
                }
            },
            noticeSink = GuiTaskNoticeSink { retryNotices += 1 },
        )

        val result = manager.startTask("购买一袋大米")
        assertTrue(result is GuiTaskStartResult.Accepted)
        manager.activeTask.first { it?.phase == GuiRunPhase.COMPLETED }

        assertEquals(listOf(1, 2), attempts)
        assertEquals(1, retryNotices)
        val todoId = (result as GuiTaskStartResult.Accepted).snapshot.todoId
        assertEquals(GuiTodoStatus.COMPLETED, repository.get(todoId)?.status)
        assertEquals(1, repository.get(todoId)?.failedRunCount)
    }

    @Test
    fun secondCompleteFailure_escalatesExactlyOnce() = runBlocking {
        val repository = FakeGuiTodoRepository()
        var escalationCount = 0
        var terminalCleanupCount = 0
        val manager = manager(
            repository = repository,
            scope = this,
            executor = GuiRunExecutor { request, _, _ ->
                GuiRunOutcome.Failed("第 ${request.attempt} 次完整执行失败")
            },
            escalationSink = GuiFailureEscalationSink { _, _ ->
                escalationCount += 1
                "event-1"
            },
            terminalTaskSink = GuiTerminalTaskSink {
                terminalCleanupCount += 1
            },
        )

        val result = manager.startTask("帮我找一份清淡午餐")
        manager.activeTask.first { it?.phase == GuiRunPhase.FAILED }

        val todoId = (result as GuiTaskStartResult.Accepted).snapshot.todoId
        val todo = repository.get(todoId)
        assertEquals(1, escalationCount)
        assertEquals(1, terminalCleanupCount)
        assertEquals(GuiTodoStatus.ESCALATED, todo?.status)
        assertEquals(2, todo?.failedRunCount)
        assertEquals("event-1", todo?.familyEscalationEventId)
    }

    @Test
    fun secondTask_isRejectedWhileOneTaskOwnsTheScreen() = runBlocking {
        val repository = FakeGuiTodoRepository()
        val executorStarted = CompletableDeferred<Unit>()
        val manager = manager(
            repository = repository,
            scope = this,
            executor = GuiRunExecutor { _, control, _ ->
                executorStarted.complete(Unit)
                control.awaitRunning()
                CompletableDeferred<GuiRunOutcome>().await()
            },
        )

        manager.startTask("第一个任务")
        executorStarted.await()
        val second = manager.startTask("第二个任务")

        assertTrue(second is GuiTaskStartResult.Busy)
        manager.cancel()
        assertEquals(GuiRunPhase.CANCELLED, manager.activeTask.value?.phase)
    }

    @Test
    fun processRecovery_marksOnlyTodoMetadataInterrupted() = runBlocking {
        val repository = FakeGuiTodoRepository()
        repository.save(
            GuiTodo(
                id = "todo-1",
                content = "购买生活用品",
                status = GuiTodoStatus.RUNNING,
                failedRunCount = 0,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            ),
        )
        val manager = manager(
            repository = repository,
            scope = this,
            executor = GuiRunExecutor { _, _, _ -> GuiRunOutcome.Completed },
            nowEpochMillis = { 2 },
        )

        manager.recoverInterruptedTodos()

        assertEquals(GuiTodoStatus.INTERRUPTED, repository.get("todo-1")?.status)
        assertEquals("购买生活用品", repository.get("todo-1")?.content)
    }

    @Test
    fun currentTimeToolInstance_isSharedByBothAgentCapabilityViews() {
        val timeTool = CurrentTimeTool(
            Clock.fixed(
                Instant.parse("2026-07-31T02:30:00Z"),
                ZoneId.of("Asia/Shanghai"),
            ),
        )
        val catalog = AgentToolCatalog(listOf(timeTool))

        val mainTool = catalog.toolsFor(SharedAgentToolCapabilities.MainChat).single()
        val guiTool = catalog.toolsFor(SharedAgentToolCapabilities.GuiAgent).single()

        assertSame(timeTool, mainTool)
        assertSame(mainTool, guiTool)
    }

    @Test
    fun resume_restoresExecutorPhaseThatWasActiveBeforePause() = runBlocking {
        val repository = FakeGuiTodoRepository()
        val waitingReported = CompletableDeferred<Unit>()
        val manager = manager(
            repository = repository,
            scope = this,
            executor = GuiRunExecutor { _, control, _ ->
                control.reportPhase(
                    GuiRunPhase.WAITING_USER_INPUT,
                    "等待老人选择",
                )
                waitingReported.complete(Unit)
                CompletableDeferred<GuiRunOutcome>().await()
            },
        )

        manager.startTask("在美团选择午餐")
        waitingReported.await()
        manager.pause(GuiPauseReason.HUMAN_INTERVENTION)
        val resumed = manager.resume()

        assertTrue(resumed is GuiTaskControlResult.Updated)
        val snapshot = (resumed as GuiTaskControlResult.Updated).snapshot
        assertEquals(GuiRunPhase.WAITING_USER_INPUT, snapshot.phase)
        assertEquals("等待老人选择", snapshot.statusMessage)
        assertTrue(manager.cancel() is GuiTaskControlResult.Updated)
    }

    @Test
    fun unavailableCapability_doesNotRetryOrEscalate() = runBlocking {
        val repository = FakeGuiTodoRepository()
        var executionCount = 0
        var escalationCount = 0
        val manager = manager(
            repository = repository,
            scope = this,
            executor = GuiRunExecutor { _, _, _ ->
                executionCount += 1
                GuiRunOutcome.Unavailable("暂不支持")
            },
            escalationSink = GuiFailureEscalationSink { _, _ ->
                escalationCount += 1
                "event"
            },
        )

        val result = manager.startTask("打开未知应用")
        manager.activeTask.first { it?.phase == GuiRunPhase.UNAVAILABLE }

        assertEquals(1, executionCount)
        assertEquals(0, escalationCount)
        val todoId = (result as GuiTaskStartResult.Accepted).snapshot.todoId
        assertEquals(GuiTodoStatus.INTERRUPTED, repository.get(todoId)?.status)
    }

    @Test
    fun guiVoiceInput_isDeliveredToCurrentRunWithoutChangingTodoContent() = runBlocking {
        val repository = FakeGuiTodoRepository()
        val executorStarted = CompletableDeferred<Unit>()
        val continueExecutor = CompletableDeferred<Unit>()
        val received = CompletableDeferred<String?>()
        val manager = manager(
            repository = repository,
            scope = this,
            executor = GuiRunExecutor { _, control, _ ->
                executorStarted.complete(Unit)
                continueExecutor.await()
                control.awaitRunning()
                received.complete(control.consumeVoiceInput())
                GuiRunOutcome.Completed
            },
        )

        val start = manager.startTask("在美团选择午餐") as GuiTaskStartResult.Accepted
        executorStarted.await()
        assertTrue(manager.beginVoiceInput() is GuiTaskControlResult.Updated)
        continueExecutor.complete(Unit)
        assertTrue(manager.submitVoiceInput("  想要少辣的  ") is GuiTaskControlResult.Updated)

        assertEquals("想要少辣的", received.await())
        manager.activeTask.first { it?.phase == GuiRunPhase.COMPLETED }
        assertEquals("在美团选择午餐", repository.get(start.snapshot.todoId)?.content)
    }

    private fun manager(
        repository: GuiTodoRepository,
        scope: CoroutineScope,
        executor: GuiRunExecutor,
        noticeSink: GuiTaskNoticeSink = NoOpGuiTaskNoticeSink,
        escalationSink: GuiFailureEscalationSink = NoOpGuiFailureEscalationSink,
        terminalTaskSink: GuiTerminalTaskSink = NoOpGuiTerminalTaskSink,
        nowEpochMillis: () -> Long = { 1 },
    ) = GuiTaskManager(
        repository = repository,
        executor = executor,
        sharedTools = AgentToolRegistry(
            listOf(CurrentTimeTool()),
        ),
        scope = scope,
        noticeSink = noticeSink,
        escalationSink = escalationSink,
        terminalTaskSink = terminalTaskSink,
        nowEpochMillis = nowEpochMillis,
        idFactory = { "todo-${repository.hashCode()}" },
    )

    private class FakeGuiTodoRepository : GuiTodoRepository {
        private val state = MutableStateFlow<List<GuiTodo>>(emptyList())
        override val todos = state

        override suspend fun get(todoId: String): GuiTodo? =
            state.value.firstOrNull { it.id == todoId }

        override suspend fun save(todo: GuiTodo) {
            state.value = state.value.filterNot { it.id == todo.id } + todo
        }

        override suspend fun markUnfinishedAsInterrupted(updatedAtEpochMillis: Long) {
            state.value = state.value.map { todo ->
                if (todo.status in setOf(GuiTodoStatus.RUNNING, GuiTodoStatus.PAUSED)) {
                    todo.copy(
                        status = GuiTodoStatus.INTERRUPTED,
                        updatedAtEpochMillis = updatedAtEpochMillis,
                    )
                } else {
                    todo
                }
            }
        }
    }
}
