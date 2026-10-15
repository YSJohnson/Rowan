package com.xiaoqi.companion.core.companion

import com.xiaoqi.companion.core.tools.ToolCategory
import com.xiaoqi.companion.data.db.converter.LlmProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCapabilitiesTest {

    @Test
    fun remoteProviders_supportStreamingAndTools() {
        val glm = ProviderCapabilityRegistry.forProvider(LlmProvider.GLM)
        val kimi = ProviderCapabilityRegistry.forProvider(LlmProvider.KIMI)

        assertTrue(glm.supportsStreaming)
        assertTrue(glm.supportsTools)
        assertTrue(ToolCategory.REMOTE_READ in glm.defaultToolPolicy.allowedCategories)
        assertTrue(kimi.supportsStreaming)
        assertTrue(kimi.supportsTools)
    }

    /**
     * 枚举穷尽性回归:新增 provider 时必须在此登记能力,否则 `forProvider` 的 when 分支会漏。
     */
    @Test
    fun everyProvider_hasStreamingAndChatDefaultTools() {
        for (provider in LlmProvider.entries) {
            val capabilities = ProviderCapabilityRegistry.forProvider(provider)

            assertTrue("$provider should support streaming", capabilities.supportsStreaming)
            assertTrue("$provider should support tools", capabilities.supportsTools)
            assertTrue("$provider should allow chat tools", capabilities.defaultToolPolicy.allowTools)
            assertTrue(capabilities.maxContextTokens > 0)
        }
    }

    @Test
    fun visionSupport_differsByProvider() {
        assertTrue(ProviderCapabilityRegistry.forProvider(LlmProvider.GLM).supportsVision)
        assertTrue(ProviderCapabilityRegistry.forProvider(LlmProvider.OPENAI_COMPATIBLE).supportsVision)
        assertTrue(!ProviderCapabilityRegistry.forProvider(LlmProvider.MODELSCOPE).supportsVision)
    }
}
