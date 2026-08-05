package com.example.silverageassistant.domain.gui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiAgentToolTest {
    @Test
    fun start_returnsImmediatelyWithTodoId() = runBlocking {
        val controller = FakeController()
        val tool = GuiAgentTool(controller)

        val result = tool.execute(
            """{"action":"START","task_content":"帮我购买一袋大米"}""",
        )

        assertTrue(result.contains("\"status\":\"STARTED\""))
        assertTrue(result.contains("\"todo_id\":\"todo-1\""))
        assertTrue(result.contains("主对话可以继续使用"))
    }

    @Test
    fun start_rejectsSecondConcurrentTask() = runBlocking {
        val controller = FakeController().apply {
            active.value = snapshot
        }
        val tool = GuiAgentTool(controller)

        val result = tool.execute(
            """{"action":"START","task_content":"第二个任务"}""",
        )

        assertTrue(result.contains("\"status\":\"BUSY\""))
    }

    private class FakeController : GuiTaskController {
        val snapshot = GuiTaskSnapshot(
            todoId = "todo-1",
            content = "帮我购买一袋大米",
            runAttempt = 1,
            phase = GuiRunPhase.RUNNING,
        )
        val active = MutableStateFlow<GuiTaskSnapshot?>(null)
        override val activeTask = active

        override suspend fun startTask(content: String): GuiTaskStartResult {
            val current = active.value
            if (current != null) return GuiTaskStartResult.Busy(current)
            active.value = snapshot.copy(content = content)
            return GuiTaskStartResult.Accepted(active.value!!)
        }

        override suspend fun pause(reason: GuiPauseReason): GuiTaskControlResult =
            update(GuiRunPhase.PAUSED, reason)

        override suspend fun resume(): GuiTaskControlResult =
            update(GuiRunPhase.RUNNING, null)

        override suspend fun cancel(): GuiTaskControlResult =
            update(GuiRunPhase.CANCELLED, null)

        private fun update(
            phase: GuiRunPhase,
            reason: GuiPauseReason?,
        ): GuiTaskControlResult {
            val current = active.value ?: return GuiTaskControlResult.NoActiveTask
            val updated = current.copy(phase = phase, pauseReason = reason)
            active.value = updated
            return GuiTaskControlResult.Updated(updated)
        }
    }
}
