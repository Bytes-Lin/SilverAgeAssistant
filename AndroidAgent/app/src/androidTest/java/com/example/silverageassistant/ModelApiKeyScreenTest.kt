package com.example.silverageassistant

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.silverageassistant.ui.settings.ModelApiKeyScreen
import com.example.silverageassistant.ui.settings.ModelApiKeyUiState
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import org.junit.Rule
import org.junit.Test

class ModelApiKeyScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun unconfiguredState_acceptsPasswordAndEnablesLocalSave() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    var draft by remember { mutableStateOf("") }
                    ModelApiKeyScreen(
                        state = ModelApiKeyUiState(isLoading = false, draft = draft),
                        onBack = {},
                        onDraftChanged = { draft = it },
                        onToggleVisibility = {},
                        onSave = {},
                        onRequestDelete = {},
                        onCancelDelete = {},
                        onConfirmDelete = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("输入 API Key").performTextInput("local-test-key")
        composeRule.onNodeWithContentDescription("将 API Key 加密保存在老人设备")
            .assertIsEnabled()
    }

    @Test
    fun configuredState_displaysOnlyMaskedKeyAndDeleteAction() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    ModelApiKeyScreen(
                        state = ModelApiKeyUiState(
                            isLoading = false,
                            isConfigured = true,
                            maskedKey = "••••1234",
                        ),
                        onBack = {},
                        onDraftChanged = {},
                        onToggleVisibility = {},
                        onSave = {},
                        onRequestDelete = {},
                        onCancelDelete = {},
                        onConfirmDelete = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("当前密钥：••••1234")
            .assertTextContains("••••1234")
            .assertIsDisplayed()
        composeRule.onNodeWithText("删除本机 API Key").assertIsDisplayed()
    }
}
