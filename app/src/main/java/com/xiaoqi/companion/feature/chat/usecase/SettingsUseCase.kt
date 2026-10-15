package com.xiaoqi.companion.feature.chat.usecase

import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.core.search.WebSearchBackend
import com.xiaoqi.companion.data.datastore.AppPreferences
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.data.repository.ConfigRepository
import com.xiaoqi.companion.data.repository.DefaultLlmValues
import com.xiaoqi.companion.feature.chat.ChatUiState
import com.xiaoqi.companion.feature.chat.mapper.defaultBaseUrl
import com.xiaoqi.companion.feature.chat.mapper.toChatConfigStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 模型配置 + 5 个 boolean 偏好的统一操作入口。
 *
 * 行为分组:
 * - **Settings 面板**:`prepareSettings` / `updateSettings*` / `saveSettings`
 * - **Boolean 偏好**:`setDeviceStatusContextEnabled` / `setLocationContextEnabled` / `setWeatherContextEnabled` / `setReminderToolEnabled` / `setNotificationEnabled`
 *
 * 状态写入全部通过 [update] 回调,与 SendMessageUseCase 保持一致。
 */
class SettingsUseCase @Inject constructor(
    private val configRepository: ConfigRepository,
    private val appPreferences: AppPreferences,
) {

    //region Settings 面板

    fun prepareSettings(
        state: ChatUiState,
        scope: CoroutineScope,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) {
        update {
            copy(
                settingsProvider = state.configStatus.provider,
                settingsApiKey = "",
                settingsModelName = state.configStatus.modelName,
                settingsBaseUrl = state.configStatus.baseUrl,
                settingsMessage = null,
            )
        }
        // 加载当前 provider 的 per-provider key 填入输入框
        scope.launch {
            val key = appPreferences.getApiKeyForProvider(state.configStatus.provider).orEmpty()
            update {
                if (settingsProvider == state.configStatus.provider && settingsApiKey.isBlank()) copy(settingsApiKey = key) else this
            }
        }
    }

    /** 编辑密钥仅更新草稿；保存时与选中的 Provider 一起持久化。 */
    fun updateSettingsApiKey(value: String, update: (ChatUiState.() -> ChatUiState) -> Unit) {
        update { copy(settingsApiKey = value, settingsMessage = null) }
    }

    fun updateSettingsProvider(
        value: LlmProvider,
        scope: CoroutineScope,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) {
        val defaultModel = DefaultLlmValues.defaultModel(value)
        update {
            copy(
                settingsProvider = value,
                settingsApiKey = "",
                settingsModelName = defaultModel,
                settingsBaseUrl = defaultBaseUrl(value),
                settingsMessage = null,
                // 上家探测到的模型 ID 对下家没有意义，切换即作废
                probedModels = emptyList(),
                modelProbeMessage = null,
            )
        }
        // 切换 provider 后加载该 provider 已保存的 key
        scope.launch {
            val key = appPreferences.getApiKeyForProvider(value).orEmpty()
            update {
                if (settingsProvider == value && settingsApiKey.isBlank()) copy(settingsApiKey = key) else this
            }
        }
    }

    fun updateSettingsModelName(
        value: String,
        state: ChatUiState,
        scope: CoroutineScope,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) {
        update { copy(settingsModelName = value, settingsMessage = null) }
    }

    fun updateSettingsBaseUrl(value: String, update: (ChatUiState.() -> ChatUiState) -> Unit) {
        update { copy(settingsBaseUrl = value, settingsMessage = null) }
    }

    suspend fun saveSettings(
        state: ChatUiState,
        scope: CoroutineScope,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) {
        val provider = state.settingsProvider
        val custom = provider.isCustomEndpoint
        // 模型名不再做白名单校验：探测出来的模型 ID 是动态的，任何非空值都必须能被保存。
        val model = state.settingsModelName.trim().ifBlank { DefaultLlmValues.defaultModel(provider) }
        val baseUrl = if (custom) state.settingsBaseUrl.trim() else DefaultLlmValues.defaultBaseUrl(provider)
        if (model.isBlank()) {
            update { copy(settingsMessage = "模型名称不能为空") }
            return
        }
        if (baseUrl.isBlank()) {
            update { copy(settingsMessage = "Base URL 不能为空") }
            return
        }
        if (custom && runCatching { com.xiaoqi.companion.core.llm.openAiClientSettings(baseUrl) }.isFailure) {
            update { copy(settingsMessage = "Base URL 必须是有效的 HTTP(S) 地址，且不含鉴权信息或查询参数") }
            return
        }
        val startedAt = System.currentTimeMillis()
        try {
            AppLogger.info(LogTags.Config, "settings_save_started", "provider" to provider, "model" to model)
            configRepository.setLlmProvider(provider)
            configRepository.setModelName(model)
            configRepository.setBaseUrl(baseUrl)
            state.settingsApiKey.trim().takeIf { it.isNotEmpty() }?.let { apiKey ->
                configRepository.setApiKey(apiKey)
            }
            update {
                copy(
                    settingsApiKey = "",
                    settingsModelName = model,
                    settingsBaseUrl = baseUrl,
                    settingsMessage = null,
                )
            }
            AppLogger.info(
                LogTags.Config,
                "settings_save_completed",
                "provider" to provider,
                "model" to model,
                "durationMs" to (System.currentTimeMillis() - startedAt),
            )
        } catch (e: Exception) {
            AppLogger.error(
                LogTags.Config,
                e,
                "settings_save_failed",
                "provider" to provider,
                "model" to model,
                "durationMs" to (System.currentTimeMillis() - startedAt),
            )
            update { copy(settingsMessage = "保存失败，请重试。") }
        }
    }

    //endregion

    //region Boolean 偏好

    suspend fun setDeviceStatusContextEnabled(
        value: Boolean,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) = updateBooleanPreference("device_status_context", value, update) {
        appPreferences.setDeviceStatusContextEnabled(value)
    }

    suspend fun setLocationContextEnabled(
        value: Boolean,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) = updateBooleanPreference("location_context", value, update) {
        appPreferences.setLocationContextEnabled(value)
    }

    suspend fun setWeatherContextEnabled(
        value: Boolean,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) = updateBooleanPreference("weather_context", value, update) {
        appPreferences.setWeatherContextEnabled(value)
    }

    suspend fun setReminderToolEnabled(
        value: Boolean,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) = updateBooleanPreference("reminder_tool", value, update) {
        appPreferences.setReminderToolEnabled(value)
    }

    suspend fun setSystemToolsEnabled(
        value: Boolean,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) = updateBooleanPreference("system_tools", value, update) {
        appPreferences.setSystemToolsEnabled(value)
    }

    suspend fun setNotificationEnabled(
        value: Boolean,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) = updateBooleanPreference("notification", value, update) {
        appPreferences.setNotificationEnabled(value)
    }

    suspend fun setWebSearchEnabled(
        value: Boolean,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) = updateBooleanPreference("web_search", value, update) {
        appPreferences.setWebSearchEnabled(value)
    }

    suspend fun setWebSearchBackend(
        value: WebSearchBackend,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) {
        update { copy(settingsWebSearchBackend = value) }
        runCatching { appPreferences.setWebSearchBackend(value) }.onFailure {
            AppLogger.error(LogTags.Config, it, "preference_update_failed", "name" to "web_search_backend")
            update { copy(error = "更新设置失败，请重试。") }
        }
    }

    suspend fun setWebSearchApiKey(
        value: String,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
    ) {
        val trimmed = value.trim()
        update { copy(settingsWebSearchApiKey = trimmed) }
        runCatching { appPreferences.setWebSearchApiKey(trimmed) }.onFailure {
            AppLogger.error(LogTags.Config, it, "preference_update_failed", "name" to "web_search_api_key")
            update { copy(error = "更新设置失败，请重试。") }
        }
    }

    private suspend fun updateBooleanPreference(
        name: String,
        value: Boolean,
        update: (ChatUiState.() -> ChatUiState) -> Unit,
        write: suspend () -> Unit,
    ) {
        try {
            AppLogger.info(LogTags.Config, "preference_update_started", "name" to name, "value" to value)
            write()
            AppLogger.info(LogTags.Config, "preference_update_completed", "name" to name, "value" to value)
        } catch (e: Exception) {
            AppLogger.error(LogTags.Config, e, "preference_update_failed", "name" to name, "value" to value)
            update { copy(error = "更新设置失败，请重试。") }
        }
    }

    //endregion

    //region 辅助

    // (无 — 所有方法都接收显式 state 参数,避免侧信道读取)

    //endregion
}
