package com.example.silverageassistant.domain.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface AgentMemorySnapshotProvider {
    suspend fun memoryMarkdown(): String
}

/**
 * Keeps one immutable memory snapshot per Agent owner for the lifetime of the Android process.
 * Writes to MEMORY.md are deliberately not reflected until a new process is created.
 */
class ProcessAgentMemorySnapshotProvider(
    private val owner: String,
    private val loader: suspend () -> String,
) : AgentMemorySnapshotProvider {
    init {
        require(owner.isNotBlank()) { "owner must not be blank" }
    }

    override suspend fun memoryMarkdown(): String = mutex.withLock {
        snapshots[owner] ?: loader().also { snapshots[owner] = it }
    }

    private companion object {
        val mutex = Mutex()
        val snapshots = mutableMapOf<String, String>()
    }
}
