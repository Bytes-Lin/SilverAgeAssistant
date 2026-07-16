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
) : OnboardingMiddleServerRepository {
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

    private suspend fun post(
        path: String,
        body: JSONObject,
        bearerToken: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = try {
            (URL("$apiBaseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Request-ID", UUID.randomUUID().toString())
                bearerToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
        } catch (error: IOException) {
            throw connectionFailure(error)
        }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
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
            "AUTHENTICATION_REQUIRED" -> "家属登录状态已失效，请重新提交家属信息。"
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

    private fun JSONObject.requiredString(name: String): String {
        val value = optString(name)
        if (value.isBlank()) throw JSONException("Missing response field")
        return value
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}
