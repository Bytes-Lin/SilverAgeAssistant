package com.example.silverageassistant.domain.agent

class MemoryFamilyContact private constructor(
    val displayName: String,
    val relationship: String,
    val contactHint: String,
    val emergencyContact: Boolean,
) {
    companion object {
        fun fromSensitiveContact(
            displayName: String,
            relationship: String,
            @Suppress("UNUSED_PARAMETER") mobileNumber: String,
            emergencyContact: Boolean,
        ) = MemoryFamilyContact(
            displayName = displayName,
            relationship = relationship,
            contactHint = "已在本机安全保存",
            emergencyContact = emergencyContact,
        )
    }
}

interface AgentLongTermMemory {
    suspend fun updateElderPreferredName(preferredName: String)

    suspend fun recordBoundFamily(contact: MemoryFamilyContact)

    suspend fun replaceFamilyContacts(contacts: List<MemoryFamilyContact>)

    suspend fun clearFamilyContacts()

    suspend fun appendMemory(note: String)

    suspend fun markdownForPrompt(): String
}
