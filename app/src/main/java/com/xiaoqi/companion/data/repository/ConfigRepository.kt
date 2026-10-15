package com.xiaoqi.companion.data.repository

import com.xiaoqi.companion.core.llm.ConnectivityResult
import com.xiaoqi.companion.core.llm.LlmConnectivityChecker
import com.xiaoqi.companion.core.llm.ModelProbeResult
import com.xiaoqi.companion.data.datastore.AppPreferences
import com.xiaoqi.companion.data.db.converter.LlmProvider
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class LlmConfig(
    val provider: LlmProvider,
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
)

object DefaultLlmValues {
    const val GLM_BASE_URL = "https://open.bigmodel.cn/api/anthropic"
    const val GLM_MODEL = "glm-5v-turbo"
    const val KIMI_BASE_URL = "https://api.kimi.com/coding"
    const val KIMI_MODEL = "kimi-for-coding"
    // 魔搭 (ModelScope) Inference 端点,兼容 Anthropic Messages 协议:
    // 鉴权用 `x-api-key: <ms-token>`,请求路径 `${baseUrl}/v1/messages`。
    const val MODELSCOPE_BASE_URL = "https://api-inference.modelscope.cn"
    const val MODELSCOPE_MODEL = "Qwen/Qwen3.5-397B-A17B"

    const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val OPENAI_MODEL = "gpt-4o-mini"
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
    const val DEEPSEEK_MODEL = "deepseek-chat"
    // 阿里云百炼的 OpenAI 兼容模式,路径必须以 /compatible-mode/v1 结尾
    const val DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val DASHSCOPE_MODEL = "qwen-plus"
    const val SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1"
    const val SILICONFLOW_MODEL = "deepseek-ai/DeepSeek-V3"
    // 火山方舟路径是 /api/v3,不是 /v1 —— openAiClientSettings 按 vN 结尾判断是否追加前缀
    const val VOLCENGINE_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    const val VOLCENGINE_MODEL = "doubao-seed-1-6-250615"
    const val MINIMAX_BASE_URL = "https://api.minimax.chat/v1"
    const val MINIMAX_MODEL = "MiniMax-Text-01"
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
    const val OPENROUTER_MODEL = "openai/gpt-4o-mini"

    /** 自定义端点没有默认值 —— Base URL 和模型名都必须由用户填，留空会让 [LlmConfigStatus] 报缺失。 */
    const val CUSTOM_BASE_URL = ""
    const val CUSTOM_MODEL = ""

    fun defaultBaseUrl(provider: LlmProvider): String =
        when (provider) {
            LlmProvider.GLM -> GLM_BASE_URL
            LlmProvider.KIMI -> KIMI_BASE_URL
            LlmProvider.MODELSCOPE -> MODELSCOPE_BASE_URL
            LlmProvider.OPENAI -> OPENAI_BASE_URL
            LlmProvider.DEEPSEEK -> DEEPSEEK_BASE_URL
            LlmProvider.DASHSCOPE -> DASHSCOPE_BASE_URL
            LlmProvider.SILICONFLOW -> SILICONFLOW_BASE_URL
            LlmProvider.VOLCENGINE -> VOLCENGINE_BASE_URL
            LlmProvider.MINIMAX -> MINIMAX_BASE_URL
            LlmProvider.OPENROUTER -> OPENROUTER_BASE_URL
            LlmProvider.OPENAI_COMPATIBLE -> CUSTOM_BASE_URL
        }

    fun defaultModel(provider: LlmProvider): String =
        when (provider) {
            LlmProvider.GLM -> GLM_MODEL
            LlmProvider.KIMI -> KIMI_MODEL
            LlmProvider.MODELSCOPE -> MODELSCOPE_MODEL
            LlmProvider.OPENAI -> OPENAI_MODEL
            LlmProvider.DEEPSEEK -> DEEPSEEK_MODEL
            LlmProvider.DASHSCOPE -> DASHSCOPE_MODEL
            LlmProvider.SILICONFLOW -> SILICONFLOW_MODEL
            LlmProvider.VOLCENGINE -> VOLCENGINE_MODEL
            LlmProvider.MINIMAX -> MINIMAX_MODEL
            LlmProvider.OPENROUTER -> OPENROUTER_MODEL
            LlmProvider.OPENAI_COMPATIBLE -> CUSTOM_MODEL
        }

    /**
     * 服务商推荐模型，用于"探测模型"失败时的兜底下拉项。
     *
     * 探测成功时以 [com.xiaoqi.companion.core.llm.LlmModelProbe] 返回的真实列表为准 ——
     * 各家的模型 ID 变化很快，硬编码列表只能当 fallback。
     */
    fun modelOptions(provider: LlmProvider): List<String> =
        listOfNotNull(defaultModel(provider).takeIf { it.isNotBlank() })
}

