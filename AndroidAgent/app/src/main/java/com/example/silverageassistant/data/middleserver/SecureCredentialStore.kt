package com.example.silverageassistant.data.middleserver

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FamilySession(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: String,
)

interface MiddleServerCredentialStore {
    suspend fun saveFamilySession(session: FamilySession)
    suspend fun saveDeviceCredential(credential: String)
    suspend fun loadFamilySession(): FamilySession?
    suspend fun loadDeviceCredential(): String?
}

class AndroidKeystoreCredentialStore(context: Context) : MiddleServerCredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun saveFamilySession(session: FamilySession) = withContext(Dispatchers.IO) {
        preferences.edit {
            putString(ACCESS_TOKEN, encrypt(session.accessToken))
            putString(REFRESH_TOKEN, encrypt(session.refreshToken))
            putString(ACCESS_TOKEN_EXPIRES_AT, session.accessTokenExpiresAt)
        }
    }

    override suspend fun saveDeviceCredential(credential: String) = withContext(Dispatchers.IO) {
        preferences.edit { putString(DEVICE_CREDENTIAL, encrypt(credential)) }
    }

    override suspend fun loadFamilySession(): FamilySession? = withContext(Dispatchers.IO) {
        val accessToken = preferences.getString(ACCESS_TOKEN, null)?.let(::decrypt) ?: return@withContext null
        val refreshToken = preferences.getString(REFRESH_TOKEN, null)?.let(::decrypt) ?: return@withContext null
        FamilySession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresAt = preferences.getString(ACCESS_TOKEN_EXPIRES_AT, "").orEmpty(),
        )
    }

    override suspend fun loadDeviceCredential(): String? = withContext(Dispatchers.IO) {
        preferences.getString(DEVICE_CREDENTIAL, null)?.let(::decrypt)
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
        require(parts.size == 2) { "Invalid encrypted credential" }
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
        const val PREFERENCES_NAME = "silverage_secure_credentials"
        const val ACCESS_TOKEN = "family_access_token"
        const val REFRESH_TOKEN = "family_refresh_token"
        const val ACCESS_TOKEN_EXPIRES_AT = "family_access_token_expires_at"
        const val DEVICE_CREDENTIAL = "elder_device_credential"
        const val KEY_ALIAS = "silverage_middle_server_credentials_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val SEPARATOR = "."
    }
}
