package com.example.silverageassistant.data.middleserver

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class HttpOnboardingMiddleServerRepository(
    serverBaseUrl: String,
    private val credentialStore: MiddleServerCredentialStore,
    private val deviceId: String,
    private val deviceName: String,
) : OnboardingMiddleServerRepository,
    FamilyCommunicationRepository,
    ElderCommandRepository,
    ElderFamilyContactsRepository {
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

    private suspend fun <T> withFamilyAccessToken(block: suspend (String) -> T): T {
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

    private fun apiFailure(statusCode: Int, responseBody: String): MiddleServerRequestException {
        val errorObject = runCatching { JSONObject(responseBody).optJSONObject("error") }.getOrNull()
        val code = errorObject?.optString("code").orEmpty().ifBlank { "HTTP_$statusCode" }
        val message = when (code) {
            "NOT_FOUND" -> "请求的中台接口不存在，请确认服务版本和地址。"
            "ELDER_MOBILE_CONFLICT" -> "该老人手机号已经创建过档案。"
            "BINDING_CREDENTIALS_INVALID" -> "手机号或绑定码不正确，请重新检查。"
            "BINDING_CODE_EXPIRED" -> "绑定码已过期，请让家属重新生成。"
            "BINDING_CODE_USED_OR_REVOKED" -> "绑定码已使用或已失效，请让家属重新生成。"
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

    private fun authenticationRequired(message: String) = MiddleServerRequestException(
        code = "AUTHENTICATION_REQUIRED",
        userMessage = message,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}