data class LlmConfigStatus(
    val provider: LlmProvider,
    val modelName: String,
    val baseUrl: String,
    val hasApiKey: Boolean,
) {
    val isReady: Boolean = hasApiKey && baseUrl.isNotBlank() && modelName.isNotBlank()

    val missingReason: String?
        get() = when {
            !hasApiKey -> missingText("API Key")
            baseUrl.isBlank() -> missingText("Base URL")
            modelName.isBlank() -> missingText(modelNameText())
            else -> null
        }
}

private fun missingText(subject: String): String = "缺失 $subject"

private fun modelNameText(): String = "模型名称"

interface ConfigRepository {
    val apiKey: Flow<String?>
    val apiKeysJson: Flow<String>
    val baseUrl: Flow<String>
    val llmProvider: Flow<LlmProvider>
    val modelName: Flow<String>
    val themeMode: Flow<com.xiaoqi.companion.data.db.converter.ThemeMode>

    fun getCurrentLlmConfig(): Flow<LlmConfig>
    fun observeLlmConfigStatus(): Flow<LlmConfigStatus>
    suspend fun setApiKey(key: String?)
    suspend fun setBaseUrl(url: String)
    suspend fun setLlmProvider(provider: LlmProvider)
    suspend fun setModelName(name: String)
    suspend fun checkConnectivity(): ConnectivityResult

    /** 拉取当前服务商真实可用的模型列表，供设置页下拉选择。 */
    suspend fun probeModels(): ModelProbeResult
}

class ConfigRepositoryImpl @Inject constructor(
    private val prefs: AppPreferences,
    private val connectivityChecker: LlmConnectivityChecker,
) : ConfigRepository {

    override val apiKey get() = prefs.apiKey
    override val apiKeysJson get() = prefs.apiKeysJson
    override val baseUrl get() = prefs.baseUrl
    override val llmProvider get() = prefs.llmProvider
    override val modelName get() = prefs.modelName
    override val themeMode get() = prefs.themeMode

    override fun getCurrentLlmConfig(): Flow<LlmConfig> =
        combine(prefs.llmProvider, prefs.apiKeysJson, prefs.apiKey, prefs.modelName, prefs.baseUrl) { provider, keysJson, legacyKey, model, storedBaseUrl ->
            // 探测模型后模型名是动态的，不能再拿推荐列表当白名单校验 —— 那会把用户选中的
            // 探测结果打回默认值。
            val resolvedModel = model.trim().takeIf { it.isNotEmpty() }
                ?: DefaultLlmValues.defaultModel(provider)
            val perProviderKeys = runCatching { JSONObject(keysJson) }.getOrNull()
            val perProviderKey = perProviderKeys
                ?.optString(provider.name, "")
                ?.takeIf { it.isNotBlank() }
            // 旧的单值 apiKey 只在"一条 per-provider 记录都没有"时兜底（老版本升级场景）。
            // 否则切换服务商会拿上一个服务商的 key 去请求，表现为莫名其妙的 401。
            val noPerProviderKeys = perProviderKeys == null || perProviderKeys.length() == 0
            val resolvedKey = perProviderKey
                ?: legacyKey?.takeIf { noPerProviderKeys && !provider.isCustomEndpoint && it.isNotBlank() }
                ?: ""
            val resolvedBaseUrl = storedBaseUrl.takeIf { it.isNotBlank() }
                ?: DefaultLlmValues.defaultBaseUrl(provider)
            LlmConfig(
                provider = provider,
                baseUrl = resolvedBaseUrl,
                apiKey = resolvedKey,
                modelName = resolvedModel,
            )
        }

    override fun observeLlmConfigStatus(): Flow<LlmConfigStatus> =
        getCurrentLlmConfig().map { config ->
            LlmConfigStatus(
                provider = config.provider,
                baseUrl = config.baseUrl,
                hasApiKey = config.apiKey.isNotBlank(),
                modelName = config.modelName,
            )
        }

    override suspend fun setApiKey(key: String?) {
        val provider = prefs.llmProvider.first()
        if (key != null && key.isNotBlank()) {
            prefs.setApiKeyForProvider(provider, key)
        }
        // 旧字段供预设 Provider 回退使用，不能混入自定义服务商的密钥。
        if (!provider.isCustomEndpoint) prefs.setApiKey(key)
    }

    override suspend fun setBaseUrl(url: String) {
        prefs.setBaseUrl(url)
    }

    override suspend fun setLlmProvider(provider: LlmProvider) {
        prefs.setLlmProvider(provider)
    }

    override suspend fun setModelName(name: String) {
        prefs.setModelName(name)
    }

    override suspend fun checkConnectivity(): ConnectivityResult {
        val config = getCurrentLlmConfig().first()
        return connectivityChecker.check(config)
    }

    override suspend fun probeModels(): ModelProbeResult {
        val config = getCurrentLlmConfig().first()
        return connectivityChecker.listModels(config)
    }
}
