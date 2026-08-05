package com.example.silverageassistant.data.model

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.modelCredentialDataStore by preferencesDataStore(
    name = "silverage_model_credentials",
)

interface ModelApiCredentialStore {
    suspend fun saveApiKey(apiKey: String)
    suspend fun loadApiKey(): String?
    suspend fun clearApiKey()
}

interface VoiceApiCredentialStore {
    suspend fun saveVoiceApiKey(apiKey: String)
    suspend fun loadVoiceApiKey(): String?
    suspend fun clearVoiceApiKey()
}

class AndroidKeystoreModelApiCredentialStore(
    context: Context,
) : ModelApiCredentialStore {
    private val dataStore = context.applicationContext.modelCredentialDataStore

    override suspend fun saveApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        withContext(Dispatchers.IO) {
            dataStore.edit { it[API_KEY] = encrypt(apiKey) }
        }
    }

    override suspend fun loadApiKey(): String? = withContext(Dispatchers.IO) {
        dataStore.data.first()[API_KEY]?.let(::decrypt)
    }

    override suspend fun clearApiKey() {
        withContext(Dispatchers.IO) {
            dataStore.edit { it.remove(API_KEY) }
        }
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
        require(parts.size == 2) { "Invalid encrypted API key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
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
        val API_KEY = stringPreferencesKey("model_api_key")
        const val KEY_ALIAS = "silverage_model_api_key_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val SEPARATOR = "."
    }
}

class AndroidKeystoreVoiceApiCredentialStore(
    context: Context,
) : VoiceApiCredentialStore {
    private val dataStore = context.applicationContext.modelCredentialDataStore

    override suspend fun saveVoiceApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "Voice API key must not be blank" }
        withContext(Dispatchers.IO) {
            dataStore.edit { it[VOICE_API_KEY] = encrypt(apiKey) }
        }
    }

    override suspend fun loadVoiceApiKey(): String? = withContext(Dispatchers.IO) {
        dataStore.data.first()[VOICE_API_KEY]?.let(::decrypt)
    }

    override suspend fun clearVoiceApiKey() {
        withContext(Dispatchers.IO) {
            dataStore.edit { it.remove(VOICE_API_KEY) }
        }
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
        require(parts.size == 2) { "Invalid encrypted voice API key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
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
        val VOICE_API_KEY = stringPreferencesKey("voice_api_key")
        const val KEY_ALIAS = "silverage_voice_api_key_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val SEPARATOR = "."
    }
}

class InMemoryModelApiCredentialStore(
    private var apiKey: String? = null,
) : ModelApiCredentialStore {
    override suspend fun saveApiKey(apiKey: String) {
        this.apiKey = apiKey
    }

    override suspend fun loadApiKey(): String? = apiKey

    override suspend fun clearApiKey() {
        apiKey = null
    }
}

class InMemoryVoiceApiCredentialStore(
    private var apiKey: String? = null,
) : VoiceApiCredentialStore {
    override suspend fun saveVoiceApiKey(apiKey: String) {
        this.apiKey = apiKey
    }

    override suspend fun loadVoiceApiKey(): String? = apiKey

    override suspend fun clearVoiceApiKey() {
        apiKey = null
    }
}
