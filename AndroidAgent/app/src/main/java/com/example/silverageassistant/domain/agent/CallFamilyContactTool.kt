package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.data.contacts.FamilyContactStore
import com.example.silverageassistant.data.middleserver.FamilyContactProfile
import com.example.silverageassistant.domain.model.ChatToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PendingPhoneCall(
    val requestId: Long,
    val displayName: String,
    val relationship: String,
)

fun interface PhoneCallLauncher {
    fun launch(phoneNumber: String, direct: Boolean)
}

class PendingPhoneCallCoordinator {
    private data class Target(
        val request: PendingPhoneCall,
        val phoneNumber: String,
    )

    private val _pending = MutableStateFlow<PendingPhoneCall?>(null)
    val pending: StateFlow<PendingPhoneCall?> = _pending.asStateFlow()
    private var target: Target? = null
    private var nextRequestId = 1L

    @Synchronized
    fun prepare(contact: FamilyContactProfile) {
        val request = PendingPhoneCall(
            requestId = nextRequestId++,
            displayName = contact.displayName,
            relationship = contact.relationship.toRelationshipLabel(),
        )
        target = Target(request, contact.mobileNumber)
        _pending.value = request
    }

    @Synchronized
    fun dismiss(requestId: Long) {
        if (target?.request?.requestId == requestId) {
            target = null
            _pending.value = null
        }
    }

    @Synchronized
    fun launch(requestId: Long, direct: Boolean, launcher: PhoneCallLauncher): Boolean {
        val current = target?.takeIf { it.request.requestId == requestId } ?: return false
        launcher.launch(current.phoneNumber, direct)
        target = null
        _pending.value = null
        return true
    }
}

class CallFamilyContactTool(
    private val contactStore: FamilyContactStore,
    private val pendingCallCoordinator: PendingPhoneCallCoordinator,
) : AgentTool {
    override val definition = ChatToolDefinition(
        name = NAME,
        description = "准备给已绑定的家属打电话。只提供家属称呼或关系，不要提供、推测或生成手机号。",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "display_name": {
                  "type": "string",
                  "description": "家属在绑定信息中的称呼，不确定时留空"
                },
                "relationship": {
                  "type": "string",
                  "description": "老人说出的关系，例如儿子、女儿、子女、亲属或照护人"
                }
              },
              "additionalProperties": false
            }
        """.trimIndent(),
    )
    override val riskLevel = ToolRiskLevel.Medium
    override val executionPolicy = ToolExecutionPolicy.PrepareForUserConfirmation
    override val runningDisplayName = "正在查找家人"

    override suspend fun execute(argumentsJson: String): String {
        val arguments = Json.parseToJsonElement(argumentsJson).jsonObject
        require(arguments.keys.all { it == "display_name" || it == "relationship" })
        val displayName = arguments["display_name"]?.jsonPrimitive?.content?.trim().orEmpty()
        val relationship = arguments["relationship"]?.jsonPrimitive?.content?.trim().orEmpty()
        val contacts = contactStore.load()?.contacts.orEmpty()
        if (contacts.isEmpty()) {
            return errorResult("NO_BOUND_CONTACT", "本机还没有可拨打的已绑定家属")
        }

        val matches = contacts.filter { contact ->
            val nameMatches = displayName.isBlank() ||
                contact.displayName.trim().equals(displayName, ignoreCase = true)
            val relationshipMatches = relationship.isBlank() ||
                contact.relationship.matchesRelationship(relationship)
            nameMatches && relationshipMatches
        }
        if (matches.isEmpty()) {
            return errorResult("CONTACT_NOT_FOUND", "没有找到符合称呼或关系的已绑定家属")
        }
        if (matches.size > 1) {
            return buildJsonObject {
                put("ok", false)
                put("error_code", "CONTACT_AMBIGUOUS")
                put("message", "找到了多位家属，请让老人说出要联系的家属称呼")
                put(
                    "candidates",
                    buildJsonArray {
                        matches.forEach { contact ->
                            add(
                                buildJsonObject {
                                    put("display_name", contact.displayName)
                                    put("relationship", contact.relationship.toRelationshipLabel())
                                },
                            )
                        }
                    },
                )
            }.toString()
        }

        val contact = matches.single()
        pendingCallCoordinator.prepare(contact)
        return buildJsonObject {
            put("ok", true)
            put("status", "USER_CONFIRMATION_REQUIRED")
            put("display_name", contact.displayName)
            put("relationship", contact.relationship.toRelationshipLabel())
            put("message", "已在手机上显示拨号确认，用户确认后才会拨打")
        }.toString()
    }

    private fun errorResult(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error_code", code)
        put("message", message)
    }.toString()

    companion object {
        const val NAME = "call_family_contact"
    }
}

private fun String.matchesRelationship(query: String): Boolean {
    val normalized = uppercase()
    val queryNormalized = query.trim().lowercase()
    return when (normalized) {
        "CHILD" -> queryNormalized in setOf("child", "children", "子女", "儿子", "女儿", "孩子")
        "RELATIVE", "OTHER_FAMILY" -> queryNormalized in setOf("relative", "亲属", "其他亲属", "家人")
        "CAREGIVER" -> queryNormalized in setOf("caregiver", "照护人", "护工")
        else -> normalized.equals(query, ignoreCase = true)
    }
}

private fun String.toRelationshipLabel(): String = when (uppercase()) {
    "CHILD" -> "子女"
    "RELATIVE", "OTHER_FAMILY" -> "亲属"
    "CAREGIVER" -> "照护人"
    else -> "家人"
}
