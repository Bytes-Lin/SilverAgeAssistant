package com.example.silverageassistant.data.middleserver

import com.example.silverageassistant.data.safety.SafetyMonitoringConfiguration

enum class SafetyEventSeverity {
    GENERAL,
    EMERGENCY,
}

enum class SafetyEventType {
    HEALTH_DISCOMFORT_REPORTED,
    FAMILY_REQUEST,
    FALL_SUSPECTED,
    UNCONSCIOUSNESS_SUSPECTED,
    OTHER_ABNORMALITY,
}

data class SafetyEvent(
    val eventId: String,
    val serverSequence: Long,
    val occurredAt: String,
    val eventType: SafetyEventType,
    val eventSummary: String,
    val severity: SafetyEventSeverity,
    val acknowledgedAt: String?,
    val createdAt: String,
    val imageAvailable: Boolean = false,
    val imageContentType: String? = null,
    val imageByteSize: Long? = null,
)

data class SafetyEventImageUpload(
    val bytes: ByteArray,
    val contentType: String,
)

data class FamilySafetyEventsSnapshot(
    val currentDate: String,
    val timeZone: String,
    val events: List<SafetyEvent>,
    val syncedAt: String,
)

data class FamilySafetyConfigurationUpdateRequest(
    val elderId: String,
    val enabled: Boolean,
    val intervalMinutes: Int,
    val expectedRevision: Long?,
    val clientRequestId: String,
)

data class ElderSafetyEventRequest(
    val clientEventId: String,
    val occurredAt: String,
    val eventType: SafetyEventType,
    val eventSummary: String,
    val severity: SafetyEventSeverity,
)

interface FamilySafetyMonitoringRepository {
    suspend fun getSafetyMonitoringConfiguration(elderId: String): SafetyMonitoringConfiguration?

    suspend fun updateSafetyMonitoringConfiguration(
        request: FamilySafetyConfigurationUpdateRequest,
    ): SafetyMonitoringConfiguration

    suspend fun getTodaySafetyEvents(elderId: String): FamilySafetyEventsSnapshot

    suspend fun acknowledgeSafetyEvent(
        elderId: String,
        eventId: String,
        clientRequestId: String,
    ): SafetyEvent

    suspend fun getSafetyEventImage(
        elderId: String,
        eventId: String,
        thumbnail: Boolean,
    ): ByteArray?
}

interface ElderSafetyMonitoringRepository {
    suspend fun getSafetyMonitoringConfiguration(): SafetyMonitoringConfiguration?

    suspend fun createSafetyEvent(request: ElderSafetyEventRequest): SafetyEvent

    suspend fun uploadSafetyEventImage(eventId: String, image: SafetyEventImageUpload)
}
