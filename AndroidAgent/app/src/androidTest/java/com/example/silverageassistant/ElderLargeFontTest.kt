package com.example.silverageassistant

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.example.silverageassistant.ui.role.RoleSelectionScreen
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import org.junit.Rule
import org.junit.Test

class ElderLargeFontTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun roleSelection_supportsLargeSystemFont() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.5f),
            ) {
                SilverAgeAssistantTheme(darkTheme = false) {
                    RoleSelectionScreen(onElderSelected = {}, onFamilySelected = {})
                }
            }
        }

        composeRule.onNodeWithContentDescription("进入老人模式").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("进入家属模式")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
