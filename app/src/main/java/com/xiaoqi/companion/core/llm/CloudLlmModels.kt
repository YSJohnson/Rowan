package com.xiaoqi.companion.core.llm

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.data.repository.LlmConfig
import okhttp3.HttpUrl.Companion.toHttpUrl

private val API_VERSION_SEGMENT = Regex("^v\\d+$")

/**
 * 由 Base URL 推导 OpenAI 兼容端点路径。
 *
 * 路径前缀按"末段是否已经是版本号"判断，而不是写死 `== "v1"`：火山方舟用的是 `/api/v3`，
 * 写死 v1 会拼出 `/api/v3/v1/chat/completions` 这种 404 路径。
 */
internal fun openAiClientSettings(baseUrl: String): OpenAIClientSettings {
    val url = baseUrl.trim().toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Base URL 不能包含用户名、密码、查询参数或片段"
    }
    val base = url.toString().trimEnd('/')
    val tail = url.pathSegments.lastOrNull { it.isNotEmpty() }
    val prefix = if (tail != null && API_VERSION_SEGMENT.matches(tail)) "" else "v1/"
    return OpenAIClientSettings(
        baseUrl = "$base/",
        chatCompletionsPath = "${prefix}chat/completions",
        modelsPath = "${prefix}models",
    )
}

internal fun LlmConfig.toKoogModel(): LLModel = LLModel(
    provider = if (provider.usesOpenAiProtocol) LLMProvider.OpenAI else LLMProvider.Anthropic,
    id = modelName,
    capabilities = buildList {
        add(LLMCapability.Completion)
        add(LLMCapability.Temperature)
        add(LLMCapability.Tools)
        add(LLMCapability.Vision.Image)
        if (provider.usesOpenAiProtocol) add(LLMCapability.OpenAIEndpoint.Completions)
    },
)
