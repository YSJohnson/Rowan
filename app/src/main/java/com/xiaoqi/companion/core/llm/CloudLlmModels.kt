package com.xiaoqi.companion.core.llm

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.data.repository.LlmConfig
import okhttp3.HttpUrl.Companion.toHttpUrl

internal fun openAiClientSettings(baseUrl: String): OpenAIClientSettings {
    val url = baseUrl.trim().toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Base URL 不能包含用户名、密码、查询参数或片段"
    }
    val base = url.toString().trimEnd('/')
    val prefix = if (url.pathSegments.lastOrNull { it.isNotEmpty() } == "v1") "" else "v1/"
    return OpenAIClientSettings(
        baseUrl = "$base/",
        chatCompletionsPath = "${prefix}chat/completions",
        modelsPath = "${prefix}models",
    )
}

internal fun LlmConfig.toKoogModel(): LLModel = LLModel(
    provider = if (provider == LlmProvider.OPENAI_COMPATIBLE) LLMProvider.OpenAI else LLMProvider.Anthropic,
    id = modelName,
    capabilities = buildList {
        add(LLMCapability.Completion)
        add(LLMCapability.Temperature)
        add(LLMCapability.Tools)
        add(LLMCapability.Vision.Image)
        if (provider == LlmProvider.OPENAI_COMPATIBLE) add(LLMCapability.OpenAIEndpoint.Completions)
    },
)
