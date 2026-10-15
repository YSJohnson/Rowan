package com.xiaoqi.companion.feature.chat

import com.xiaoqi.companion.data.db.converter.LlmProvider

/**
 * Provider 的中文显示名。
 *
 * 用穷尽 `when` 而不是 Map：新增枚举值时编译器会强制补一条，不会静默漏掉一个 chip。
 * KIMI / MOONSHOT 这类同公司不同端点的名字要标清楚协议差异，否则用户会拿开放平台的
 * key 去请求编程版端点。
 */
internal fun LlmProvider.displayName(): String = when (this) {
    LlmProvider.GLM -> "智谱 GLM"
    LlmProvider.KIMI -> "Kimi 编程版"
    LlmProvider.MODELSCOPE -> "魔搭 ModelScope"
    LlmProvider.OPENAI -> "OpenAI"
    LlmProvider.DEEPSEEK -> "DeepSeek"
    LlmProvider.DASHSCOPE -> "通义千问"
    LlmProvider.SILICONFLOW -> "硅基流动"
    LlmProvider.VOLCENGINE -> "火山方舟"
    LlmProvider.MINIMAX -> "MiniMax"
    LlmProvider.OPENROUTER -> "OpenRouter"
    LlmProvider.OPENAI_COMPATIBLE -> "自定义"
}
