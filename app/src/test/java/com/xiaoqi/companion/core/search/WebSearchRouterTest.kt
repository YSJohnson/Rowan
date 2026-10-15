package com.xiaoqi.companion.core.search

import com.xiaoqi.companion.data.datastore.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后端选择与兜底。
 *
 * 关键行为：用户选的后端没配密钥时要能自动退到别的后端（"默认 GLM 但其实只填了 Tavily"
 * 这种配置不该报错），全都没配才失败；总开关关掉时连探测都不做。
 */
class WebSearchRouterTest {

    private val glm = mockk<GlmWebSearchProvider>()
    private val tavily = mockk<TavilyWebSearchProvider>()
    private val serper = mockk<SerperWebSearchProvider>()
    private val bing = mockk<BingWebSearchProvider>()
    private val appPreferences = mockk<AppPreferences>(relaxed = true)

    private fun router() = WebSearchRouter(glm, tavily, serper, bing, appPreferences)

    private fun prefs(enabled: Boolean, backend: WebSearchBackend) {
        every { appPreferences.webSearchEnabled } returns flowOf(enabled)
        every { appPreferences.webSearchBackend } returns flowOf(backend)
    }

    @Test
    fun `uses the configured preferred backend`() = runTest {
        prefs(enabled = true, backend = WebSearchBackend.SERPER)
        coEvery { serper.isConfigured() } returns true
        coEvery { serper.search(any(), any()) } returns listOf(WebSearchResult("t", "u", "s"))

        val response = router().search("query", 5)

        assertEquals(WebSearchBackend.SERPER, response.backend)
        assertEquals(1, response.results.size)
        coVerify(exactly = 0) { glm.search(any(), any()) }
    }

    @Test
    fun `falls back when the preferred backend has no key`() = runTest {
        prefs(enabled = true, backend = WebSearchBackend.TAVILY)
        coEvery { tavily.isConfigured() } returns false
        coEvery { glm.isConfigured() } returns true
        coEvery { glm.search(any(), any()) } returns emptyList()

        val response = router().search("query", 5)

        assertEquals(WebSearchBackend.GLM, response.backend)
        coVerify(exactly = 0) { tavily.search(any(), any()) }
    }

    @Test
    fun `auto mode picks the first backend that has a key`() = runTest {
        prefs(enabled = true, backend = WebSearchBackend.AUTO)
        coEvery { glm.isConfigured() } returns false
        coEvery { tavily.isConfigured() } returns true
        coEvery { tavily.search(any(), any()) } returns emptyList()

        val response = router().search("query", 5)

        assertEquals(WebSearchBackend.TAVILY, response.backend)
        coVerify(exactly = 0) { bing.search(any(), any()) }
    }

    /**
     * 这条是本次线上问题的回归测试：用 DeepSeek 的用户没有任何搜索密钥，
     * 旧默认值写死"智谱"会导致搜索整个失败；现在应自动落到免密钥的必应。
     */
    @Test
    fun `auto mode falls back to key-free bing when no key is configured`() = runTest {
        prefs(enabled = true, backend = WebSearchBackend.AUTO)
        coEvery { glm.isConfigured() } returns false
        coEvery { tavily.isConfigured() } returns false
        coEvery { serper.isConfigured() } returns false
        coEvery { bing.isConfigured() } returns true
        coEvery { bing.search(any(), any()) } returns listOf(WebSearchResult("t", "u", "s"))

        val response = router().search("query", 5)

        assertEquals(WebSearchBackend.BING, response.backend)
        assertEquals(1, response.results.size)
    }

    @Test
    fun `reports not configured when even the key-free backend is unavailable`() = runTest {
        prefs(enabled = true, backend = WebSearchBackend.AUTO)
        coEvery { glm.isConfigured() } returns false
        coEvery { tavily.isConfigured() } returns false
        coEvery { serper.isConfigured() } returns false
        coEvery { bing.isConfigured() } returns false

        val error = runCatching { router().search("query", 5) }.exceptionOrNull()

        assertEquals("search_not_configured", (error as WebSearchException).reason)
    }

    @Test
    fun `does not probe any backend when search is switched off`() = runTest {
        prefs(enabled = false, backend = WebSearchBackend.AUTO)

        val error = runCatching { router().search("query", 5) }.exceptionOrNull()

        assertEquals("search_disabled", (error as WebSearchException).reason)
        coVerify(exactly = 0) { glm.isConfigured() }
        coVerify(exactly = 0) { bing.isConfigured() }
    }
}
