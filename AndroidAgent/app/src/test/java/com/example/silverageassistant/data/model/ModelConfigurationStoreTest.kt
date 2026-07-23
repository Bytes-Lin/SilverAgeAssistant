package com.example.silverageassistant.data.model

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigurationStoreTest {
    @Test
    fun configuration_roundTripsAsJsonWithoutApiKey() = runBlocking {
        val file = Files.createTempDirectory("model-config")
            .resolve("model-config.json")
            .toFile()
        val defaults = configuration(baseUrl = "http://default:11435", model = "default")
        val firstStore = JsonModelConfigurationStore(file, defaults, allowCleartextHttp = true)
        val saved = configuration(
            baseUrl = "http://58.199.163.98:11435",
            model = "qwen3_5",
        ).copy(
            revision = 3,
            contextWindowTokens = 65536,
            maxOutputTokens = 768,
            temperature = 0.7,
        )

        firstStore.save(saved)
        val secondStore = JsonModelConfigurationStore(file, defaults, allowCleartextHttp = true)
        secondStore.initialize()

        assertEquals(saved, secondStore.configuration.value)
        val json = file.readText()
        assertTrue(json.contains("\"reasoning_enabled\": false"))
        assertFalse(json.contains("api_key", ignoreCase = true))
        assertFalse(json.contains("verification_token", ignoreCase = true))
    }

    @Test
    fun legacyConfiguration_withoutContextWindow_usesCompatibleDefault() = runBlocking {
        val file = Files.createTempDirectory("legacy-model-config")
            .resolve("model-config.json")
            .toFile()
        file.writeText(
            """
            {
              "schema_version": 1,
              "revision": 2,
              "base_url": "http://58.199.163.98:11435",
              "model": "qwen3_5",
              "dialect": "llama_cpp",
              "max_output_tokens": 512,
              "sampling": {
                "temperature": 0.6,
                "top_p": 0.9,
                "top_k": 40
              },
              "reasoning_enabled": false
            }
            """.trimIndent(),
        )
        val store = JsonModelConfigurationStore(
            file,
            configuration("http://default:11435", "default"),
            allowCleartextHttp = true,
        )

        store.initialize()

        assertEquals(32768, store.configuration.value.contextWindowTokens)
    }

    @Test
    fun releaseConfiguration_rejectsCleartextHttp() {
        val configuration = configuration(
            baseUrl = "http://model.example.com",
            model = "cloud-model",
        )

        assertThrows(IllegalArgumentException::class.java) {
            configuration.validate(allowCleartextHttp = false)
        }
    }

    private fun configuration(baseUrl: String, model: String) = ModelRuntimeConfiguration(
        baseUrl = baseUrl,
        model = model,
        dialect = OpenAiCompatibleDialect.LlamaCpp,
    )
}
