package com.example.silverageassistant.domain.gui

import com.example.silverageassistant.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug 构建使用的进程内 GUI Agent 追踪。不写日志文件、Room 或中台，也不保存截图字节。
 * details 可以包含模型 JSON 和测试任务文本，因此应用退出或手动清空后立即丢弃。
 */
data class GuiDebugEvent(
    val id: Long,
    val timestampEpochMillis: Long,
    val source: String,
    val stage: String,
    val message: String,
    val details: String? = null,
)

object GuiDebugTrace {
    private const val MAX_EVENTS = 100
    private const val MAX_DETAILS_LENGTH = 4_000
    private val lock = Any()
    private val sequence = AtomicLong(0)
    private val mutableEvents = MutableStateFlow<List<GuiDebugEvent>>(emptyList())
    val events: StateFlow<List<GuiDebugEvent>> = mutableEvents.asStateFlow()

    fun record(
        source: String,
        stage: String,
        message: String,
        details: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val event = GuiDebugEvent(
            id = sequence.incrementAndGet(),
            timestampEpochMillis = System.currentTimeMillis(),
            source = source.take(40),
            stage = stage.take(60),
            message = message.take(300),
            details = details?.take(MAX_DETAILS_LENGTH),
        )
        synchronized(lock) {
            mutableEvents.value = (mutableEvents.value + event).takeLast(MAX_EVENTS)
        }
    }

    fun clear() {
        synchronized(lock) {
            mutableEvents.value = emptyList()
        }
    }
}
