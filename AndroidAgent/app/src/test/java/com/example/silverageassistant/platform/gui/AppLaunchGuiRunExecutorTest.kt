package com.example.silverageassistant.platform.gui

import com.example.silverageassistant.domain.agent.AgentToolRegistry
import com.example.silverageassistant.domain.gui.GuiRunControl
import com.example.silverageassistant.domain.gui.GuiRunOutcome
import com.example.silverageassistant.domain.gui.GuiRunPermission
import com.example.silverageassistant.domain.gui.GuiRunPhase
import com.example.silverageassistant.domain.gui.GuiRunRequest
import com.example.silverageassistant.domain.gui.GuiTargetApp
import com.example.silverageassistant.domain.gui.GuiTargetAppLauncher
import com.example.silverageassistant.domain.gui.GuiTargetLaunchResult
import com.example.silverageassistant.domain.gui.GuiTargetSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchGuiRunExecutorTest {
    @Test
    fun openOnlyRequest_launchesTargetAndCompletes() = runBlocking {
        val launcher = FakeLauncher()
        val outcome = AppLaunchGuiRunExecutor(launcher).execute(
            request = GuiRunRequest("todo-1", "打开美团", 1),
            control = FakeControl(),
            sharedTools = AgentToolRegistry(emptyList()),
        )

        assertEquals(GuiRunOutcome.Completed, outcome)
        assertEquals(1, launcher.launchCount)
        assertEquals(null, launcher.activeSession.value)
    }

    @Test
    fun taskNeedingClicks_staysControllableWithoutClaimingCompletion() = runBlocking {
        val launcher = FakeLauncher()
        val control = FakeControl()
        val job: Job = launch {
            AppLaunchGuiRunExecutor(launcher).execute(
                request = GuiRunRequest("todo-1", "打开美团帮我点一份外卖", 1),
                control = control,
                sharedTools = AgentToolRegistry(emptyList()),
            )
        }

        val report = control.reported.await()
        assertEquals(GuiRunPhase.WAITING_USER_INPUT, report.first)
        assertTrue(report.second.orEmpty().contains("自动点击能力尚未接入"))
        assertTrue(job.isActive)
        job.cancel()
        job.join()
        assertEquals(null, launcher.activeSession.value)
    }

    private class FakeControl : GuiRunControl {
        override val permission = MutableStateFlow(GuiRunPermission.RUNNING)
        val reported = CompletableDeferred<Pair<GuiRunPhase, String?>>()

        override suspend fun awaitRunning() = Unit

        override suspend fun reportPhase(
            phase: GuiRunPhase,
            statusMessage: String?,
        ) {
            reported.complete(phase to statusMessage)
        }

        override suspend fun awaitUserGate(
            phase: GuiRunPhase,
            statusMessage: String,
        ) = Unit
    }

    private class FakeLauncher : GuiTargetAppLauncher {
        private val meituan = GuiTargetApp("com.sankuai.meituan", "美团")
        override val activeSession = MutableStateFlow<GuiTargetSession?>(null)
        var launchCount = 0

        override fun resolve(taskContent: String): GuiTargetApp? =
            meituan.takeIf { taskContent.contains("美团") }

        override fun launch(
            todoId: String,
            targetApp: GuiTargetApp,
        ): GuiTargetLaunchResult {
            launchCount += 1
            activeSession.value = GuiTargetSession(todoId, targetApp)
            return GuiTargetLaunchResult.Launched(targetApp)
        }

        override fun returnToTask(todoId: String): Boolean =
            activeSession.value?.todoId == todoId

        override fun clear(todoId: String) {
            if (activeSession.value?.todoId == todoId) {
                activeSession.value = null
            }
        }
    }
}
