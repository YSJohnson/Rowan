package com.xiaoqi.companion.data.repository

import com.xiaoqi.companion.data.db.converter.LlmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 目录的不变量。
 *
 * 新增服务商时最容易漏的是 [DefaultLlmValues] 里那两个穷尽 `when` —— 漏掉会编译失败，
 * 但"填了却填错"（比如把 Anthropic 协议的服务商标成 OpenAI 协议）编译期查不出来，
 * 只会在真机上表现为 401 或路径 404。这里把这类不变量固化下来。
 */
class LlmProviderDefaultsTest {

    @Test
    fun `preset providers all have a usable default base url and model`() {
        LlmProvider.entries
            .filterNot { it.isCustomEndpoint }
            .forEach { provider ->
                assertTrue(
                    "${provider.name} 缺默认 Base URL",
                    DefaultLlmValues.defaultBaseUrl(provider).startsWith("https://"),
                )
                assertTrue(
                    "${provider.name} 缺默认模型",
                    DefaultLlmValues.defaultModel(provider).isNotBlank(),
                )
            }
    }

    @Test
    fun `only the custom endpoint provider has blank defaults`() {
        assertEquals("", DefaultLlmValues.defaultBaseUrl(LlmProvider.OPENAI_COMPATIBLE))
        assertEquals("", DefaultLlmValues.defaultModel(LlmProvider.OPENAI_COMPATIBLE))
        assertTrue(DefaultLlmValues.modelOptions(LlmProvider.OPENAI_COMPATIBLE).isEmpty())
        assertEquals(
            listOf(LlmProvider.OPENAI_COMPATIBLE),
            LlmProvider.entries.filter { it.isCustomEndpoint },
        )
    }

    @Test
    fun `protocol flag matches each provider's documented endpoint`() {
        val anthropicProtocol = listOf(LlmProvider.GLM, LlmProvider.KIMI, LlmProvider.MODELSCOPE)
        LlmProvider.entries.forEach { provider ->
            if (provider in anthropicProtocol) {
                assertFalse("${provider.name} 应走 Anthropic Messages", provider.usesOpenAiProtocol)
            } else {
                assertTrue("${provider.name} 应走 OpenAI 兼容协议", provider.usesOpenAiProtocol)
            }
        }
    }

    @Test
    fun `stored provider name resolves and unknown names fall back`() {
        LlmProvider.entries.forEach { provider ->
            assertEquals(provider, LlmProvider.fromStoredName(provider.name, LlmProvider.GLM))
        }
        assertEquals(LlmProvider.GLM, LlmProvider.fromStoredName("LOCAL_QWEN", LlmProvider.GLM))
        assertEquals(LlmProvider.GLM, LlmProvider.fromStoredName(null, LlmProvider.GLM))
    }
}
