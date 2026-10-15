package com.xiaoqi.companion.core.config

import com.xiaoqi.companion.data.datastore.AppPreferences
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.data.repository.DefaultLlmValues
import com.xiaoqi.companion.data.repository.McpServerListRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugConfigSeederTest {
    @Test
    fun forcingProviderChangeResetsEndpointAndModelTogether() = runTest {
        val provider = MutableStateFlow(LlmProvider.OPENAI_COMPATIBLE)
        val baseUrl = MutableStateFlow("https://old-gateway.test/v1")
        val model = MutableStateFlow("old-model")
        val prefs = mockk<AppPreferences>(relaxed = true) {
            every { llmProvider } returns provider
            every { apiKeysJson } returns MutableStateFlow("{}")
            every { modelName } returns model
            every { this@mockk.baseUrl } returns baseUrl
        }
        coEvery { prefs.setLlmProvider(any()) } coAnswers { provider.value = firstArg() }
        coEvery { prefs.setBaseUrl(any()) } coAnswers { baseUrl.value = firstArg() }
        coEvery { prefs.setModelName(any()) } coAnswers { model.value = firstArg() }
        val seeder = DebugConfigSeeder(prefs, mockk<McpServerListRepository>(relaxed = true))

        seeder.seedLlm(true, LlmProvider.MODELSCOPE, "next-model", "")
        assertEquals(LlmProvider.MODELSCOPE, provider.value)
        assertEquals(DefaultLlmValues.MODELSCOPE_BASE_URL, baseUrl.value)
        assertEquals("next-model", model.value)

        seeder.seedLlm(true, LlmProvider.OPENAI_COMPATIBLE, "vendor/model", "https://new-gateway.test/proxy/v1/")
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, provider.value)
        assertEquals("https://new-gateway.test/proxy/v1/", baseUrl.value)
        assertEquals("vendor/model", model.value)

        seeder.seedLlm(false, LlmProvider.KIMI, "ignored", "")
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, provider.value)
        assertEquals("https://new-gateway.test/proxy/v1/", baseUrl.value)
        assertEquals("vendor/model", model.value)
    }
}
