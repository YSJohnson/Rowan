package com.xiaoqi.companion.core.companion

import com.xiaoqi.companion.core.tools.ToolPolicy
import com.xiaoqi.companion.data.db.converter.LlmProvider

data class ProviderCapabilities(
    val supportsStreaming: Boolean,
    val supportsVision: Boolean,
    val supportsTools: Boolean,
    val supportsThinking: Boolean,
    val maxContextTokens: Int,
    val defaultToolPolicy: ToolPolicy,
)

object ProviderCapabilityRegistry {
    fun forProvider(provider: LlmProvider): ProviderCapabilities =
        when (provider) {
            LlmProvider.GLM -> ProviderCapabilities(
                supportsStreaming = true,
                supportsVision = true,
                supportsTools = true,
                supportsThinking = true,
                maxContextTokens = 200_000,
                defaultToolPolicy = ToolPolicy.chatDefault,
            )
            LlmProvider.KIMI -> ProviderCapabilities(
                supportsStreaming = true,
                supportsVision = true,
                supportsTools = true,
                supportsThinking = false,
                maxContextTokens = 256_000,
                defaultToolPolicy = ToolPolicy.chatDefault,
            )
            LlmProvider.MODELSCOPE -> ProviderCapabilities(
                supportsStreaming = true,
                supportsVision = false,
                supportsTools = true,
                supportsThinking = true,
                maxContextTokens = 128_000,
                defaultToolPolicy = ToolPolicy.chatDefault,
            )
            LlmProvider.OPENAI -> openAi()
            LlmProvider.OPENAI_COMPATIBLE -> openAi()
            LlmProvider.DEEPSEEK -> openAi(vision = false, thinking = true)
            LlmProvider.DASHSCOPE -> openAi(vision = true, thinking = true)
            LlmProvider.SILICONFLOW -> openAi(vision = true, thinking = true)
            LlmProvider.VOLCENGINE -> openAi(vision = true, thinking = true, maxContextTokens = 256_000)
            LlmProvider.MINIMAX -> openAi(vision = false, maxContextTokens = 1_000_000)
            LlmProvider.OPENROUTER -> openAi()
        }

    /** OpenAI 兼容系服务商的共同形态：流式 + 工具必开，视觉/思考按家各自覆写。 */
    private fun openAi(
        vision: Boolean = true,
        thinking: Boolean = false,
        maxContextTokens: Int = 128_000,
    ) = ProviderCapabilities(
        supportsStreaming = true,
        supportsVision = vision,
        supportsTools = true,
        supportsThinking = thinking,
        maxContextTokens = maxContextTokens,
        defaultToolPolicy = ToolPolicy.chatDefault,
    )
}
