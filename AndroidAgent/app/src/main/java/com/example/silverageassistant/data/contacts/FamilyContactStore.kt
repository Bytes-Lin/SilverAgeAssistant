package com.example.silverageassistant.data.contacts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.example.silverageassistant.data.middleserver.FamilyContactProfile
import com.example.silverageassistant.data.middleserver.FamilyContactsSyncResult
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface FamilyContactStore {
    suspend fun load(): FamilyContactsSyncResult?

    suspend fun save(snapshot: FamilyContactsSyncResult)

    suspend fun clear()
}

class EncryptedFamilyContactStore(context: Context) : FamilyContactStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): FamilyContactsSyncResult? = withContext(Dispatchers.IO) {
        val encrypted = preferences.getString(CONTACTS_SNAPSHOT, null) ?: return@withContext null
        runCatching { decodeSnapshot(JSONObject(decrypt(encrypted))) }
            .getOrElse {
                preferences.edit { remove(CONTACTS_SNAPSHOT) }
                null
            }
    }

    override suspend fun save(snapshot: FamilyContactsSyncResult) = withContext(Dispatchers.IO) {
        preferences.edit {
            putString(CONTACTS_SNAPSHOT, encrypt(encodeSnapshot(snapshot).toString()))
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        preferences.edit { remove(CONTACTS_SNAPSHOT) }
    }

    private fun encodeSnapshot(snapshot: FamilyContactsSyncResult) = JSONObject()
        .put("version", SNAPSHOT_FORMAT_VERSION)
        .put("snapshot_version", snapshot.snapshotVersion)
        .put("synced_at", snapshot.syncedAt)
        .put(
            "contacts",
            JSONArray().apply {
                snapshot.contacts.forEach { contact ->
                    put(
                        JSONObject()
                            .put("binding_id", contact.bindingId)
                            .put("family_account_id", contact.familyAccountId)
                            .put("display_name", contact.displayName)
                            .put("mobile_number", contact.mobileNumber)
                            .put("relationship", contact.relationship)
                            .put("permissions", JSONArray(contact.permissions))
                            .put("emergency_contact", contact.emergencyContact)
                            .put("bound_at", contact.boundAt),
                    )
                }
            },
        )

    private fun decodeSnapshot(value: JSONObject): FamilyContactsSyncResult {
        require(value.optInt("version") == SNAPSHOT_FORMAT_VERSION)
        val contacts = value.getJSONArray("contacts")
        return FamilyContactsSyncResult(
            contacts = buildList {
                for (index in 0 until contacts.length()) {
                    val contact = contacts.getJSONObject(index)
                    val permissions = contact.getJSONArray("permissions")
                    add(
                        FamilyContactProfile(
                            bindingId = contact.getString("binding_id"),
                            familyAccountId = contact.getString("family_account_id"),
                            displayName = contact.getString("display_name"),
                            mobileNumber = contact.getString("mobile_number"),
                            relationship = contact.getString("relationship"),
                            permissions = buildList {
                                for (permissionIndex in 0 until permissions.length()) {
                                    add(permissions.getString(permissionIndex))
                                }
                            },
                            emergencyContact = contact.getBoolean("emergency_contact"),
                            boundAt = contact.getString("bound_at"),
                        ),
                    )
                }
            },
            snapshotVersion = value.getString("snapshot_version"),
            syncedAt = value.getString("synced_at"),
        )
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, encrypted).joinToString(SEPARATOR) {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(SEPARATOR, limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val SNAPSHOT_FORMAT_VERSION = 1
        const val PREFERENCES_NAME = "silverage_family_contacts"
        const val CONTACTS_SNAPSHOT = "encrypted_contacts_snapshot"
        const val KEY_ALIAS = "silverage_family_contacts_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val SEPARATOR = "."
    }
}

class InMemoryFamilyContactStore(
    initialSnapshot: FamilyContactsSyncResult? = null,
) : FamilyContactStore {
    var snapshot: FamilyContactsSyncResult? = initialSnapshot
        private set

    override suspend fun load(): FamilyContactsSyncResult? = snapshot

    override suspend fun save(snapshot: FamilyContactsSyncResult) {
        this.snapshot = snapshot
    }

    override suspend fun clear() {
        snapshot = null
    }
}
