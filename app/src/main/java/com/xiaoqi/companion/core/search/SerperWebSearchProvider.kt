package com.xiaoqi.companion.core.search

import com.xiaoqi.companion.data.datastore.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Serper.dev —— 直接返回 Google 结果条目（标题/链接/摘要）。
 * 需要用户单独申请密钥（设置页「联网搜索」里填）。
 */
@Singleton
class SerperWebSearchProvider @Inject constructor(
    private val client: OkHttpClient,
    private val appPreferences: AppPreferences,
) : WebSearchProvider {

    override val backend: WebSearchBackend = WebSearchBackend.SERPER

    override suspend fun isConfigured(): Boolean = apiKey().isNotBlank()

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
        val key = apiKey()
        if (key.isBlank()) {
            throw WebSearchException(
                reason = "search_not_configured",
                hint = "Serper 密钥还没填。请让用户到「设置 > 联网搜索」填入 Serper API Key，或改用智谱后端。",
            )
        }
        return parseSerperResults(
            postSearchJson(
                client = client,
                backend = backend,
                url = ENDPOINT,
                headers = mapOf(
                    "X-API-KEY" to key,
                    "Accept" to "application/json",
                ),
                body = buildJsonObject {
                    put("q", query)
                    put("num", maxResults.coerceIn(1, MAX_RESULTS))
                },
            )
        ).usable()
    }

    private suspend fun apiKey(): String = appPreferences.webSearchApiKey.first().trim()

    private companion object {
        const val ENDPOINT = "https://google.serper.dev/search"
        const val MAX_RESULTS = 20
    }
}
