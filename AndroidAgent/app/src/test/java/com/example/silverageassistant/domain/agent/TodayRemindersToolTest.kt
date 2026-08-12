package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.data.middleserver.RemoteCommand
import com.example.silverageassistant.data.reminders.PendingCommandAcknowledgement
import com.example.silverageassistant.data.reminders.ReminderRepository
import com.example.silverageassistant.data.reminders.StoredReminder
import com.example.silverageassistant.data.reminders.StoredReminderStatus
import com.example.silverageassistant.domain.model.ChatModelProvider
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatStreamEvent
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayRemindersToolTest {
    @Test
    fun execute_exportsTodayReminderSnapshotWithoutInternalId() = runBlocking {
        val repository = FakeReminderRepository(
            listOf(
                StoredReminder(
                    id = "internal-command-id",
                    title = "量血压",
                    detail = "测量后记下来",
                    scheduledAtEpochMillis = 1_786_409_400_000,
                    sourceDisplayName = "小林",
                    status = StoredReminderStatus.PENDING,
                ),
            ),
        )
        val result = Json.parseToJsonElement(
            TodayRemindersTool(repository) { ZoneId.of("Asia/Shanghai") }.execute("{}"),
        ).jsonObject

        assertEquals(1, result.getValue("count").jsonPrimitive.content.toInt())
        assertEquals("Asia/Shanghai", result.getValue("timezone").jsonPrimitive.content)
        val item = result.getValue("items").jsonArray.single().jsonObject
        assertEquals("量血压", item.getValue("title").jsonPrimitive.content)
        assertEquals("pending", item.getValue("status").jsonPrimitive.content)
        assertEquals(true, item.containsKey("deadline_at"))
        assertEquals(false, item.containsKey("id"))
        assertEquals(false, item.containsKey("command_id"))
    }

    @Test
    fun presenter_listsOnlyPendingAndSnoozedReminders() = runBlocking {
        val repository = FakeReminderRepository(
            listOf(
                reminder("pending", "量血压", StoredReminderStatus.PENDING),
                reminder("snoozed", "取快递", StoredReminderStatus.SNOOZED),
                reminder("completed", "晨间服药", StoredReminderStatus.COMPLETED),
            ),
        )
        val result = TodayRemindersTool(repository) { ZoneId.of("Asia/Shanghai") }.execute("{}")

        val response = TodayRemindersToolResultPresenter.present(result)

        assertTrue(response.contains("2个提醒尚未确认完成"))
        assertTrue(response.contains("量血压"))
        assertTrue(response.contains("取快递"))
        assertTrue(response.contains("已设置稍后提醒"))
        assertFalse(response.contains("晨间服药"))
    }

    @Test
    fun todayOutstandingQuery_isRoutedAndDoesNotCallChatModel() = runBlocking {
        val repository = FakeReminderRepository(
            listOf(reminder("pending", "量血压", StoredReminderStatus.PENDING)),
        )
        val provider = object : ChatModelProvider {
            override fun stream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
                error("deterministic reminder query must not call chat model")
            }
        }
        val coordinator = AgentChatCoordinator(
            provider = provider,
            toolRegistry = AgentToolRegistry(listOf(TodayRemindersTool(repository))),
            deterministicToolRouter = TodayRemindersMainAgentToolRouter(),
        )

        val events = coordinator.streamTurn("我今天还有什么事没做？").toList()

        assertTrue(events.contains(AgentChatEvent.ToolRunning("正在查看今日提醒")))
        assertTrue(
            events.any { event ->
                event is AgentChatEvent.TextDelta && event.text.contains("量血压")
            },
        )
        assertEquals(AgentChatEvent.Completed, events.last())
    }

    @Test
    fun reminderCreationAndTomorrowQuery_areNotMisrouted() {
        val router = TodayRemindersMainAgentToolRouter()

        assertEquals(null, router.route("提醒我今天下午买菜"))
        assertEquals(null, router.route("明天有什么事情要做"))
        assertEquals(null, router.route("怎么查看今天的提醒"))
        assertEquals(null, router.route("今天的提醒我已经看了"))
    }

    private fun reminder(
        id: String,
        title: String,
        status: StoredReminderStatus,
    ) = StoredReminder(
        id = id,
        title = title,
        detail = title,
        scheduledAtEpochMillis = 1_786_409_400_000,
        sourceDisplayName = "小林",
        status = status,
    )

    private class FakeReminderRepository(reminders: List<StoredReminder>) : ReminderRepository {
        override val reminders = MutableStateFlow(reminders)
        override suspend fun saveRemoteCommand(command: RemoteCommand) = Unit
        override suspend fun lastServerSequence(): Long = 0
        override suspend fun pendingAcknowledgements(): List<PendingCommandAcknowledgement> = emptyList()
        override suspend fun markAcknowledged(commandId: String) = Unit
        override suspend fun updateStatus(id: String, status: StoredReminderStatus) = Unit
    }
}
