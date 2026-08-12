package com.example.silverageassistant.data.safety

import com.example.silverageassistant.data.middleserver.InMemoryMiddleServerCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Test

class FamilySafetyRealtimeClientTest {
    @Test
    fun reminderStatusHint_refreshesReminderHistoryOnly() {
        var safetyRefreshes = 0
        var reminderRefreshes = 0
        val client = FamilySafetyRealtimeClient(
            serverBaseUrl = "http://127.0.0.1:8765/api/v1",
            credentialStore = InMemoryMiddleServerCredentialStore(),
            onSafetyEventAvailable = { safetyRefreshes += 1 },
            onReminderStatusAvailable = { reminderRefreshes += 1 },
        )

        client.dispatchMessageType("REMINDER_STATUS_CHANGED")

        assertEquals(0, safetyRefreshes)
        assertEquals(1, reminderRefreshes)
        client.close()
    }

    @Test
    fun socketConnection_reconcilesFamilySnapshots() {
        var safetyRefreshes = 0
        var reminderRefreshes = 0
        val client = FamilySafetyRealtimeClient(
            serverBaseUrl = "http://127.0.0.1:8765/api/v1",
            credentialStore = InMemoryMiddleServerCredentialStore(),
            onSafetyEventAvailable = { safetyRefreshes += 1 },
            onReminderStatusAvailable = { reminderRefreshes += 1 },
        )

        client.dispatchConnected()

        assertEquals(1, safetyRefreshes)
        assertEquals(1, reminderRefreshes)
        client.close()
    }
}
