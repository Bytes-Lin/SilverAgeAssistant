package com.example.silverageassistant.data.usage

import com.example.silverageassistant.data.middleserver.InMemoryMiddleServerCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ElderUsageRealtimeClientTest {
    @Test
    fun modelConfigurationHint_requestsReliableRestRefresh() {
        var usageRequests = 0
        var modelConfigurationRefreshes = 0
        val client = ElderUsageRealtimeClient(
            serverBaseUrl = "http://127.0.0.1:8765/api/v1",
            credentialStore = InMemoryMiddleServerCredentialStore(),
            onUsageReportRequested = { usageRequests += 1 },
        )
        client.setModelConfigurationListener { modelConfigurationRefreshes += 1 }

        client.dispatchMessageType("MODEL_CONFIG_AVAILABLE")

        assertEquals(1, modelConfigurationRefreshes)
        assertEquals(0, usageRequests)
        client.close()
    }

    @Test
    fun missingOrUnknownMessageTypes_areIgnored() {
        var refreshes = 0
        val client = ElderUsageRealtimeClient(
            serverBaseUrl = "http://127.0.0.1:8765/api/v1",
            credentialStore = InMemoryMiddleServerCredentialStore(),
            onUsageReportRequested = { refreshes += 1 },
        )
        client.setModelConfigurationListener { refreshes += 1 }

        client.dispatchMessageType(null)
        client.dispatchMessageType("SOMETHING_ELSE")

        assertEquals(0, refreshes)
        client.close()
    }

    @Test
    fun socketConnection_requestsCommandRestReconciliation() {
        var commandRefreshes = 0
        val client = ElderUsageRealtimeClient(
            serverBaseUrl = "http://127.0.0.1:8765/api/v1",
            credentialStore = InMemoryMiddleServerCredentialStore(),
            onUsageReportRequested = {},
        )
        client.setCommandAvailableListener { commandRefreshes += 1 }

        client.dispatchConnected()

        assertEquals(1, commandRefreshes)
        client.close()
    }
}
