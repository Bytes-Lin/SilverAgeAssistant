package com.example.silverageassistant.platform.gui

import com.example.silverageassistant.domain.agent.AgentToolRegistry
import com.example.silverageassistant.domain.gui.GuiConfirmationScope
import com.example.silverageassistant.domain.gui.GuiDeviceAction
import com.example.silverageassistant.domain.gui.GuiDeviceActionAuthorization
import com.example.silverageassistant.domain.gui.GuiDeviceActionResult
import com.example.silverageassistant.domain.gui.GuiDeviceController
import com.example.silverageassistant.domain.gui.GuiDeviceControllerProvider
import com.example.silverageassistant.domain.gui.GuiObserveResult
import com.example.silverageassistant.domain.gui.GuiPlannedAction
import com.example.silverageassistant.domain.gui.GuiRunControl
import com.example.silverageassistant.domain.gui.GuiRunOutcome
import com.example.silverageassistant.domain.gui.GuiRunPermission
import com.example.silverageassistant.domain.gui.GuiRunPhase
import com.example.silverageassistant.domain.gui.GuiRunRequest
import com.example.silverageassistant.domain.gui.GuiTargetApp
import com.example.silverageassistant.domain.gui.GuiTargetAppLauncher
import com.example.silverageassistant.domain.gui.GuiTargetLaunchResult
import com.example.silverageassistant.domain.gui.GuiTargetSession
import com.example.silverageassistant.domain.gui.GuiVisionPlanner
import com.example.silverageassistant.domain.gui.vision.AffineTransform2D
import com.example.silverageassistant.domain.gui.vision.GuiNodeSnapshot
import com.example.silverageassistant.domain.gui.vision.GuiScreenObservation
import com.example.silverageassistant.domain.gui.vision.PixelRect
import com.example.silverageassistant.domain.gui.vision.PixelSize
import com.example.silverageassistant.domain.gui.vision.ScreenFrameGeometry
import com.example.silverageassistant.domain.gui.vision.ScreenshotPixelBudget
import com.example.silverageassistant.domain.gui.vision.ScreenshotResizePlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityGuiRunExecutorTest {
    @Test
    fun reactRun_returnsFailureWhenSameStepIsRepeatedMoreThanFiveTimes() = runBlocking {
        val controller = FakeController(
            observations = ArrayDeque(
                (1..6).map { GuiObserveResult.Captured(observation("frame-$it")) },
            ),
        )
        val planner = QueuePlanner(
            ArrayDeque(
                (1..6).map {
                    GuiPlannedAction.Wait(milliseconds = 0, reason = "等待页面")
                },
            ),
        )

        val outcome = executor(controller, planner).execute(
            GuiRunRequest("todo-limit", "在美团搜索午餐", 1),
            FakeControl(),
            AgentToolRegistry(emptyList()),
        )

        assertTrue(outcome is GuiRunOutcome.Failed)
        assertEquals(6, controller.observeCount)
    }

    @Test
    fun reactRun_allowsMoreThanFiveStepsWhenPageKeepsProgressing() = runBlocking {
        val actionCount = 7
        val controller = FakeController(
            observations = ArrayDeque(
                (1..(actionCount + 1)).map { index ->
                    GuiObserveResult.Captured(
                        observation(
                            frameId = "frame-$index",
                            nodeText = "页面-$index",
                        ),
                    )
                },
            ),
        )
        val planner = QueuePlanner(
            ArrayDeque(
                buildList {
                    repeat(actionCount) { index ->
                        add(
                            GuiPlannedAction.Device(
                                GuiDeviceAction.ClickNode("frame-${index + 1}", "0.1"),
                                "前往第 ${index + 2} 个页面",
                            ),
                        )
                    }
                    add(GuiPlannedAction.Complete("已在新页面验证任务完成"))
                },
            ),
        )

        val outcome = executor(controller, planner).execute(
            GuiRunRequest("todo-progress", "在美团搜索午餐", 1),
            FakeControl(),
            AgentToolRegistry(emptyList()),
        )

        assertEquals(GuiRunOutcome.Completed, outcome)
        assertEquals(actionCount + 1, controller.observeCount)
        assertEquals(actionCount, controller.actions.size)
    }

    @Test
    fun complexTask_observesPerActionAndCompletes() = runBlocking {
        val first = observation("frame-1")
        val second = observation("frame-2")
        val controller = FakeController(
            observations = ArrayDeque(
                listOf(
                    GuiObserveResult.Captured(first),
                    GuiObserveResult.Captured(second),
                ),
            ),
        )
        val planner = QueuePlanner(
            ArrayDeque(
                listOf(
                    GuiPlannedAction.Device(
                        GuiDeviceAction.ClickNode("frame-1", "0.1"),
                        "点击搜索框",
                    ),
                    GuiPlannedAction.Complete("已完成"),
                ),
            ),
        )

        val outcome = executor(controller, planner).execute(
            GuiRunRequest("todo-1", "在美团搜索米饭", 1),
            FakeControl(),
            AgentToolRegistry(emptyList()),
        )

        assertEquals(GuiRunOutcome.Completed, outcome)
        assertEquals(2, controller.observeCount)
        assertEquals(1, controller.actions.size)
        assertEquals(null, FakeLauncher.lastInstance.activeSession.value)
    }

    @Test
    fun orderSubmission_requiresExplicitElderGateBeforeAuthorization() = runBlocking {
        val first = observation("frame-open-cart")
        val confirmation = observation("frame-confirm")
        val second = observation("frame-submit")
        val third = observation("frame-done")
        val controller = FakeController(
            ArrayDeque(
                listOf(
                    GuiObserveResult.Captured(first),
                    GuiObserveResult.Captured(confirmation),
                    GuiObserveResult.Captured(second),
                    GuiObserveResult.Captured(third),
                ),
            ),
        )
        val planner = QueuePlanner(
            ArrayDeque(
                listOf(
                    GuiPlannedAction.Device(
                        GuiDeviceAction.ClickNode("frame-open-cart", "0.1"),
                        "打开购物车",
                    ),
                    GuiPlannedAction.AskElder(
                        "确认商品和金额后提交订单吗？",
                        GuiConfirmationScope.ORDER_SUBMISSION,
                    ),
                    GuiPlannedAction.Device(
                        GuiDeviceAction.ClickNode("frame-submit", "0.1"),
                        "提交订单",
                    ),
                    GuiPlannedAction.Complete("订单已提交"),
                ),
            ),
        )
        val control = FakeControl()

        val outcome = executor(controller, planner).execute(
            GuiRunRequest("todo-2", "在美团购买午餐", 1),
            control,
            AgentToolRegistry(emptyList()),
        )

        assertEquals(GuiRunOutcome.Completed, outcome)
        assertEquals(
            listOf(GuiRunPhase.WAITING_ELDER_CONFIRMATION),
            control.userGates.map { it.first },
        )
        assertEquals(false, controller.authorizations.first().allowOrderSubmission)
        assertTrue(controller.authorizations.last().allowOrderSubmission)
    }

    private fun executor(
        controller: GuiDeviceController,
        planner: GuiVisionPlanner,
    ): AccessibilityGuiRunExecutor {
        val launcher = FakeLauncher()
        FakeLauncher.lastInstance = launcher
        return AccessibilityGuiRunExecutor(
            launcher = launcher,
            controllerProvider = GuiDeviceControllerProvider { controller },
            planner = planner,
        )
    }

    private class QueuePlanner(
        private val actions: ArrayDeque<GuiPlannedAction>,
    ) : GuiVisionPlanner {
        override suspend fun plan(
            request: com.example.silverageassistant.domain.gui.GuiPlanningRequest,
        ): GuiPlannedAction = actions.removeFirst()
    }

    private class FakeController(
        private val observations: ArrayDeque<GuiObserveResult>,
    ) : GuiDeviceController {
        var observeCount = 0
        val actions = mutableListOf<GuiDeviceAction>()
        val authorizations = mutableListOf<GuiDeviceActionAuthorization>()

        override suspend fun observe(
            targetPackage: String,
            pixelBudget: ScreenshotPixelBudget,
        ): GuiObserveResult {
            observeCount++
            return observations.removeFirst()
        }

        override suspend fun perform(
            targetPackage: String,
            observation: GuiScreenObservation,
            action: GuiDeviceAction,
            authorization: GuiDeviceActionAuthorization,
        ): GuiDeviceActionResult {
            actions += action
            authorizations += authorization
            return GuiDeviceActionResult.Success("成功")
        }
    }

    private class FakeControl : GuiRunControl {
        override val permission = MutableStateFlow(GuiRunPermission.RUNNING)
        val userGates = mutableListOf<Pair<GuiRunPhase, String>>()

        override suspend fun awaitRunning() = Unit

        override suspend fun reportPhase(
            phase: GuiRunPhase,
            statusMessage: String?,
        ) = Unit

        override suspend fun awaitUserGate(
            phase: GuiRunPhase,
            statusMessage: String,
        ) {
            userGates += phase to statusMessage
        }
    }

    private class FakeLauncher : GuiTargetAppLauncher {
        private val target = GuiTargetApp("com.sankuai.meituan", "美团")
        override val activeSession = MutableStateFlow<GuiTargetSession?>(null)

        override fun resolve(taskContent: String): GuiTargetApp? =
            target.takeIf { taskContent.contains("美团") }

        override fun launch(
            todoId: String,
            targetApp: GuiTargetApp,
        ): GuiTargetLaunchResult {
            activeSession.value = GuiTargetSession(todoId, targetApp)
            return GuiTargetLaunchResult.Launched(targetApp)
        }

        override fun returnToTask(todoId: String): Boolean = true

        override fun clear(todoId: String) {
            activeSession.value = null
        }

        companion object {
            lateinit var lastInstance: FakeLauncher
        }
    }

    private fun observation(
        frameId: String,
        nodeText: String = "搜索",
    ): GuiScreenObservation {
        val captureSize = PixelSize(1080, 2400)
        val modelImage = ScreenshotResizePlanner.plan(
            captureSize = captureSize,
            budget = ScreenshotPixelBudget(1280, 1_200_000),
        )
        return GuiScreenObservation(
            geometry = ScreenFrameGeometry(
                frameId = frameId,
                capturedAtEpochMillis = 1,
                displayId = 0,
                rotationDegrees = 0,
                screenSize = captureSize,
                targetWindowInScreen = PixelRect.full(captureSize),
                modelImage = modelImage,
                captureToScreen = AffineTransform2D.Identity,
            ),
            uploadBytes = byteArrayOf(1, 2, 3),
            mimeType = "image/jpeg",
            targetPackage = "com.sankuai.meituan",
            windowTitle = "美团",
            nodes = listOf(
                GuiNodeSnapshot(
                    nodeId = "0.1",
                    text = nodeText,
                    contentDescription = null,
                    className = "android.widget.Button",
                    viewId = null,
                    boundsInScreen = PixelRect(100.0, 100.0, 300.0, 220.0),
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    password = false,
                ),
            ),
        )
    }
}
