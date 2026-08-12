package com.example.silverageassistant.data.memory

import android.content.Context
import com.example.silverageassistant.domain.agent.AgentLongTermMemory
import com.example.silverageassistant.domain.agent.MemoryFamilyContact
import java.io.File
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 首版端侧长期记忆实现。
 *
 * MEMORY.md 使用受控区段标记更新身份、家属和已确认事实，避免整文件自由改写破坏结构。
 * 文件只位于应用私有目录；写入前对 Markdown 控制字符做最小转义，且不保存完整手机号或
 * API Key。后续结构化 MemoryItem 可以替换实现而不改变 AgentLongTermMemory 接口。
 */
class MarkdownAgentLongTermMemory private constructor(
    private val memoryFile: File,
    private val clock: Clock,
) : AgentLongTermMemory {
    constructor(context: Context) : this(
        memoryFile = File(context.filesDir, MEMORY_RELATIVE_PATH),
        clock = Clock.systemUTC(),
    )

    internal constructor(
        memoryFile: File,
        clock: Clock = Clock.systemUTC(),
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(memoryFile, clock)

    private val mutex = Mutex()

    override suspend fun updateElderPreferredName(preferredName: String) {
        val safeName = preferredName.toSafeMemoryValue()
        if (safeName.isBlank()) return
        updateSection(
            startMarker = ELDER_START,
            endMarker = ELDER_END,
            content = """
                ## 老人基本信息
                - 喜欢的称呼：$safeName
            """.trimIndent(),
        )
    }

    override suspend fun recordBoundFamily(contact: MemoryFamilyContact) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = ensureDocument()
                if (!current.familySectionIsEmpty()) return@withContext
                writeDocument(
                    current.replaceSection(
                        FAMILY_START,
                        FAMILY_END,
                        renderFamilyContacts(listOf(contact)),
                    ),
                )
            }
        }
    }

    override suspend fun replaceFamilyContacts(contacts: List<MemoryFamilyContact>) {
        updateSection(
            startMarker = FAMILY_START,
            endMarker = FAMILY_END,
            content = renderFamilyContacts(contacts),
        )
    }

    override suspend fun clearFamilyContacts() {
        replaceFamilyContacts(emptyList())
    }

    override suspend fun appendMemory(note: String) {
        val safeNote = note.toSafeMemoryValue().take(MAX_NOTE_LENGTH)
        if (safeNote.isBlank()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = ensureDocument()
                val notes = current.sectionContent(NOTES_START, NOTES_END)
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .toMutableList()
                val entry = "- $safeNote"
                if (entry in notes || notes.size >= MAX_NOTE_COUNT) return@withContext
                val updated = current.replaceSection(
                    NOTES_START,
                    NOTES_END,
                    (notes + entry).joinToString("\n"),
                )
                if (updated.toByteArray(Charsets.UTF_8).size > MAX_MEMORY_FILE_BYTES) {
                    return@withContext
                }
                writeDocument(updated)
            }
        }
    }

    override suspend fun markdownForPrompt(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureDocument()
        }
    }

    private suspend fun updateSection(
        startMarker: String,
        endMarker: String,
        content: String,
    ) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeDocument(ensureDocument().replaceSection(startMarker, endMarker, content))
            }
        }
    }

    private fun renderFamilyContacts(contacts: List<MemoryFamilyContact>): String = buildString {
        appendLine("## 已绑定家属")
        if (contacts.isEmpty()) {
            append("- 尚未同步")
            return@buildString
        }
        contacts.forEachIndexed { index, contact ->
            if (index > 0) appendLine()
            appendLine("- 称呼：${contact.displayName.toSafeMemoryValue().ifBlank { "家属" }}")
            appendLine("  - 关系：${contact.relationship.toRelationshipLabel()}")
            appendLine("  - 联系方式：${contact.contactHint.toSafeMemoryValue()}")
            append(
                "  - 紧急联系人：${if (contact.emergencyContact) "是" else "否"}",
            )
        }
    }

    private fun ensureDocument(): String {
        memoryFile.parentFile?.mkdirs()
        if (!memoryFile.exists()) {
            writeDocument(emptyDocument())
        }
        val current = memoryFile.readText(Charsets.UTF_8)
        return if (REQUIRED_MARKERS.all(current::contains)) {
            current
        } else {
            emptyDocument()
        }
    }

    private fun emptyDocument(): String = """
        # MEMORY.md
        <!-- silverage-memory-version: 1 -->
        <!-- 本文件位于老人设备的应用私有目录，不得写入密钥、验证码或完整手机号。 -->

        $ELDER_START
        ## 老人基本信息
        - 喜欢的称呼：尚未记录
        $ELDER_END

        $FAMILY_START
        ## 已绑定家属
        - 尚未同步
        $FAMILY_END

        ## 后续长期记忆
        $NOTES_START
        $NOTES_END

        <!-- 最近更新：${Instant.now(clock)} -->
    """.trimIndent() + "\n"

    private fun writeDocument(content: String) {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_MEMORY_FILE_BYTES) return
        memoryFile.parentFile?.mkdirs()
        val withUpdatedAt = content.replace(
            UPDATED_AT_REGEX,
            "<!-- 最近更新：${Instant.now(clock)} -->",
        )
        val temporary = File(memoryFile.parentFile, "${memoryFile.name}.tmp")
        // 先写临时文件再重命名，尽量避免进程在写入中途退出后留下半份 system memory。
        temporary.writeText(withUpdatedAt, Charsets.UTF_8)
        if (!temporary.renameTo(memoryFile)) {
            memoryFile.writeText(withUpdatedAt, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun String.replaceSection(
        startMarker: String,
        endMarker: String,
        content: String,
    ): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end >= 0) { "Invalid MEMORY.md section markers" }
        val contentStart = start + startMarker.length
        return substring(0, contentStart) +
            "\n$content\n" +
            substring(end)
    }

    private fun String.sectionContent(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        if (start < 0 || end < 0) return ""
        return substring(start + startMarker.length, end).trim()
    }

    private fun String.familySectionIsEmpty(): Boolean =
        sectionContent(FAMILY_START, FAMILY_END).contains("- 尚未同步")

    private fun String.toSafeMemoryValue(): String = trim()
        .replace(Regex("\\s+"), " ")
        .replace("<", "＜")
        .replace(">", "＞")
        .replace("#", "＃")
        .replace("`", "｀")

    private fun String.toRelationshipLabel(): String = when (trim().uppercase()) {
        "CHILD" -> "子女"
        "RELATIVE", "OTHER_FAMILY" -> "其他亲属"
        "CAREGIVER" -> "照护人"
        "OTHER" -> "其他"
        else -> toSafeMemoryValue().ifBlank { "尚未记录" }
    }

    private companion object {
        const val MEMORY_RELATIVE_PATH = "agent/MEMORY.md"
        const val ELDER_START = "<!-- elder-profile:start -->"
        const val ELDER_END = "<!-- elder-profile:end -->"
        const val FAMILY_START = "<!-- family-contacts:start -->"
        const val FAMILY_END = "<!-- family-contacts:end -->"
        const val NOTES_START = "<!-- memories:start -->"
        const val NOTES_END = "<!-- memories:end -->"
        const val MAX_NOTE_LENGTH = 300
        const val MAX_NOTE_COUNT = 100
        const val MAX_MEMORY_FILE_BYTES = 24_000
        val UPDATED_AT_REGEX = Regex("<!-- 最近更新：.*? -->")
        val REQUIRED_MARKERS = listOf(
            ELDER_START,
            ELDER_END,
            FAMILY_START,
            FAMILY_END,
            NOTES_START,
            NOTES_END,
        )
    }
}
