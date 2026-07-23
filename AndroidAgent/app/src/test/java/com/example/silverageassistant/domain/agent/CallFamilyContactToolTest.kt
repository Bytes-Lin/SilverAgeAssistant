package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.data.contacts.InMemoryFamilyContactStore
import com.example.silverageassistant.data.middleserver.FamilyContactProfile
import com.example.silverageassistant.data.middleserver.FamilyContactsSyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFamilyContactToolTest {
    @Test
    fun uniqueRelationship_preparesLocalConfirmationWithoutExposingPhoneNumber() = runBlocking {
        val phoneNumber = "13800138000"
        val pendingCoordinator = PendingPhoneCallCoordinator()
        val tool = tool(
            contacts = listOf(contact("binding-1", "小林", "CHILD", phoneNumber)),
            pendingCoordinator = pendingCoordinator,
        )

        val result = tool.execute("""{"relationship":"儿子"}""")

        assertTrue(result.contains("USER_CONFIRMATION_REQUIRED"))
        assertFalse(result.contains(phoneNumber))
        assertEquals("小林", pendingCoordinator.pending.value?.displayName)
        assertFalse(pendingCoordinator.pending.value.toString().contains(phoneNumber))
    }

    @Test
    fun ambiguousRelationship_doesNotChooseOrPrepareAPhoneNumber() = runBlocking {
        val pendingCoordinator = PendingPhoneCallCoordinator()
        val tool = tool(
            contacts = listOf(
                contact("binding-1", "小林", "CHILD", "13800138000"),
                contact("binding-2", "小王", "CHILD", "13900139000"),
            ),
            pendingCoordinator = pendingCoordinator,
        )

        val result = tool.execute("""{"relationship":"子女"}""")

        assertTrue(result.contains("CONTACT_AMBIGUOUS"))
        assertFalse(result.contains("13800138000"))
        assertFalse(result.contains("13900139000"))
        assertNull(pendingCoordinator.pending.value)
    }

    @Test
    fun confirmedRequest_passesStoredNumberOnlyToLocalLauncher() {
        val pendingCoordinator = PendingPhoneCallCoordinator()
        val contact = contact("binding-1", "小林", "CHILD", "13800138000")
        var launchedNumber: String? = null
        var launchedDirectly = false
        pendingCoordinator.prepare(contact)
        val requestId = requireNotNull(pendingCoordinator.pending.value).requestId

        val launched = pendingCoordinator.launch(requestId, direct = true) { number, direct ->
            launchedNumber = number
            launchedDirectly = direct
        }

        assertTrue(launched)
        assertEquals("13800138000", launchedNumber)
        assertTrue(launchedDirectly)
        assertNull(pendingCoordinator.pending.value)
    }

    private fun tool(
        contacts: List<FamilyContactProfile>,
        pendingCoordinator: PendingPhoneCallCoordinator,
    ) = CallFamilyContactTool(
        contactStore = InMemoryFamilyContactStore(
            FamilyContactsSyncResult(
                contacts = contacts,
                snapshotVersion = "1",
                syncedAt = "2026-07-22T00:00:00Z",
            ),
        ),
        pendingCallCoordinator = pendingCoordinator,
    )

    private fun contact(
        bindingId: String,
        displayName: String,
        relationship: String,
        mobileNumber: String,
    ) = FamilyContactProfile(
        bindingId = bindingId,
        familyAccountId = "family-$bindingId",
        displayName = displayName,
        mobileNumber = mobileNumber,
        relationship = relationship,
        permissions = emptyList(),
        emergencyContact = false,
        boundAt = "2026-07-22T00:00:00Z",
    )
}
