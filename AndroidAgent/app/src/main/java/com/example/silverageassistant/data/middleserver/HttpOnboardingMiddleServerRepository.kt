package com.example.silverageassistant.data.middleserver

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import com.example.silverageassistant.data.model.ModelRuntimeConfiguration
import com.example.silverageassistant.data.model.OpenAiCompatibleDialect
import com.example.silverageassistant.data.usage.ModelUsagePolicy
import com.example.silverageassistant.data.safety.SafetyMonitoringConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Android 与 FastAPI 中台的统一 REST Repository。
 *
 * 同一实现服务老人和家属角色，但每个接口都显式选择 family access token 或 device
 * credential，避免角色凭证混用。可靠业务数据以 REST 响应为准；WebSocket 只触发这些读取
 * 方法。模型 API Key 和日常模型流量不得进入本 Repository。
 */
class HttpOnboardingMiddleServerRepository(
    serverBaseUrl: String,
    private val credentialStore: MiddleServerCredentialStore,
    private val deviceId: String,
    private val deviceName: String,
) : OnboardingMiddleServerRepository,
    FamilyCommunicationRepository,
    ElderCommandRepository,
    ElderFamilyContactsRepository,
    FamilyModelConfigurationRepository,
    ElderModelConfigurationRepository,
    ElderModelUsageReportingRepository,
    FamilyModelUsageRepository,
    FamilySafetyMonitoringRepository,
    ElderSafetyMonitoringRepository {
    private val apiBaseUrl = "${serverBaseUrl.trimEnd('/').removeSuffix("/api/v1")}/api/v1"

    override suspend fun registerFamilyAndCreateBindingCode(
        request: FamilyOnboardingRequest,
    ): FamilyOnboardingResult {
        val registration = post(
            path = "/auth/family/register",
            body = JSONObject()
                .put("display_name", request.displayName)
                .put("mobile_number", request.mobileNumber)
                .put("client_request_id", UUID.randomUUID().toString()),
        )
        val accessToken = registration.requiredString("access_token")
        credentialStore.saveFamilySession(
            FamilySession(
                accessToken = accessToken,
                refreshToken = registration.requiredString("refresh_token"),
                accessTokenExpiresAt = registration.requiredString("access_token_expires_at"),
            ),
        )

        val elder = post(
            path = "/elders",
            body = JSONObject()
                .put("display_name", request.elderDisplayName)
                .put("mobile_number", request.elderMobileNumber)
                .put("relationship", request.relationship)
                .put("emergency_contact", request.emergencyContact)
                .put("client_request_id", UUID.randomUUID().toString()),
            bearerToken = accessToken,
        )
        val code = post(
            path = "/bindings/codes",
            body = JSONObject()
                .put("elder_id", elder.requiredString("elder_id"))
                .put("client_request_id", UUID.randomUUID().toString()),
            bearerToken = accessToken,
        )
        return FamilyOnboardingResult(
            bindingCode = code.requiredString("binding_code"),
            bindingCodeExpiresAt = code.requiredString("expires_at"),
            familyMobileMasked = code.requiredString("family_mobile_masked"),
            elderId = elder.requiredString("elder_id"),
        )
    }

    override suspend fun regenerateBindingCode(
        elderId: String,
    ): FamilyOnboardingResult {
        val clientRequestId = UUID.randomUUID().toString()
        return withFamilyAccessToken { accessToken ->
            val code = post(
                path = "/bindings/codes",
                body = JSONObject()
                    .put("elder_id", elderId)
                    .put("client_request_id", clientRequestId),
                bearerToken = accessToken,
            )
            FamilyOnboardingResult(
                bindingCode = code.requiredString("binding_code"),
                bindingCodeExpiresAt = code.requiredString("expires_at"),
                familyMobileMasked = code.requiredString("family_mobile_masked"),
                elderId = code.requiredString("elder_id"),
            )
        }
    }

    override suspend fun bindElderDevice(request: ElderBindingRequest): ElderBindingResult {
        val response = post(
            path = "/devices/bind",
            body = JSONObject()
                .put("binding_code", request.bindingCode)
                .put("family_mobile_number", request.familyMobileNumber)
                .put("elder_display_name", request.displayName)
                .put("sharing_consent", request.sharingConsent)
                .put("device_id", deviceId)
                .put("device_name", deviceName.take(80))
                .put("client_request_id", UUID.randomUUID().toString()),
        )
        credentialStore.saveDeviceCredential(response.requiredString("device_credential"))
        return ElderBindingResult(
            familyMobileMasked = response.requiredString("family_mobile_masked"),
            relationship = response.requiredString("relationship"),
            boundAt = response.requiredString("bound_at"),
        )
    }

    override suspend fun restoreFamilySession(): SessionRestoreResult {
        val savedSession = try {
            credentialStore.loadFamilySession()
        } catch (_: Exception) {
            credentialStore.clearFamilySession()
            return SessionRestoreResult(SessionRestoreStatus.INVALID)
        } ?: return SessionRestoreResult(SessionRestoreStatus.MISSING)

        return try {
            getBindings(savedSession.accessToken)
        } catch (error: MiddleServerRequestException) {
            if (error.code.isOfflineError()) return SessionRestoreResult(SessionRestoreStatus.OFFLINE)
            if (error.code != "AUTHENTICATION_REQUIRED") throw error
            try {
                val refreshed = post(
                    path = "/auth/refresh",
                    body = JSONObject().put("refresh_token", savedSession.refreshToken),
                )
                val updatedSession = savedSession.copy(
                    accessToken = refreshed.requiredString("access_token"),
                    accessTokenExpiresAt = refreshed.requiredString("access_token_expires_at"),
                )
                credentialStore.saveFamilySession(updatedSession)
                getBindings(updatedSession.accessToken)
            } catch (refreshError: MiddleServerRequestException) {
                when {
                    refreshError.code.isOfflineError() -> {
                        SessionRestoreResult(SessionRestoreStatus.OFFLINE)
                    }
                    refreshError.code == "AUTHENTICATION_REQUIRED" -> {
                        credentialStore.clearFamilySession()
                        SessionRestoreResult(SessionRestoreStatus.INVALID)
                    }
                    else -> throw refreshError
                }
            }
        }
    }

    override suspend fun restoreElderSession(): SessionRestoreResult {
        val credential = try {
            credentialStore.loadDeviceCredential()
        } catch (_: Exception) {
            credentialStore.clearDeviceCredential()
            return SessionRestoreResult(SessionRestoreStatus.INVALID)
        } ?: return SessionRestoreResult(SessionRestoreStatus.MISSING)

        return try {
            val result = getBindings(credential)
            if (result.binding == null) {
                credentialStore.clearDeviceCredential()
                SessionRestoreResult(SessionRestoreStatus.INVALID)
            } else {
                result
            }
        } catch (error: MiddleServerRequestException) {
            when {
                error.code.isOfflineError() -> SessionRestoreResult(SessionRestoreStatus.OFFLINE)
                error.code == "AUTHENTICATION_REQUIRED" -> {
                    credentialStore.clearDeviceCredential()
                    SessionRestoreResult(SessionRestoreStatus.INVALID)
                }
                else -> throw error
            }
        }
    }

    private suspend fun getBindings(bearerToken: String): SessionRestoreResult {
        val response = get(path = "/bindings", bearerToken = bearerToken)
        val bindings = response.optJSONArray("bindings")
            ?: throw JSONException("Missing response field")
        val first = bindings.optJSONObject(0)
        return SessionRestoreResult(
            status = SessionRestoreStatus.ACTIVE,
            binding = first?.let {
                RestoredBinding(
                    elderDisplayName = it.requiredString("elder_display_name"),
                    familyDisplayName = it.requiredString("family_display_name"),
                    relationship = it.requiredString("relationship"),
                    elderId = it.requiredString("elder_id"),
                )
            },
        )
    }

    override suspend fun sendNotification(
        request: FamilyNotificationRequest,
    ): FamilyCommandResult = withFamilyAccessToken { accessToken ->
        post(
            path = "/elders/${request.elderId}/commands/notifications",
            body = JSONObject()
                .put("client_request_id", request.clientRequestId)
                .put("content", request.content)
                .put("created_at", request.createdAt),
            bearerToken = accessToken,
            idempotencyKey = request.clientRequestId,
        ).toFamilyCommandResult()
    }

    override suspend fun createReminder(
        request: FamilyReminderRequest,
    ): FamilyCommandResult = withFamilyAccessToken { accessToken ->
        post(
            path = "/elders/${request.elderId}/commands/reminders",
            body = JSONObject()
                .put("client_request_id", request.clientRequestId)
                .put("title", request.title)
                .put("content", request.content)
                .put("scheduled_at", request.scheduledAt)
                .put("timezone", request.timezone),
            bearerToken = accessToken,
            idempotencyKey = request.clientRequestId,
        ).toFamilyCommandResult()
    }

    override suspend fun getPendingCommands(
        afterSequence: Long,
        limit: Int,
    ): PendingCommandsResult {
        val credential = credentialStore.loadDeviceCredential()
            ?: throw authenticationRequired("老人设备尚未绑定家人。")
        val response = get(
            path = "/commands/pending?after_sequence=$afterSequence&limit=${limit.coerceIn(1, 100)}",
            bearerToken = credential,
        )
        val commandsArray = response.optJSONArray("commands")
            ?: throw JSONException("Missing response field")
        val commands = buildList {
            for (index in 0 until commandsArray.length()) {
                val item = commandsArray.optJSONObject(index)
                    ?: throw JSONException("Invalid command item")
                val type = runCatching {
                    RemoteCommandType.valueOf(item.requiredString("command_type"))
                }.getOrElse { throw JSONException("Unknown command type") }
                add(
                    RemoteCommand(
                        commandId = item.requiredString("command_id"),
                        serverSequence = item.requiredLong("server_sequence"),
                        elderId = item.requiredString("elder_id"),
                        type = type,
                        title = item.optString("title").takeIf(String::isNotBlank),
                        content = item.requiredString("content"),
                        scheduledAt = item.optString("scheduled_at").takeIf(String::isNotBlank),
                        timezone = item.optString("timezone").ifBlank { "UTC" },
                        senderDisplayName = item.optJSONObject("sender")
                            ?.optString("display_name")
                            .orEmpty()
                            .ifBlank { "家人" },
                        createdAt = item.requiredString("created_at"),
                    ),
                )
            }
        }
        return PendingCommandsResult(
            commands = commands,
            nextAfterSequence = response.optLong("next_after_sequence", afterSequence),
            hasMore = response.optBoolean("has_more", false),
        )
    }

    override suspend fun acknowledgeCommand(
        commandId: String,
        clientRequestId: String,
        storedAt: String,
    ) {
        val credential = credentialStore.loadDeviceCredential()
            ?: throw authenticationRequired("老人设备尚未绑定家人。")
        post(
            path = "/commands/$commandId/ack",
            body = JSONObject()
                .put("client_request_id", clientRequestId)
                .put("ack_type", "STORED")
                .put("stored_at", storedAt),
            bearerToken = credential,
            idempotencyKey = clientRequestId,
        )
    }

    override suspend fun getFamilyContacts(): FamilyContactsSyncResult {
        val credential = credentialStore.loadDeviceCredential()
            ?: throw authenticationRequired("老人设备尚未绑定家人。")
        val response = get(
            path = "/devices/me/family-contacts",
            bearerToken = credential,
        )
        val contactsArray = response.optJSONArray("contacts")
            ?: throw JSONException("Missing response field")
        val contacts = buildList {
            for (index in 0 until contactsArray.length()) {
                val item = contactsArray.optJSONObject(index)
                    ?: throw JSONException("Invalid contact item")
                val permissionsArray = item.optJSONArray("permissions")
                    ?: throw JSONException("Missing response field")
                add(
                    FamilyContactProfile(
                        bindingId = item.requiredString("binding_id"),
                        familyAccountId = item.requiredString("family_account_id"),
                        displayName = item.requiredString("display_name"),
                        mobileNumber = item.requiredString("mobile_number"),
                        relationship = item.requiredString("relationship"),
                        permissions = buildList {
                            for (permissionIndex in 0 until permissionsArray.length()) {
                                add(permissionsArray.getString(permissionIndex))
                            }
                        },
                        emergencyContact = item.optBoolean("emergency_contact", false),
                        boundAt = item.requiredString("bound_at"),
                    ),
                )
            }
        }
        return FamilyContactsSyncResult(
            contacts = contacts,
            snapshotVersion = response.requiredString("snapshot_version"),
            syncedAt = response.requiredString("synced_at"),
        )
    }

    override suspend fun getFamilyModelConfiguration(
        elderId: String,
    ): ModelRuntimeConfiguration? = withFamilyAccessToken { accessToken ->
        try {
            get(
                path = "/elders/$elderId/model-config",
                bearerToken = accessToken,
            ).toModelRuntimeConfiguration()
        } catch (error: MiddleServerRequestException) {
            if (error.code == "MODEL_CONFIG_NOT_FOUND") null else throw error
        }
    }

    override suspend fun updateFamilyModelConfiguration(
        request: FamilyModelConfigurationUpdateRequest,
    ): ModelRuntimeConfiguration = withFamilyAccessToken { accessToken ->
        put(
            path = "/elders/${request.elderId}/model-config",
            body = request.configuration.toRemoteModelConfigJson()
                .put("expected_revision", request.expectedRevision ?: JSONObject.NULL)
                .put("client_request_id", request.clientRequestId),
            bearerToken = accessToken,
            idempotencyKey = request.clientRequestId,
        ).toModelRuntimeConfiguration()
    }

    override suspend fun getElderModelConfiguration(): ModelRuntimeConfiguration? {
        val credential = credentialStore.loadDeviceCredential()
            ?: throw authenticationRequired("老人设备尚未绑定家人。")
        return try {
            get(
                path = "/devices/me/model-config",
                bearerToken = credential,
            ).toModelRuntimeConfiguration()
        } catch (error: MiddleServerRequestException) {
            if (error.code == "MODEL_CONFIG_NOT_FOUND") null else throw error
        }
    }

    override suspend fun getSafetyMonitoringConfiguration(
        elderId: String,
    ): SafetyMonitoringConfiguration? = withFamilyAccessToken { accessToken ->
        try {
            get(
                path = "/elders/$elderId/safety-monitoring/config",
                bearerToken = accessToken,
            ).toSafetyMonitoringConfiguration()
        } catch (error: MiddleServerRequestException) {
            if (error.code == "SAFETY_CONFIG_NOT_FOUND") null else throw error
        }
    }

    override suspend fun updateSafetyMonitoringConfiguration(
        request: FamilySafetyConfigurationUpdateRequest,
    ): SafetyMonitoringConfiguration = withFamilyAccessToken { accessToken ->
        put(
            path = "/elders/${request.elderId}/safety-monitoring/config",
            body = JSONObject()
                .put("enabled", request.enabled)
                .put("interval_minutes", request.intervalMinutes)
                .put("expected_revision", request.expectedRevision ?: JSONObject.NULL)
                .put("client_request_id", request.clientRequestId),
            bearerToken = accessToken,
            idempotencyKey = request.clientRequestId,
        ).toSafetyMonitoringConfiguration()
    }

    override suspend fun getSafetyMonitoringConfiguration(): SafetyMonitoringConfiguration? {
        val credential = credentialStore.loadDeviceCredential()
            ?: throw authenticationRequired("老人设备尚未绑定家人。")
        return try {
            get(
                path = "/devices/me/safety-monitoring/config",
                bearerToken = credential,
            ).toSafetyMonitoringConfiguration()
        } catch (error: MiddleServerRequestException) {
            if (error.code == "SAFETY_CONFIG_NOT_FOUND") null else throw error
        }
    }

    override suspend fun createSafetyEvent(request: ElderSafetyEventRequest): SafetyEvent {
        val credential = credentialStore.loadDeviceCredential()
            ?: throw authenticationRequired("老人设备尚未绑定家人。")
        return post(
            path = "/devices/me/safety-events",
            body = JSONObject()
                .put("client_event_id", request.clientEventId)
                .put("occurred_at", request.occurredAt)
                .put("event_type", request.eventType.name)
                .put("event_summary", request.eventSummary)
                .put("severity", request.severity.name),
            bearerToken = credential,
            idempotencyKey = request.clientEventId,
        ).toSafetyEvent()
    }

    override suspend fun uploadSafetyEventImage(
        eventId: String,
        image: SafetyEventImageUpload,
    ) {
        val credential = credentialStore.loadDeviceCredential()
            ?: throw authenticationRequired("老人设备尚未绑定家人。")
        requestBinaryUpload(
            path = "/devices/me/safety-events/$eventId/image",
            bytes = image.bytes,
            contentType = image.contentType,
            bearerToken = credential,
            idempotencyKey = eventId,
        )
    }

    override suspend fun getTodaySafetyEvents(
        elderId: String,
    ): FamilySafetyEventsSnapshot = withFamilyAccessToken { accessToken ->
        val response = get(
            path = "/elders/$elderId/safety-events?scope=today",
            bearerToken = accessToken,
        )
        val eventsJson = response.optJSONArray("events")
            ?: throw JSONException("Missing response field")
        FamilySafetyEventsSnapshot(
            currentDate = response.requiredString("current_date"),
            timeZone = response.requiredString("timezone"),
            events = buildList {
                for (index in 0 until eventsJson.length()) {
                    add(eventsJson.getJSONObject(index).toSafetyEvent())
                }
            }.sortedByDescending(SafetyEvent::occurredAt),
            syncedAt = response.requiredString("synced_at"),
        )
    }

    override suspend fun acknowledgeSafetyEvent(
        elderId: String,
        eventId: String,
        clientRequestId: String,
    ): SafetyEvent = withFamilyAccessToken { accessToken ->
        post(
            path = "/elders/$elderId/safety-events/$eventId/acknowledge",
            body = JSONObject().put("client_request_id", clientRequestId),
            bearerToken = accessToken,
            idempotencyKey = clientRequestId,
        ).toSafetyEvent()
    }

    override suspend fun getSafetyEventImage(
        elderId: String,
        eventId: String,
        thumbnail: Boolean,
    ): ByteArray? = withFamilyAccessToken { accessToken ->
        try {
            requestBytes(
                path = "/elders/$elderId/safety-events/$eventId/image" +
                    "?variant=${if (thumbnail) "thumbnail" else "original"}",
                bearerToken = accessToken,
            )
        } catch (error: MiddleServerRequestException) {
            if (error.code == "SAFETY_EVENT_IMAGE_NOT_FOUND") null else throw error
        }
    }

    override suspend fun uploadModelUsage(batch: ModelUsageUploadBatch) {
        val credential = credentialStore.loadDeviceCredential()
            ?: throw authenticationRequired("老人设备尚未绑定家人。")
        val items = org.json.JSONArray().apply {
            batch.items.forEach { item ->
                put(
                    JSONObject()
                        .put("modality", item.modality)
                        .put("provider", item.provider)
                        .put("model", item.model ?: JSONObject.NULL)
                        .put("feature", item.feature)
                        .put("request_count", item.requestCount)
                        .put("success_count", item.successCount)
                        .put("input_tokens", item.inputTokens)
                        .put("output_tokens", item.outputTokens)
                        .put("asr_audio_duration_ms", item.asrAudioDurationMillis)
                        .put("tts_character_count", item.ttsCharacterCount)
                        .put("tts_audio_duration_ms", item.ttsAudioDurationMillis)
                        .put("contains_estimated_values", item.containsEstimatedValues),
                )
            }
        }
        val baseBody = JSONObject()
            .put("batch_id", batch.batchId)
            .put("period_started_at", batch.periodStartedAt)
            .put("period_ended_at", batch.periodEndedAt)
            .put("items", items)
        try {
            post(
                path = "/model-usage/batches",
                body = JSONObject(baseBody.toString())
                    .put("time_zone", batch.timeZone)
                    .put("time_zone_source", batch.timeZoneSource),
                bearerToken = credential,
                idempotencyKey = batch.batchId,
            )
        } catch (error: MiddleServerRequestException) {
            if (
                error.code != "REQUEST_VALIDATION_ERROR" &&
                error.code != "INVALID_USAGE_BATCH"
            ) {
                throw error
            }
            // Compatibility for a middle server that has not yet added the two
            // location-time-zone fields. Validation fails before the batch is stored.
            post(
                path = "/model-usage/batches",
                body = baseBody,
                bearerToken = credential,
                idempotencyKey = batch.batchId,
            )
        }
    }

    override suspend fun getFamilyModelUsage(
        elderId: String,
        from: String,
        to: String,
    ): FamilyModelUsageSummary = withFamilyAccessToken { accessToken ->
        val response = get(
            path = "/elders/$elderId/model-usage" +
                "?from=${from.asQueryValue()}&to=${to.asQueryValue()}",
            bearerToken = accessToken,
        )
        val totals = response.optJSONObject("totals")
            ?: throw JSONException("Missing response field")
        FamilyModelUsageSummary(
            periodStartedAt = response.requiredString("period_started_at"),
            periodEndedAt = response.requiredString("period_ended_at"),
            inputTokens = totals.optLong("input_tokens", 0),
            outputTokens = totals.optLong("output_tokens", 0),
            mllmRequestCount = totals.optLong("mllm_request_count", 0),
            asrRequestCount = totals.optLong("asr_request_count", 0),
            ttsRequestCount = totals.optLong("tts_request_count", 0),
            asrAudioDurationMillis = totals.optLong("asr_audio_duration_ms", 0),
            ttsCharacterCount = totals.optLong("tts_character_count", 0),
            ttsAudioDurationMillis = totals.optLong("tts_audio_duration_ms", 0),
            containsEstimatedValues = totals.optBoolean("contains_estimated_values", false),
            lastReportedAt = if (response.isNull("last_reported_at")) {
                null
            } else {
                response.optString("last_reported_at").takeIf(String::isNotBlank)
            },
        )
    }

    override suspend fun requestCurrentModelUsage(
        elderId: String,
        clientRequestId: String,
    ): ModelUsageRefreshResult = withFamilyAccessToken { accessToken ->
        val response = post(
            path = "/elders/$elderId/model-usage/refresh",
            body = JSONObject().put("client_request_id", clientRequestId),
            bearerToken = accessToken,
            idempotencyKey = clientRequestId,
        )
        ModelUsageRefreshResult(
            deviceOnline = response.optBoolean("device_online", false),
            requestedAt = response.requiredString("requested_at"),
        )
    }

    override suspend fun getFamilyDailyModelUsage(
        elderId: String,
    ): FamilyDailyModelUsageTimeline = withFamilyAccessToken { accessToken ->
        val response = get(
            path = "/elders/$elderId/model-usage/daily",
            bearerToken = accessToken,
        )
        val daysJson = response.optJSONArray("days")
            ?: throw JSONException("Missing response field")
        val days = buildList {
            for (index in 0 until daysJson.length()) {
                val day = daysJson.getJSONObject(index)
                val totals = day.optJSONObject("totals")
                    ?: throw JSONException("Missing response field")
                add(
                    FamilyDailyModelUsage(
                        date = day.requiredString("date"),
                        inputTokens = totals.optLong("input_tokens", 0),
                        outputTokens = totals.optLong("output_tokens", 0),
                        mllmRequestCount = totals.optLong("mllm_request_count", 0),
                        asrRequestCount = totals.optLong("asr_request_count", 0),
                        ttsRequestCount = totals.optLong("tts_request_count", 0),
                        containsEstimatedValues = totals.optBoolean(
                            "contains_estimated_values",
                            false,
                        ),
                    ),
                )
            }
        }
        FamilyDailyModelUsageTimeline(
            periodStartedOn = response.requiredString("period_started_on"),
            periodEndedOn = response.requiredString("period_ended_on"),
            currentDate = response.requiredString("current_date"),
            timeZone = response.requiredString("timezone"),
            timeZoneSource = response.requiredString("timezone_source"),
            days = days,
            lastReportedAt = if (response.isNull("last_reported_at")) {
                null
            } else {
                response.optString("last_reported_at").takeIf(String::isNotBlank)
            },
        )
    }

    private suspend fun <T> withFamilyAccessToken(block: suspend (String) -> T): T {
        // 家属短期 access token 失效时只刷新并重放一次。业务写操作依靠稳定幂等键，
        // 因而刷新后的重放不会创建重复事件。
        val savedSession = credentialStore.loadFamilySession()
            ?: throw authenticationRequired("家属登录已失效，请重新登录。")
        return try {
            block(savedSession.accessToken)
        } catch (error: MiddleServerRequestException) {
            if (error.code != "AUTHENTICATION_REQUIRED") throw error
            val refreshed = try {
                post(
                    path = "/auth/refresh",
                    body = JSONObject().put("refresh_token", savedSession.refreshToken),
                )
            } catch (refreshError: MiddleServerRequestException) {
                if (refreshError.code == "AUTHENTICATION_REQUIRED") {
                    credentialStore.clearFamilySession()
                }
                throw refreshError
            }
            val updatedSession = savedSession.copy(
                accessToken = refreshed.requiredString("access_token"),
                accessTokenExpiresAt = refreshed.requiredString("access_token_expires_at"),
            )
            credentialStore.saveFamilySession(updatedSession)
            block(updatedSession.accessToken)
        }
    }

    private fun String.asQueryValue(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun JSONObject.toSafetyMonitoringConfiguration() = SafetyMonitoringConfiguration(
        enabled = optBoolean("enabled", true),
        intervalMinutes = getInt("interval_minutes"),
        revision = requiredLong("revision"),
        updatedAt = requiredString("updated_at"),
    ).also(SafetyMonitoringConfiguration::validate)

    private fun JSONObject.toSafetyEvent() = SafetyEvent(
        eventId = requiredString("event_id"),
        serverSequence = requiredLong("server_sequence"),
        occurredAt = requiredString("occurred_at"),
        eventType = SafetyEventType.valueOf(requiredString("event_type")),
        eventSummary = requiredString("event_summary"),
        severity = SafetyEventSeverity.valueOf(requiredString("severity")),
        acknowledgedAt = if (isNull("acknowledged_at")) {
            null
        } else {
            optString("acknowledged_at").takeIf(String::isNotBlank)
        },
        createdAt = requiredString("created_at"),
        imageAvailable = optBoolean("image_available", false),
        imageContentType = optString("image_content_type").takeIf(String::isNotBlank),
        imageByteSize = if (!has("image_byte_size") || isNull("image_byte_size")) {
            null
        } else {
            optLong("image_byte_size")
        },
    )

    private fun JSONObject.toFamilyCommandResult() = FamilyCommandResult(
        commandId = requiredString("command_id"),
        serverSequence = requiredLong("server_sequence"),
        status = requiredString("status"),
        createdAt = requiredString("created_at"),
    )

    private suspend fun post(
        path: String,
        body: JSONObject,
        bearerToken: String? = null,
        idempotencyKey: String? = null,
    ): JSONObject = request("POST", path, body, bearerToken, idempotencyKey)

    private suspend fun put(
        path: String,
        body: JSONObject,
        bearerToken: String? = null,
        idempotencyKey: String? = null,
    ): JSONObject = request("PUT", path, body, bearerToken, idempotencyKey)

    private suspend fun get(path: String, bearerToken: String): JSONObject =
        request("GET", path, body = null, bearerToken = bearerToken, idempotencyKey = null)

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject?,
        bearerToken: String?,
        idempotencyKey: String?,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = try {
            (URL("$apiBaseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doOutput = body != null
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                setRequestProperty("X-Request-ID", UUID.randomUUID().toString())
                bearerToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                idempotencyKey?.let { setRequestProperty("Idempotency-Key", it) }
            }
        } catch (error: IOException) {
            throw connectionFailure(error)
        }

        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                }
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) throw apiFailure(statusCode, responseBody)
            if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        } catch (error: MiddleServerRequestException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw MiddleServerRequestException(
                code = "NETWORK_TIMEOUT",
                userMessage = "连接中台超时，请检查网络后重试。",
                cause = error,
            )
        } catch (error: IOException) {
            throw connectionFailure(error)
        } catch (error: JSONException) {
            throw MiddleServerRequestException(
                code = "INVALID_SERVER_RESPONSE",
                userMessage = "中台返回的数据无法识别，请确认服务版本一致。",
                cause = error,
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun requestBytes(path: String, bearerToken: String): ByteArray =
        withContext(Dispatchers.IO) {
            val connection = (URL("$apiBaseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("Accept", "image/jpeg,image/png")
                setRequestProperty("Authorization", "Bearer $bearerToken")
                setRequestProperty("X-Request-ID", UUID.randomUUID().toString())
            }
            try {
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }.orEmpty()
                    throw apiFailure(statusCode, body)
                }
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_SAFETY_IMAGE_BYTES) {
                    throw MiddleServerRequestException(
                        code = "SAFETY_EVENT_IMAGE_TOO_LARGE",
                        userMessage = "异常图像过大，无法查看。",
                    )
                }
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(
                        declaredLength.coerceIn(0, MAX_SAFETY_IMAGE_BYTES.toLong()).toInt(),
                    )
                    val buffer = ByteArray(DEFAULT_IMAGE_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_SAFETY_IMAGE_BYTES) {
                            throw MiddleServerRequestException(
                                code = "SAFETY_EVENT_IMAGE_TOO_LARGE",
                                userMessage = "异常图像过大，无法查看。",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (error: MiddleServerRequestException) {
                throw error
            } catch (error: IOException) {
                throw connectionFailure(error)
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun requestBinaryUpload(
        path: String,
        bytes: ByteArray,
        contentType: String,
        bearerToken: String,
        idempotencyKey: String,
    ): Unit = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_SAFETY_IMAGE_BYTES)
        require(contentType == "image/jpeg" || contentType == "image/png")
        val connection = (URL("$apiBaseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("Idempotency-Key", idempotencyKey)
            setRequestProperty("X-Request-ID", UUID.randomUUID().toString())
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }.orEmpty()
                throw apiFailure(statusCode, body)
            }
        } catch (error: MiddleServerRequestException) {
            throw error
        } catch (error: IOException) {
            throw connectionFailure(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun apiFailure(statusCode: Int, responseBody: String): MiddleServerRequestException {
        val errorObject = runCatching { JSONObject(responseBody).optJSONObject("error") }.getOrNull()
        val code = errorObject?.optString("code").orEmpty().ifBlank { "HTTP_$statusCode" }
        val message = when (code) {
            "NOT_FOUND" -> "请求的中台接口不存在，请确认服务版本和地址。"
            "ELDER_MOBILE_CONFLICT" -> "该老人手机号已经创建过档案。"
            "BINDING_CREDENTIALS_INVALID" -> "手机号或绑定码不正确，请重新检查。"
            "BINDING_CODE_EXPIRED" -> "绑定码已过期，请让家属重新生成。"
            "BINDING_CODE_USED_OR_REVOKED" -> "绑定码已使用或已失效，请让家属重新生成。"
            "DEVICE_BINDING_CONFLICT" -> "这部手机仍有旧绑定，请等待中台完成设备重新绑定支持。"
            "BINDING_ATTEMPTS_EXCEEDED" -> "尝试次数过多，请稍后再试。"
            "SHARING_CONSENT_REQUIRED" -> "绑定家人前，请先确认共享范围。"
            "AUTHENTICATION_REQUIRED" -> "登录或绑定状态已失效，请重新登录或绑定。"
            "INVALID_COMMAND_CONTENT" -> "通知或提醒内容不正确，请检查后重试。"
            "COMMAND_FORBIDDEN" -> "当前账号没有向这位老人发送内容的权限。"
            "COMMAND_NOT_FOUND" -> "这条通知或提醒已经不存在。"
            "IDEMPOTENCY_CONFLICT" -> "本次提交状态冲突，请返回后重新操作。"
            "BINDING_REVOKED" -> "家庭绑定已解除，暂时不能发送内容。"
            "COMMAND_RATE_LIMITED" -> "发送得太频繁，请稍后再试。"
            "FAMILY_CONTACTS_FORBIDDEN" -> "当前设备无权读取家属联系人。"
            "FAMILY_CONTACTS_UNAVAILABLE" -> "暂时无法同步家属联系人，请稍后重试。"
            "MODEL_CONFIG_NOT_FOUND" -> "尚未设置模型服务。"
            "MODEL_CONFIG_FORBIDDEN" -> "当前账号无权修改这位老人的模型配置。"
            "MODEL_CONFIG_REVISION_CONFLICT" -> "模型配置已被其他家属更新，请刷新后重试。"
            "INVALID_MODEL_CONFIG" -> "模型配置格式不正确，请检查后重试。"
            "USAGE_REFRESH_RATE_LIMITED" -> "刷新得太频繁，请稍等几秒再试。"
            "SAFETY_CONFIG_NOT_FOUND" -> "尚未设置状态检测间隔。"
            "INVALID_SAFETY_ENABLED" -> "状态检测开关信息不正确。"
            "INVALID_SAFETY_INTERVAL" -> "检测间隔必须在 1 到 60 分钟之间。"
            "SAFETY_CONFIG_REVISION_CONFLICT" -> "检测设置已被其他家属更新，请刷新后重试。"
            "INVALID_SAFETY_EVENT" -> "安全事件信息不正确，请重新检测。"
            "INVALID_SAFETY_EVENT_IMAGE" -> "异常图像格式不正确。"
            "SAFETY_EVENT_IMAGE_TOO_LARGE" -> "异常图像过大，无法上传。"
            "SAFETY_EVENT_IMAGE_NOT_FOUND" -> "这条事件暂时没有可查看的图像。"
            "SAFETY_EVENT_NOT_FOUND" -> "这条安全事件已不存在。"
            "SAFETY_EVENT_FORBIDDEN" -> "当前账号无权查看这位老人的安全事件。"
            "REQUEST_VALIDATION_ERROR", "INVALID_MOBILE_FORMAT" -> "提交的信息格式不正确，请检查后重试。"
            else -> errorObject?.optString("message").orEmpty().ifBlank {
                "中台请求失败，请稍后重试。"
            }
        }
        return MiddleServerRequestException(code = code, userMessage = message)
    }

    private fun connectionFailure(error: IOException) = MiddleServerRequestException(
        code = "MIDDLE_SERVER_UNREACHABLE",
        userMessage = "无法连接中台，请确认地址、端口和服务监听设置。",
        cause = error,
    )

    private fun String.isOfflineError(): Boolean =
        this == "MIDDLE_SERVER_UNREACHABLE" || this == "NETWORK_TIMEOUT"

    private fun JSONObject.requiredString(name: String): String {
        val value = optString(name)
        if (value.isBlank()) throw JSONException("Missing response field")
        return value
    }

    private fun JSONObject.requiredLong(name: String): Long {
        if (!has(name)) throw JSONException("Missing response field")
        return getLong(name)
    }

    private fun JSONObject.toModelRuntimeConfiguration(): ModelRuntimeConfiguration {
        val configuration = optJSONObject("configuration") ?: this
        val sampling = configuration.optJSONObject("sampling")
            ?: throw JSONException("Missing response field")
        return ModelRuntimeConfiguration(
            schemaVersion = configuration.optInt(
                "schema_version",
                ModelRuntimeConfiguration.CURRENT_SCHEMA_VERSION,
            ),
            revision = optLong("revision", configuration.optLong("revision", 0)),
            baseUrl = configuration.requiredString("base_url"),
            model = configuration.requiredString("model"),
            dialect = when (configuration.requiredString("dialect").lowercase()) {
                "standard" -> OpenAiCompatibleDialect.Standard
                "llama_cpp" -> OpenAiCompatibleDialect.LlamaCpp
                else -> throw JSONException("Unknown model dialect")
            },
            contextWindowTokens = configuration.optInt(
                "context_window_tokens",
                ModelUsagePolicy.DEFAULT_CONTEXT_WINDOW_TOKENS.toInt(),
            ),
            maxOutputTokens = configuration.getInt("max_output_tokens"),
            temperature = sampling.getDouble("temperature"),
            topP = sampling.getDouble("top_p"),
            topK = sampling.getInt("top_k"),
            updatedAt = optString("updated_at")
                .ifBlank { configuration.optString("updated_at") }
                .takeIf(String::isNotBlank),
        )
    }

    private fun ModelRuntimeConfiguration.toRemoteModelConfigJson() = JSONObject()
        .put("schema_version", schemaVersion)
        .put("base_url", baseUrl)
        .put("model", model)
        .put("dialect", dialect.wireName)
        .put("context_window_tokens", contextWindowTokens)
        .put("max_output_tokens", maxOutputTokens)
        .put(
            "sampling",
            JSONObject()
                .put("temperature", temperature)
                .put("top_p", topP)
                .put("top_k", topK),
        )
        .put("reasoning_enabled", false)

    private fun authenticationRequired(message: String) = MiddleServerRequestException(
        code = "AUTHENTICATION_REQUIRED",
        userMessage = message,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 15_000
        const val MAX_SAFETY_IMAGE_BYTES = 8 * 1024 * 1024
        const val DEFAULT_IMAGE_BUFFER_SIZE = 8 * 1024
    }
}
