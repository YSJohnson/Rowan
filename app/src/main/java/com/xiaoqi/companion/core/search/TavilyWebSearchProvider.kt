package com.xiaoqi.companion.core.search

import com.xiaoqi.companion.data.datastore.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Tavily —— 面向 LLM 的搜索 API，返回的是已抽取好的正文片段，适合直接喂给模型。
 * 需要用户单独申请密钥（设置页「联网搜索」里填）。
 */
@Singleton
class TavilyWebSearchProvider @Inject constructor(
    private val client: OkHttpClient,
    private val appPreferences: AppPreferences,
) : WebSearchProvider {

    override val backend: WebSearchBackend = WebSearchBackend.TAVILY

    override suspend fun isConfigured(): Boolean = apiKey().isNotBlank()

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
        val key = apiKey()
        if (key.isBlank()) {
            throw WebSearchException(
                reason = "search_not_configured",
                hint = "Tavily 密钥还没填。请让用户到「设置 > 联网搜索」填入 Tavily API Key，或改用智谱后端。",
            )
        }
        return parseTavilyResults(
            postSearchJson(
                client = client,
                backend = backend,
                url = ENDPOINT,
                headers = mapOf(
                    "Authorization" to "Bearer $key",
                    "Accept" to "application/json",
                ),
                body = buildJsonObject {
                    put("query", query)
                    put("max_results", maxResults.coerceIn(1, MAX_RESULTS))
                    put("search_depth", "basic")
                },
            )
        ).usable()
    }

    private suspend fun apiKey(): String = appPreferences.webSearchApiKey.first().trim()

    private companion object {
        const val ENDPOINT = "https://api.tavily.com/search"
        const val MAX_RESULTS = 20
    }
}
