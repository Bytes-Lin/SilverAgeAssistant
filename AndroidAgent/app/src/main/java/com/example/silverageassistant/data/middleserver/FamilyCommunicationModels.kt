package com.example.silverageassistant.data.middleserver

data class FamilyNotificationRequest(
    val elderId: String,
    val content: String,
    val clientRequestId: String,
    val createdAt: String,
)

data class FamilyReminderRequest(
    val elderId: String,
    val title: String,
    val content: String,
    val scheduledAt: String,
    val timezone: String,
    val clientRequestId: String,
)

data class FamilyCommandResult(
    val commandId: String,
    val serverSequence: Long,
    val status: String,
    val createdAt: String,
)

enum class RemoteCommandType {
    FAMILY_NOTIFICATION,
    REMOTE_REMINDER,
}

data class RemoteCommand(
    val commandId: String,
    val serverSequence: Long,
    val elderId: String,
    val type: RemoteCommandType,
    val title: String?,
    val content: String,
    val scheduledAt: String?,
    val timezone: String,
    val senderDisplayName: String,
    val createdAt: String,
)

data class PendingCommandsResult(
    val commands: List<RemoteCommand>,
    val nextAfterSequence: Long,
    val hasMore: Boolean,
)

data class FamilyReminderHistoryItem(
    val commandId: String,
    val title: String,
    val content: String,
    val scheduledAt: String,
    val timezone: String,
    val createdAt: String,
    val deliveryStatus: String,
    val completionStatus: String,
    val storedAt: String?,
    val completedAt: String?,
)

data class FamilyReminderHistoryResult(
    val reminders: List<FamilyReminderHistoryItem>,
    val nextCursor: String?,
)

interface FamilyCommunicationRepository {
    suspend fun sendNotification(request: FamilyNotificationRequest): FamilyCommandResult

    suspend fun createReminder(request: FamilyReminderRequest): FamilyCommandResult

    suspend fun getReminderHistory(
        elderId: String,
        limit: Int = 100,
        cursor: String? = null,
    ): FamilyReminderHistoryResult

    /** Hides one reminder from this family account without deleting the elder's reminder. */
    suspend fun archiveReminder(
        elderId: String,
        commandId: String,
        clientRequestId: String,
    ) {
        throw UnsupportedOperationException("Reminder archive is not available")
    }
}

interface ElderCommandRepository {
    suspend fun getPendingCommands(afterSequence: Long, limit: Int = 100): PendingCommandsResult

    suspend fun acknowledgeCommand(
        commandId: String,
        clientRequestId: String,
        storedAt: String,
    )

    suspend fun reportReminderCompleted(
        commandId: String,
        clientRequestId: String,
        completedAt: String,
    ) = Unit
}

data class FamilyContactProfile(
    val bindingId: String,
    val familyAccountId: String,
    val displayName: String,
    val mobileNumber: String,
    val relationship: String,
    val permissions: List<String>,
    val emergencyContact: Boolean,
    val boundAt: String,
)

data class FamilyContactsSyncResult(
    val contacts: List<FamilyContactProfile>,
    val snapshotVersion: String,
    val syncedAt: String,
)

interface ElderFamilyContactsRepository {
    suspend fun getFamilyContacts(): FamilyContactsSyncResult
}
