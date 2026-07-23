package com.example.silverageassistant.data.safety

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

class FamilySafetyRealtimeClient(
    serverBaseUrl: String,
    private val credentialStore: MiddleServerCredentialStore,
    private val onSafetyEventAvailable: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val webSocketUrl = serverBaseUrl.toWebSocketUrl()
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()
    private val active = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null

    fun start() {
        if (webSocketUrl.isBlank() || !active.compareAndSet(false, true)) return
        connect()
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
            val accessToken = runCatching { credentialStore.loadFamilySession()?.accessToken }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: run {
                    active.set(false)
                    return@launch
                }
            if (!active.get()) return@launch
            webSocket = client.newWebSocket(
                Request.Builder()
                    .url(webSocketUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .build(),
                Listener(),
            )
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
        override fun onMessage(webSocket: WebSocket, text: String) {
            val messageType = runCatching {
                JSONObject(text).optString("message_type")
            }.getOrNull()
            if (
                messageType == SAFETY_EVENT_AVAILABLE ||
                messageType == SAFETY_EVENT_IMAGE_AVAILABLE
            ) {
                runCatching(onSafetyEventAvailable)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (this@FamilySafetyRealtimeClient.webSocket === webSocket) {
                this@FamilySafetyRealtimeClient.webSocket = null
            }
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (this@FamilySafetyRealtimeClient.webSocket === webSocket) {
                this@FamilySafetyRealtimeClient.webSocket = null
            }
            // REST remains the source of truth and also refreshes an expired family session.
            runCatching(onSafetyEventAvailable)
            scheduleReconnect()
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
        return URI(scheme, null, uri.host, uri.port, "/api/v1/ws", null, null).toString()
    }

    private companion object {
        const val SAFETY_EVENT_AVAILABLE = "SAFETY_EVENT_AVAILABLE"
        const val SAFETY_EVENT_IMAGE_AVAILABLE = "SAFETY_EVENT_IMAGE_AVAILABLE"
        const val PING_INTERVAL_SECONDS = 30L
        const val RECONNECT_DELAY_MILLIS = 5_000L
    }
}
