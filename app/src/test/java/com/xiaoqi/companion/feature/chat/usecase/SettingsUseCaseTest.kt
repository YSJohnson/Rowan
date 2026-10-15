package com.xiaoqi.companion.feature.chat.usecase

import com.xiaoqi.companion.data.datastore.AppPreferences
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.data.repository.ConfigRepository
import com.xiaoqi.companion.data.repository.DefaultLlmValues
import com.xiaoqi.companion.feature.chat.ChatConfigStatus
import com.xiaoqi.companion.feature.chat.ChatUiState
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsUseCaseTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val state = MutableStateFlow(
        ChatUiState(
            configStatus = ChatConfigStatus(
                label = "GLM",
                isReady = true,
                detail = "Ready",
                provider = LlmProvider.GLM,
                modelName = "glm-5v-turbo",
                baseUrl = "https://open.bigmodel.cn/api/paas/v1",
            ),
        )
    )
    private val update: (ChatUiState.() -> ChatUiState) -> Unit = { reducer -> state.update(reducer) }

    private val configRepository: ConfigRepository = mockk(relaxed = true)
    private val appPreferences: AppPreferences = mockk(relaxed = true)
    private lateinit var useCase: SettingsUseCase

    @Before
    fun setUp() {
        useCase = SettingsUseCase(
            configRepository = configRepository,
            appPreferences = appPreferences,
        )
    }

    @Test
    fun prepareSettings_loadsCurrentConfig() = runTest {
        useCase.prepareSettings(state.value, this, update)
        assertEquals(LlmProvider.GLM, state.value.settingsProvider)
        assertEquals("glm-5v-turbo", state.value.settingsModelName)
        assertEquals("https://open.bigmodel.cn/api/paas/v1", state.value.settingsBaseUrl)
    }

    @Test
    fun saveSettings_persistsProviderModelAndApiKey() = runTest {
        useCase.prepareSettings(state.value, this, update)
        state.update { it.copy(settingsProvider = LlmProvider.KIMI, settingsModelName = DefaultLlmValues.KIMI_MODEL) }
        state.update { it.copy(settingsApiKey = "new-key") }

        useCase.saveSettings(state.value, this, update)
        advanceUntilIdle()

        coVerify { configRepository.setLlmProvider(LlmProvider.KIMI) }
        coVerify { configRepository.setModelName(DefaultLlmValues.KIMI_MODEL) }
        coVerify { configRepository.setBaseUrl(DefaultLlmValues.KIMI_BASE_URL) }
        coVerify { configRepository.setApiKey("new-key") }
    }

    @Test
    fun saveSettings_openAiPreservesCustomEndpointAndModel() = runTest {
        state.update {
            it.copy(
                settingsProvider = LlmProvider.OPENAI_COMPATIBLE,
                settingsModelName = " vendor/custom-model ",
                settingsBaseUrl = " https://example.test/proxy/v1/ ",
                settingsApiKey = "openai-key",
            )
        }
        useCase.saveSettings(state.value, this, update)
        coVerify { configRepository.setLlmProvider(LlmProvider.OPENAI_COMPATIBLE) }
        coVerify { configRepository.setModelName("vendor/custom-model") }
        coVerify { configRepository.setBaseUrl("https://example.test/proxy/v1/") }
        coVerify { configRepository.setApiKey("openai-key") }
    }

    @Test
    fun saveSettings_invalidOpenAiEndpointDoesNotPersist() = runTest {
        state.update { it.copy(settingsProvider = LlmProvider.OPENAI_COMPATIBLE, settingsBaseUrl = "not a url") }
        useCase.saveSettings(state.value, this, update)
        coVerify(exactly = 0) { configRepository.setLlmProvider(any()) }
        assertTrue(state.value.settingsMessage.orEmpty().contains("Base URL"))
    }

    @Test
    fun editingApiKeyDoesNotSaveUnderThePreviouslyActiveProvider() = runTest {
        useCase.updateSettingsApiKey("new-key", update)
        advanceUntilIdle()
        assertEquals("new-key", state.value.settingsApiKey)
        coVerify(exactly = 0) { configRepository.setApiKey(any()) }
    }

    @Test
    fun saveSettings_blankModelNameFallsBackToProviderDefault() = runTest {
        state.update { it.copy(settingsModelName = "   ") }
        useCase.saveSettings(state.value, this, update)
        advanceUntilIdle()
        coVerify { configRepository.setModelName(DefaultLlmValues.GLM_MODEL) }
    }

    @Test
    fun updateSettingsProvider_appliesProviderDefaultModelAndBaseUrl() = runTest {
        useCase.updateSettingsProvider(LlmProvider.MODELSCOPE, this, update)
        advanceUntilIdle()

        assertEquals(LlmProvider.MODELSCOPE, state.value.settingsProvider)
        assertEquals(DefaultLlmValues.MODELSCOPE_MODEL, state.value.settingsModelName)
        assertEquals(DefaultLlmValues.MODELSCOPE_BASE_URL, state.value.settingsBaseUrl)
    }

    @Test
    fun setSystemToolsEnabled_persistsToAppPreferences() = runTest {
        useCase.setSystemToolsEnabled(false, update)
        advanceUntilIdle()
        coVerify { appPreferences.setSystemToolsEnabled(false) }
    }
}
