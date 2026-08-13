package com.example.silverageassistant

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.example.silverageassistant.domain.weather.CurrentWeather
import com.example.silverageassistant.domain.weather.DailyWeather
import com.example.silverageassistant.domain.weather.WeatherSnapshot
import com.example.silverageassistant.ui.home.ElderHomeScreen
import com.example.silverageassistant.ui.home.HomeWeatherUiState
import com.example.silverageassistant.ui.reminders.ReminderItemUi
import com.example.silverageassistant.ui.reminders.ReminderStatus
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class ElderHomeWeatherScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun currentAndNextThreeDays_areDisplayedOnHome() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    ElderHomeScreen(
                        onConversation = {},
                        onReminders = {},
                        onFamilyContacts = {},
                        onNews = {},
                        onSettings = {},
                        todayReminders = listOf(
                            ReminderItemUi(
                                id = "family-notification",
                                eventTimeEpochMillis = 2L,
                                time = "下午 3:30",
                                title = "家人通知",
                                detail = "今晚一起回家吃饭。",
                            ),
                        ),
                        weatherState = HomeWeatherUiState(snapshot = weatherSnapshot()),
                    )
                }
            }
        }

        composeRule.onNodeWithText("多云  28℃").assertIsDisplayed()
        composeRule.onNodeWithText("上海").assertIsDisplayed()
        composeRule.onNodeWithText("明天 中雨  24～32℃").assertIsDisplayed()
        composeRule.onNodeWithText("后天 多云  25～33℃").assertIsDisplayed()
        composeRule.onNodeWithText("最近提醒").assertIsDisplayed()
        composeRule.onNodeWithText("下午 3:30 家人通知").assertIsDisplayed()
        composeRule.onNodeWithText("今晚一起回家吃饭。").assertIsDisplayed()
    }

    @Test
    fun noTodayReminders_showsEmptyMessage() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    ElderHomeScreen(
                        onConversation = {},
                        onReminders = {},
                        onFamilyContacts = {},
                        onNews = {},
                        onSettings = {},
                        todayReminders = emptyList(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("暂无要完成的提醒待办").assertIsDisplayed()
    }

    @Test
    fun allTodayRemindersCompleted_showsNoPendingTodo() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    ElderHomeScreen(
                        onConversation = {},
                        onReminders = {},
                        onFamilyContacts = {},
                        onNews = {},
                        onSettings = {},
                        todayReminders = listOf(
                            ReminderItemUi(
                                id = "completed-reminder",
                                eventTimeEpochMillis = 3L,
                                time = "下午 4:00",
                                title = "已经完成的待办",
                                detail = "不应继续显示在首页。",
                                status = ReminderStatus.Completed,
                            ),
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithText("暂无要完成的提醒待办").assertIsDisplayed()
        composeRule.onAllNodesWithText("下午 4:00 已经完成的待办").assertCountEquals(0)
    }

    @Test
    fun completedNewerReminder_doesNotHideLatestPendingTodo() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    ElderHomeScreen(
                        onConversation = {},
                        onReminders = {},
                        onFamilyContacts = {},
                        onNews = {},
                        onSettings = {},
                        todayReminders = listOf(
                            ReminderItemUi(
                                id = "pending-reminder",
                                eventTimeEpochMillis = 2L,
                                time = "下午 3:00",
                                title = "尚未完成的待办",
                                detail = "请按时完成。",
                            ),
                            ReminderItemUi(
                                id = "completed-reminder",
                                eventTimeEpochMillis = 3L,
                                time = "下午 4:00",
                                title = "已经完成的待办",
                                detail = "不应继续显示在首页。",
                                status = ReminderStatus.Completed,
                            ),
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithText("下午 3:00 尚未完成的待办").assertIsDisplayed()
        composeRule.onAllNodesWithText("下午 4:00 已经完成的待办").assertCountEquals(0)
    }

    private fun weatherSnapshot() = WeatherSnapshot(
        fetchedAt = Instant.parse("2026-07-18T08:00:00Z"),
        timezone = "Asia/Shanghai",
        locationName = "上海",
        current = CurrentWeather(
            observedAtLocal = "2026-07-18T16:00",
            condition = "多云",
            weatherCode = 2,
            temperatureCelsius = 28.4,
            apparentTemperatureCelsius = 30.1,
            relativeHumidityPercent = 62,
            precipitationMillimetres = 0.0,
            windSpeedKilometresPerHour = 12.0,
        ),
        daily = (0L..3L).map { offset ->
            DailyWeather(
                date = LocalDate.parse("2026-07-18").plusDays(offset),
                condition = if (offset == 1L) "中雨" else "多云",
                weatherCode = if (offset == 1L) 63 else 2,
                minimumTemperatureCelsius = 23.0 + offset,
                maximumTemperatureCelsius = 31.0 + offset,
                precipitationProbabilityPercent = if (offset == 1L) 80 else 20,
                precipitationMillimetres = if (offset == 1L) 12.0 else 0.0,
                maximumWindSpeedKilometresPerHour = 20.0,
            )
        },
        advisories = listOf("明天可能下雨，外出记得带伞。"),
    )
}
