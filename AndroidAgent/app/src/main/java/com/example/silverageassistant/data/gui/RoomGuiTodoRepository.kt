package com.example.silverageassistant.data.gui

import com.example.silverageassistant.domain.gui.GuiTodo
import com.example.silverageassistant.domain.gui.GuiTodoRepository
import com.example.silverageassistant.domain.gui.GuiTodoStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomGuiTodoRepository(
    private val dao: GuiTodoDao,
) : GuiTodoRepository {
    override val todos: Flow<List<GuiTodo>> = dao.observeAll().map { entities ->
        entities.map(::toDomain)
    }

    override suspend fun get(todoId: String): GuiTodo? = dao.get(todoId)?.let(::toDomain)

    override suspend fun save(todo: GuiTodo) {
        dao.save(todo.toEntity())
    }

    override suspend fun markUnfinishedAsInterrupted(updatedAtEpochMillis: Long) {
        dao.markUnfinishedAsInterrupted(updatedAtEpochMillis)
    }

    private fun toDomain(entity: GuiTodoEntity) = GuiTodo(
        id = entity.id,
        content = entity.content,
        status = runCatching { GuiTodoStatus.valueOf(entity.status) }
            .getOrDefault(GuiTodoStatus.INTERRUPTED),
        failedRunCount = entity.failedRunCount.coerceIn(0, 2),
        createdAtEpochMillis = entity.createdAtEpochMillis,
        updatedAtEpochMillis = entity.updatedAtEpochMillis,
        familyEscalationEventId = entity.familyEscalationEventId,
    )

    private fun GuiTodo.toEntity() = GuiTodoEntity(
        id = id,
        content = content,
        status = status.name,
        failedRunCount = failedRunCount,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        familyEscalationEventId = familyEscalationEventId,
    )
}
