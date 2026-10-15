package com.xiaoqi.companion.core.config

import com.xiaoqi.companion.BuildConfig
import com.xiaoqi.companion.core.llm.openAiClientSettings
import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.data.datastore.AppPreferences
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.data.repository.DefaultLlmValues
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Debug 构建专用：把 `.env -> BuildConfig.ENV_*` 的配置预填进 DataStore，
 * 省去开发/演示时每次在设置页手填 API key。
 *
 * 触发：[com.xiaoqi.companion.CompanionApplication.onCreate] 里 `if (BuildConfig.DEBUG)`。
 * Release 构建的 ENV_* 是 defaultConfig 空占位，seed() 实质 no-op，且不会进入该分支。
 *
 * 策略（由 .env 的 ENV_FORCE_SEED 控制）：
 * - true  → 每次启动强制用 .env 覆盖 DataStore
 * - false → 仅当字段为空/默认时填，不破坏运行时手改的值
 */
@Singleton
class DebugConfigSeeder @Inject constructor(
    private val appPreferences: AppPreferences,
) {
    suspend fun seed() {
        val force = BuildConfig.ENV_FORCE_SEED
        AppLogger.info(LogTags.Config, "debug_config_seed_started", "force" to force)

        seedLlm(
            force = force,
            provider = runCatching { LlmProvider.valueOf(BuildConfig.ENV_LLM_PROVIDER) }.getOrNull(),
            model = BuildConfig.ENV_LLM_MODEL,
            baseUrl = BuildConfig.ENV_LLM_BASE_URL.trim(),
        )

        AppLogger.info(LogTags.Config, "debug_config_seed_done")
    }

    internal suspend fun seedLlm(force: Boolean, provider: LlmProvider?, model: String, baseUrl: String) {
        if (provider == null) return

        // 构建 per-provider key map，所有非空 key 同时注入
        val keyMap = mapOf(
            LlmProvider.GLM to BuildConfig.ENV_GLM_API_KEY,
            LlmProvider.MODELSCOPE to BuildConfig.ENV_MODELSCOPE_API_KEY,
            LlmProvider.KIMI to BuildConfig.ENV_KIMI_API_KEY,
            LlmProvider.OPENAI_COMPATIBLE to BuildConfig.ENV_OPENAI_API_KEY,
        ).filterValues { it.isNotBlank() }

        if (keyMap.isNotEmpty()) {
            val existingJson = appPreferences.apiKeysJson.first()
            val existing = runCatching { JSONObject(existingJson) }.getOrNull() ?: JSONObject()
            val additions = keyMap.filter { (p, _) -> force || existing.optString(p.name).isBlank() }
            if (additions.isNotEmpty()) {
                additions.forEach { (p, k) -> existing.put(p.name, k) }
                appPreferences.setApiKeysJson(existing.toString())
            }
        }

        val previousProvider = appPreferences.llmProvider.first()
        if (force || previousProvider == AppPreferences.defaultLlmProvider) {
            appPreferences.setLlmProvider(provider)
            if (force || previousProvider != provider) {
                appPreferences.setBaseUrl(DefaultLlmValues.defaultBaseUrl(provider))
                appPreferences.setModelName(model.ifBlank { DefaultLlmValues.defaultModel(provider) })
            }
        }
        if (provider == LlmProvider.OPENAI_COMPATIBLE && appPreferences.llmProvider.first() == provider &&
            baseUrl.isNotBlank() && (force || appPreferences.baseUrl.first().isBlank()) &&
            runCatching { openAiClientSettings(baseUrl) }.isSuccess
        ) {
            appPreferences.setBaseUrl(baseUrl)
        }
        if (model.isNotBlank() && (force || appPreferences.modelName.first() == DefaultLlmValues.GLM_MODEL)) {
            appPreferences.setModelName(model)
        }
    }
}
