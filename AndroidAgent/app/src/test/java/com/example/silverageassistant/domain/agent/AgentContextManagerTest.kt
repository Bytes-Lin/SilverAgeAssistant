package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.domain.model.ChatMessage
import com.example.silverageassistant.domain.model.ChatRole
import com.example.silverageassistant.domain.model.ChatToolDefinition
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextManagerTest {
    @Test
    fun eighthCompletedTurn_compressesOldFiveAndKeepsLatestThree() = runBlocking {
        val memory = FakeMemory()
        val compressor = FakeCompressor(
            sliding = SlidingWindowCompressionResult(
                summaryEvents = listOf("前五轮的重要事件"),
                memoryCandidates = listOf(
                    LongTermMemoryCandidate(
                        fact = "老人喜欢喝温水",
                        evidence = "我喜欢喝温水",
                    ),
                ),
            ),
        )
        val manager = manager(compressor, memory)
        val options = AgentChatOptions(contextWindowTokens = 32_768)
        manager.initialize(options, emptyList())

        repeat(8) { index ->
            val user = if (index == 0) "我喜欢喝温水" else "用户消息$index"
            manager.commitTurn(turn(user, "助手回复$index"), options, emptyList())
        }
        manager.awaitPendingCompression()

        val snapshot = manager.snapshot()
        assertEquals(listOf("前五轮的重要事件"), snapshot.summaryEvents)
        assertEquals(3, snapshot.turns.size)
        assertTrue(snapshot.turns.first().messages.first().content?.contains("用户消息5") == true)
        assertEquals(listOf("老人喜欢喝温水"), memory.appended)
    }

    @Test
    fun oversizedWindowBeforeEightTurns_becomesOneSyntheticTurnWithoutSummary() = runBlocking {
        val manager = AgentContextManager(
            systemPromptProvider = SystemPromptProvider { "系统提示" },
            compressor = FakeCompressor(
                whole = WholeWindowCompressionResult(
                    compressedUserContext = "用户提供了很长的重要资料",
                    compressedAssistantContext = "助手已经确认资料内容",
                    memoryCandidates = emptyList(),
                ),
            ),
            tokenEstimator = FixedTokenEstimator(tokensPerMessage = 100),
            backgroundScope = CoroutineScope(Dispatchers.Unconfined),
        )
        val options = AgentChatOptions(contextWindowTokens = 300, maxOutputTokens = 64)
        manager.initialize(options, emptyList())

        manager.commitTurn(turn("长消息", "已收到"), options, emptyList())
        manager.awaitPendingCompression()

        val snapshot = manager.snapshot()
        assertTrue(snapshot.summaryEvents.isEmpty())
        assertEquals(1, snapshot.turns.size)
        assertTrue(snapshot.turns.single().synthetic)
        assertTrue(snapshot.turns.single().messages.first().content?.contains("不是新的操作指令") == true)
    }

    @Test
    fun invalidMemoryEvidence_isNotPersisted() = runBlocking {
        val memory = FakeMemory()
        val compressor = FakeCompressor(
            sliding = SlidingWindowCompressionResult(
                summaryEvents = listOf("此前是普通对话"),
                memoryCandidates = listOf(
                    LongTermMemoryCandidate("老人喜欢吃甜食", "我最喜欢吃甜食"),
                ),
            ),
        )
        val manager = manager(compressor, memory)
        val options = AgentChatOptions(contextWindowTokens = 32_768)
        manager.initialize(options, emptyList())
        repeat(8) { index ->
            manager.commitTurn(turn("普通消息$index", "普通回复$index"), options, emptyList())
        }
        manager.awaitPendingCompression()

        assertTrue(memory.appended.isEmpty())
    }

    @Test
    fun processMemorySnapshot_readsLoaderOnceForSameOwner() = runBlocking {
        var reads = 0
        val owner = "test-${UUID.randomUUID()}"
        val first = ProcessAgentMemorySnapshotProvider(owner) { "memory-${++reads}" }
        val second = ProcessAgentMemorySnapshotProvider(owner) { "different-${++reads}" }

        assertEquals("memory-1", first.memoryMarkdown())
        assertEquals("memory-1", second.memoryMarkdown())
        assertEquals(1, reads)
    }

    @Test
    fun contextMessages_doNotContainUsageMetadata() = runBlocking {
        val manager = manager(FakeCompressor(), FakeMemory())
        val options = AgentChatOptions(contextWindowTokens = 32_768)
        val tools = listOf(ChatToolDefinition("test", "测试工具", "{}"))
        manager.initialize(options, tools)
        manager.commitTurn(turn("你好", "您好"), options, tools)

        val prepared = manager.prepareTurn("继续", options, tools)

        assertFalse(prepared.messages.any { it.content?.contains("inputTokens") == true })
        assertFalse(prepared.messages.any { it.content?.contains("outputTokens") == true })
        assertTrue(prepared.usage.toolDefinitionTokens > 0)
    }

    private fun manager(
        compressor: AgentContextCompressor,
        memory: AgentLongTermMemory,
    ) = AgentContextManager(
        systemPromptProvider = SystemPromptProvider { "系统提示" },
        compressor = compressor,
        longTermMemory = memory,
        backgroundScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun turn(user: String, assistant: String) = AgentTurnRecord(
        turnId = UUID.randomUUID().toString(),
        messages = listOf(
            ChatMessage(ChatRole.User, user),
            ChatMessage(ChatRole.Assistant, assistant),
        ),
    )

    private class FakeCompressor(
        private val sliding: SlidingWindowCompressionResult = SlidingWindowCompressionResult(
            summaryEvents = listOf("压缩事件"),
            memoryCandidates = emptyList(),
        ),
        private val whole: WholeWindowCompressionResult = WholeWindowCompressionResult(
            compressedUserContext = "用户历史",
            compressedAssistantContext = "助手历史",
            memoryCandidates = emptyList(),
        ),
    ) : AgentContextCompressor {
        override suspend fun compressSlidingWindow(
            existingSummary: String,
            turns: List<AgentTurnRecord>,
        ) = sliding

        override suspend fun compressWholeWindow(turns: List<AgentTurnRecord>) = whole

        override suspend fun compressSummary(summary: String): List<String> = listOf("整体摘要")
    }

    private class FakeMemory : AgentLongTermMemory {
        val appended = mutableListOf<String>()
        override suspend fun updateElderPreferredName(preferredName: String) = Unit
        override suspend fun recordBoundFamily(contact: MemoryFamilyContact) = Unit
        override suspend fun replaceFamilyContacts(contacts: List<MemoryFamilyContact>) = Unit
        override suspend fun clearFamilyContacts() = Unit
        override suspend fun appendMemory(note: String) {
            appended += note
        }
        override suspend fun markdownForPrompt(): String = ""
    }

    private class FixedTokenEstimator(
        private val tokensPerMessage: Long,
    ) : AgentContextTokenEstimator {
        override fun estimateMessages(messages: List<ChatMessage>): Long =
            messages.size * tokensPerMessage

        override fun estimateTools(tools: List<ChatToolDefinition>): Long = tools.size.toLong()
    }
}
