package com.xiaoqi.companion.data.repository

import app.cash.turbine.test
import androidx.datastore.preferences.core.edit
import com.xiaoqi.companion.core.llm.ConnectivityResult
import com.xiaoqi.companion.core.llm.LlmConnectivityChecker
import com.xiaoqi.companion.data.datastore.AppPreferences
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.data.db.converter.ThemeMode
import com.xiaoqi.companion.testing.InMemoryPreferencesDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConfigRepositoryTest {

    @Test
    fun openAi_preservesCustomModelAndDoesNotReuseAnotherProvidersKey() = runTest {
        for (keys in listOf("{}", """{"OPENAI_COMPATIBLE":"openai-key"}""")) {
            val prefs: AppPreferences = mockk {
                every { apiKey } returns flowOf("legacy-glm-key")
                every { apiKeysJson } returns flowOf(keys)
                every { llmProvider } returns flowOf(LlmProvider.OPENAI_COMPATIBLE)
                every { modelName } returns flowOf("vendor/custom-model")
                every { baseUrl } returns flowOf("https://example.test/proxy/v1/")
            }
            buildRepo(prefs).getCurrentLlmConfig().test {
                val config = awaitItem()
                assertEquals("vendor/custom-model", config.modelName)
                assertEquals("https://example.test/proxy/v1/", config.baseUrl)
                assertEquals(if (keys == "{}") "" else "openai-key", config.apiKey)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun savingOpenAiKeyDoesNotChangeTheLegacyPresetKey() = runTest {
        val prefs: AppPreferences = mockk(relaxed = true) {
            every { llmProvider } returns flowOf(LlmProvider.OPENAI_COMPATIBLE)
        }
        buildRepo(prefs).setApiKey("openai-key")
        coVerify { prefs.setApiKeyForProvider(LlmProvider.OPENAI_COMPATIBLE, "openai-key") }
        coVerify(exactly = 0) { prefs.setApiKey(any()) }
    }

    private fun buildRepo(
        prefs: AppPreferences,
        checker: LlmConnectivityChecker = mockk(relaxed = true),
    ): ConfigRepositoryImpl = ConfigRepositoryImpl(prefs, checker)

    @Test
    fun getCurrentLlmConfig_returnsCombinedConfig() = runTest {
        val prefs: AppPreferences = mockk {
            every { apiKey } returns flowOf("test-key-123")
            every { apiKeysJson } returns flowOf("""{"GLM":"test-key-123"}""")
            every { llmProvider } returns flowOf(LlmProvider.GLM)
            every { modelName } returns flowOf("glm-5v-turbo")
            every { baseUrl } returns flowOf("https://example.test/v1")
        }

        val repo = buildRepo(prefs)

        repo.getCurrentLlmConfig().test {
            val config = awaitItem()
            assertEquals(LlmProvider.GLM, config.provider)
            assertEquals("test-key-123", config.apiKey)
            assertEquals("https://example.test/v1", config.baseUrl)
            assertEquals("glm-5v-turbo", config.modelName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getCurrentLlmConfig_kimiProvider() = runTest {
        val prefs: AppPreferences = mockk {
            every { apiKey } returns flowOf("kimi-key")
            every { apiKeysJson } returns flowOf("""{"KIMI":"kimi-key"}""")
            every { llmProvider } returns flowOf(LlmProvider.KIMI)
            every { modelName } returns flowOf(DefaultLlmValues.KIMI_MODEL)
            every { baseUrl } returns flowOf("")
        }

        val repo = buildRepo(prefs)

        repo.getCurrentLlmConfig().test {
            val config = awaitItem()
            assertEquals(LlmProvider.KIMI, config.provider)
            assertEquals(DefaultLlmValues.KIMI_BASE_URL, config.baseUrl)
            assertEquals(DefaultLlmValues.KIMI_MODEL, config.modelName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getCurrentLlmConfig_modelScopeProvider_usesAnthropicCompatibleEndpoint() = runTest {
        val prefs: AppPreferences = mockk {
            every { apiKey } returns flowOf("ms-test-token")
            every { apiKeysJson } returns flowOf("""{"MODELSCOPE":"ms-test-token"}""")
            every { llmProvider } returns flowOf(LlmProvider.MODELSCOPE)
            every { modelName } returns flowOf(DefaultLlmValues.MODELSCOPE_MODEL)
            every { baseUrl } returns flowOf("https://stale.example.com/v1")
        }

        val repo = buildRepo(prefs)

        repo.getCurrentLlmConfig().test {
            val config = awaitItem()
            assertEquals(LlmProvider.MODELSCOPE, config.provider)
            assertEquals("https://stale.example.com/v1", config.baseUrl)
            assertEquals(DefaultLlmValues.MODELSCOPE_MODEL, config.modelName)
            assertEquals("ms-test-token", config.apiKey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getCurrentLlmConfig_usesDefaultBaseUrl_whenStoredBaseUrlBlank() = runTest {
        val prefs: AppPreferences = mockk {
            every { apiKey } returns flowOf("key")
            every { apiKeysJson } returns flowOf("""{"GLM":"key"}""")
            every { llmProvider } returns flowOf(LlmProvider.GLM)
            every { modelName } returns flowOf(DefaultLlmValues.GLM_MODEL)
            every { baseUrl } returns flowOf("")
        }

        val repo = buildRepo(prefs)

        repo.getCurrentLlmConfig().test {
            val config = awaitItem()
            assertEquals(DefaultLlmValues.GLM_BASE_URL, config.baseUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getCurrentLlmConfig_retiredLocalQwenInDataStore_resolvesToDefaultProvider() = runTest {
        // 升级用户的 DataStore 里仍写着已下线的 LOCAL_QWEN:必须反解成默认 provider 而不是抛异常。
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.edit {
            it[AppPreferences.Keys.llmProvider] = "LOCAL_QWEN"
            it[AppPreferences.Keys.modelName] = "Qwen3.5-0.8B-MNN"
        }
        val prefs = AppPreferences(dataStore)

        buildRepo(prefs).getCurrentLlmConfig().test {
            val config = awaitItem()
            assertEquals(LlmProvider.GLM, config.provider)
            assertEquals(DefaultLlmValues.GLM_BASE_URL, config.baseUrl)
            assertEquals(DefaultLlmValues.GLM_MODEL, config.modelName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeLlmConfigStatus_withoutApiKey_isNotReady() = runTest {
        val prefs: AppPreferences = mockk {
            every { apiKey } returns flowOf(null)
            every { apiKeysJson } returns flowOf("{}")
            every { llmProvider } returns flowOf(LlmProvider.GLM)
            every { modelName } returns flowOf(DefaultLlmValues.GLM_MODEL)
            every { baseUrl } returns flowOf("")
        }

        val repo = buildRepo(prefs)

        repo.observeLlmConfigStatus().test {
            val status = awaitItem()
            assertEquals(LlmProvider.GLM, status.provider)
            assertEquals(false, status.hasApiKey)
            assertEquals(false, status.isReady)
            assertEquals("缺失 API Key", status.missingReason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeLlmConfigStatus_readyWhenKeyAndEndpointsPresent() = runTest {
        val prefs: AppPreferences = mockk {
            every { apiKey } returns flowOf("glm-key")
            every { apiKeysJson } returns flowOf("""{"GLM":"glm-key"}""")
            every { llmProvider } returns flowOf(LlmProvider.GLM)
            every { modelName } returns flowOf(DefaultLlmValues.GLM_MODEL)
            every { baseUrl } returns flowOf("https://open.bigmodel.cn/api/anthropic")
        }

        val repo = buildRepo(prefs)

        repo.observeLlmConfigStatus().test {
            val status = awaitItem()
            assertEquals(true, status.isReady)
            assertEquals(null, status.missingReason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getCurrentLlmConfig_perProviderKey_takesPrecedenceOverLegacy() = runTest {
        val prefs: AppPreferences = mockk {
            every { apiKey } returns flowOf("old-legacy-key")
            every { apiKeysJson } returns flowOf("""{"GLM":"new-per-provider-key"}""")
            every { llmProvider } returns flowOf(LlmProvider.GLM)
            every { modelName } returns flowOf(DefaultLlmValues.GLM_MODEL)
            every { baseUrl } returns flowOf("")
        }

        val repo = buildRepo(prefs)

        repo.getCurrentLlmConfig().test {
            val config = awaitItem()
            assertEquals("new-per-provider-key", config.apiKey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getCurrentLlmConfig_fallsBackToLegacyKey_whenPerProviderEmpty() = runTest {
        val prefs: AppPreferences = mockk {
            every { apiKey } returns flowOf("legacy-fallback")
            every { apiKeysJson } returns flowOf("{}")
            every { llmProvider } returns flowOf(LlmProvider.GLM)
            every { modelName } returns flowOf(DefaultLlmValues.GLM_MODEL)
            every { baseUrl } returns flowOf("")
        }

        val repo = buildRepo(prefs)

        repo.getCurrentLlmConfig().test {
            val config = awaitItem()
            assertEquals("legacy-fallback", config.apiKey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun themeMode_flowDelegatesToPrefs() = runTest {
        val prefs: AppPreferences = mockk {
            every { themeMode } returns flowOf(ThemeMode.DARK)
        }

        val repo = buildRepo(prefs)
        repo.themeMode.test {
            assertEquals(ThemeMode.DARK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setApiKey_savesPerProviderAndLegacy() = runTest {
        val prefs: AppPreferences = mockk(relaxed = true) {
            every { llmProvider } returns flowOf(LlmProvider.GLM)
            coEvery { setApiKey(any()) } returns Unit
            coEvery { setApiKeyForProvider(any(), any()) } returns Unit
        }

        val repo = buildRepo(prefs)
        repo.setApiKey("new-key")

        coVerify { prefs.setApiKeyForProvider(LlmProvider.GLM, "new-key") }
        coVerify { prefs.setApiKey("new-key") }
    }

    @Test
    fun setLlmProvider_delegatesToPrefs() = runTest {
        val prefs: AppPreferences = mockk(relaxed = true) {
            coEvery { setLlmProvider(any()) } returns Unit
        }

        val repo = buildRepo(prefs)
        repo.setLlmProvider(LlmProvider.KIMI)
    }

    @Test
    fun setModelName_delegatesToPrefs() = runTest {
        val prefs: AppPreferences = mockk(relaxed = true) {
            coEvery { setModelName(any()) } returns Unit
        }

        val repo = buildRepo(prefs)
        repo.setModelName("kimi-latest")
    }

    @Test
    fun setBaseUrl_delegatesToPrefs() = runTest {
        val prefs: AppPreferences = mockk(relaxed = true) {
            coEvery { setBaseUrl(any()) } returns Unit
        }

        val repo = buildRepo(prefs)
        repo.setBaseUrl("https://example.test/v1")

        coVerify { prefs.setBaseUrl("https://example.test/v1") }
    }

    @Test
    fun checkConnectivity_delegatesToChecker() = runTest {
        val prefs: AppPreferences = mockk {
            every { apiKey } returns flowOf("key-abc")
            every { apiKeysJson } returns flowOf("""{"GLM":"key-abc"}""")
            every { llmProvider } returns flowOf(LlmProvider.GLM)
            every { modelName } returns flowOf("glm-5v-turbo")
            every { baseUrl } returns flowOf("")
        }
        val expected = ConnectivityResult.Success(latencyMs = 123L, modelName = "glm-5v-turbo")
        val checker: LlmConnectivityChecker = mockk {
            coEvery { check(any()) } returns expected
        }
        val repo = buildRepo(prefs, checker)

        val actual = repo.checkConnectivity()

        assertSame(expected, actual)
    }

    @Test
    fun checkConnectivity_propagatesUnreachable() = runTest {
        val prefs: AppPreferences = mockk {
            every { apiKey } returns flowOf("k")
            every { apiKeysJson } returns flowOf("""{"GLM":"k"}""")
            every { llmProvider } returns flowOf(LlmProvider.GLM)
            every { modelName } returns flowOf("glm-5v-turbo")
            every { baseUrl } returns flowOf("")
        }
        val expected = ConnectivityResult.Unreachable("timeout")
        val checker: LlmConnectivityChecker = mockk {
            coEvery { check(any()) } returns expected
        }
        val repo = buildRepo(prefs, checker)

        val actual = repo.checkConnectivity()

        assertSame(expected, actual)
    }
}
