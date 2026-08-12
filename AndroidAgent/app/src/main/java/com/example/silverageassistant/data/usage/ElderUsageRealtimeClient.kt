package com.example.silverageassistant.data.usage

import com.example.silverageassistant.data.middleserver.MiddleServerCredentialStore
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class ElderUsageRealtimeClient(
    serverBaseUrl: String,
    private val credentialStore: MiddleServerCredentialStore,
    private val onUsageReportRequested: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val webSocketUrl = serverBaseUrl.toWebSocketUrl()
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()
    private val active = AtomicBoolean(false)

    @Volatile
    private var onSafetyMonitoringConfigurationAvailable: (() -> Unit)? = null

    @Volatile
    private var onModelConfigurationAvailable: (() -> Unit)? = null

    @Volatile
    private var onCommandAvailable: (() -> Unit)? = null

    @Volatile
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null

    fun start() {
        if (webSocketUrl.isBlank() || !active.compareAndSet(false, true)) return
        connect()
    }

    fun setSafetyMonitoringConfigurationListener(listener: (() -> Unit)?) {
        onSafetyMonitoringConfigurationAvailable = listener
    }

    /**
     * Registers the reliable-REST refresh triggered by a minimal WebSocket hint.
     * The event never carries model endpoints, parameters, or credentials itself.
     */
    fun setModelConfigurationListener(listener: (() -> Unit)?) {
        onModelConfigurationAvailable = listener
    }

    fun setCommandAvailableListener(listener: (() -> Unit)?) {
        onCommandAvailable = listener
    }

    fun stop() {
        active.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, null)
        webSocket = null
    }

    fun close() {
        stop()
        client.dispatcher.executorService.shutdown()
        scope.cancel()
    }

    private fun connect() {
        scope.launch {
            val credential = runCatching { credentialStore.loadDeviceCredential() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: run {
                    active.set(false)
                    return@launch
                }
            if (!active.get()) return@launch
            val request = Request.Builder()
                .url(webSocketUrl)
                .header("Authorization", "Bearer $credential")
                .build()
            webSocket = client.newWebSocket(request, Listener())
        }
    }

    private fun scheduleReconnect() {
        if (!active.get() || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MILLIS)
            if (active.get()) connect()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // A fresh connection may follow an offline period. Reconcile reliable REST data once.
            dispatchConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            dispatchServerMessage(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (this@ElderUsageRealtimeClient.webSocket === webSocket) {
                this@ElderUsageRealtimeClient.webSocket = null
            }
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (this@ElderUsageRealtimeClient.webSocket === webSocket) {
                this@ElderUsageRealtimeClient.webSocket = null
            }
            scheduleReconnect()
        }
    }

    internal fun dispatchServerMessage(text: String) {
        val messageType = runCatching {
            JSONObject(text).optString("message_type")
        }.getOrNull()
        dispatchMessageType(messageType)
    }

    internal fun dispatchConnected() {
        onCommandAvailable?.let { listener -> runCatching(listener) }
    }

    internal fun dispatchMessageType(messageType: String?) {
        if (messageType == USAGE_REPORT_REQUESTED) {
            runCatching(onUsageReportRequested)
        } else if (messageType == SAFETY_CONFIG_AVAILABLE) {
            onSafetyMonitoringConfigurationAvailable?.let { listener ->
                runCatching(listener)
            }
        } else if (messageType == MODEL_CONFIG_AVAILABLE) {
            onModelConfigurationAvailable?.let { listener ->
                runCatching(listener)
            }
        } else if (messageType == COMMAND_AVAILABLE) {
            onCommandAvailable?.let { listener -> runCatching(listener) }
        }
    }

    private fun String.toWebSocketUrl(): String {
        if (isBlank()) return ""
        val apiRoot = trimEnd('/').removeSuffix("/api/v1")
        val uri = runCatching { URI(apiRoot) }.getOrNull() ?: return ""
        val scheme = when (uri.scheme?.lowercase()) {
            "http" -> "ws"
            "https" -> "wss"
            else -> return ""
        }
        return URI(
            scheme,
            null,
            uri.host,
            uri.port,
            "/api/v1/ws",
            null,
            null,
        ).toString()
    }

    private companion object {
        const val USAGE_REPORT_REQUESTED = "MODEL_USAGE_REPORT_REQUESTED"
        const val SAFETY_CONFIG_AVAILABLE = "SAFETY_MONITORING_CONFIG_AVAILABLE"
        const val MODEL_CONFIG_AVAILABLE = "MODEL_CONFIG_AVAILABLE"
        const val COMMAND_AVAILABLE = "COMMAND_AVAILABLE"
        const val PING_INTERVAL_SECONDS = 30L
        const val RECONNECT_DELAY_MILLIS = 5_000L
    }
}
