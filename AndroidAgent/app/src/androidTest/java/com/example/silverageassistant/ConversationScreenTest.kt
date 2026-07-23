package com.example.silverageassistant

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.silverageassistant.ui.conversation.ConversationRoute
import com.example.silverageassistant.ui.conversation.ConversationTestTags
import com.example.silverageassistant.ui.conversation.ConversationViewModel
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import com.example.silverageassistant.domain.agent.AgentChatCoordinator
import com.example.silverageassistant.domain.agent.AgentToolRegistry
import com.example.silverageassistant.domain.model.ChatModelProvider
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatStreamEvent
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test

class ConversationScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun header_onlyShowsCompactContextPercentage() {
        val viewModel = conversationViewModel()
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    ConversationRoute(onBack = {}, viewModel = viewModel)
                }
            }
        }

        composeRule.onNodeWithText("0%").assertIsDisplayed()
        composeRule.onAllNodesWithText("可以开始聊天").assertCountEquals(0)
        composeRule.onAllNodesWithText(
            "现在可以使用打字或手写聊天。语音识别和语音播放将在后续接入。",
        ).assertCountEquals(0)
    }

    @Test
    fun streamingConversationAndTypedReply_areShownAsChatMessages() {
        val viewModel = conversationViewModel()
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    ConversationRoute(onBack = {}, viewModel = viewModel)
                }
            }
        }

        composeRule.onNodeWithContentDescription("银龄助手：您好，我在这里。今天想聊些什么？")
            .assertIsDisplayed()

        composeRule.onNodeWithTag(ConversationTestTags.TEXT_INPUT)
            .performTextInput("今天阳光真好。")
        composeRule.onNodeWithContentDescription("发送文字消息").performClick()

        composeRule.onNodeWithText("今天阳光真好。").assertIsDisplayed()
        composeRule.onNodeWithText("是呀，适合出去晒晒太阳。")
            .assertIsDisplayed()
    }

    @Test
    fun handwritingMode_explainsHowToUseSystemHandwritingKeyboard() {
        val viewModel = conversationViewModel()
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    ConversationRoute(onBack = {}, viewModel = viewModel)
                }
            }
        }

        composeRule.onNodeWithText("手写").performClick()

        composeRule.onNodeWithText("在系统手写键盘中书写").assertIsDisplayed()
        composeRule.onNodeWithText("请在手机键盘中切换到“手写”，识别结果会显示在这里。")
            .assertIsDisplayed()
    }

    private fun conversationViewModel(): ConversationViewModel {
        val provider = object : ChatModelProvider {
            override fun stream(request: ChatRequest) = flow {
                emit(ChatStreamEvent.TextDelta("是呀，"))
                emit(ChatStreamEvent.TextDelta("适合出去晒晒太阳。"))
                emit(ChatStreamEvent.Completed("stop"))
            }
        }
        return ConversationViewModel(
            AgentChatCoordinator(provider, AgentToolRegistry(emptyList())),
        )
    }
}
