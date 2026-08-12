package com.example.silverageassistant.domain.gui

import com.example.silverageassistant.domain.agent.DefaultSystemPromptProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiAgentSystemPromptProviderTest {
    @Test
    fun mainPrompt_routesExplicitMeituanLaunchToGuiTool() = runBlocking {
        val prompt = DefaultSystemPromptProvider().systemPrompt()

        assertTrue(prompt.contains("gui_agent"))
        assertTrue(prompt.contains("打开"))
        assertTrue(prompt.contains("美团"))
        assertTrue(prompt.contains("STARTED 只表示后台任务刚创建"))
    }

    @Test
    fun guiPrompt_requiresFreshFramesAndManualPayment() = runBlocking {
        val prompt = DefaultGuiAgentSystemPromptProvider().systemPrompt()

        assertTrue(prompt.contains("每个真实动作前"))
        assertTrue(prompt.contains("frame_id"))
        assertTrue(prompt.contains("0..1000"))
        assertTrue(prompt.contains("老人亲自完成付款"))
        assertTrue(prompt.contains("短信验证码"))
    }
}
