package com.example.silverageassistant

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.example.silverageassistant.data.middleserver.FamilyContactProfile
import com.example.silverageassistant.ui.family.FamilyContactsScreen
import com.example.silverageassistant.ui.family.FamilyContactsUiState
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import org.junit.Rule
import org.junit.Test

class FamilyContactsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun boundContact_showsSyncedNameAndFullMobileNumber() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    FamilyContactsScreen(
                        state = FamilyContactsUiState(
                            contacts = listOf(
                                FamilyContactProfile(
                                    bindingId = "binding-1",
                                    familyAccountId = "family-1",
                                    displayName = "李女士",
                                    mobileNumber = "13800138000",
                                    relationship = "CHILD",
                                    permissions = listOf("STATUS_SUMMARY"),
                                    emergencyContact = false,
                                    boundAt = "2026-07-17T08:00:00Z",
                                ),
                            ),
                            isLoadingLocal = false,
                        ),
                        onRefresh = {},
                        onCall = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("李女士").assertIsDisplayed()
        composeRule.onNodeWithText("138 0013 8000").assertIsDisplayed()
        composeRule.onAllNodesWithText("小林").assertCountEquals(0)
        composeRule.onAllNodesWithText("小周").assertCountEquals(0)
    }
}
