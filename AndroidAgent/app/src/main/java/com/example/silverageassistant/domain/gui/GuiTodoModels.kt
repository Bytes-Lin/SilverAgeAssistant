package com.example.silverageassistant.domain.gui

import kotlinx.coroutines.flow.Flow

enum class GuiTodoStatus {
    RUNNING,
    PAUSED,
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    ESCALATED,
}

/**
 * 可跨进程保留的 GUI Todo 摘要。
 *
 * 这里只保存老人下达的任务内容和恢复提示所需的技术元数据，不保存候选商品、地址、页面
 * 节点、截图、ReAct 历史、订单号或支付信息。真正的 GUI Run 始终只存在于当前进程内。
 */
data class GuiTodo(
    val id: String,
    val content: String,
    val status: GuiTodoStatus,
    val failedRunCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val familyEscalationEventId: String? = null,
)

interface GuiTodoRepository {
    val todos: Flow<List<GuiTodo>>

    suspend fun get(todoId: String): GuiTodo?

    suspend fun save(todo: GuiTodo)

    /**
     * 进程退出时无法可靠执行清理；新进程启动后把遗留运行态转换为提醒用的中断状态。
     */
    suspend fun markUnfinishedAsInterrupted(updatedAtEpochMillis: Long)
}
