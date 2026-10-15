package com.xiaoqi.companion.core.search

import com.xiaoqi.companion.data.datastore.AppPreferences
import com.xiaoqi.companion.data.db.converter.LlmProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * 智谱 Web Search API。刻意复用对话用的 GLM 密钥：同一个账号、同一个 key，用户装完就能搜，
 * 不用再去申请第二个服务。
 *
 * 代价是它只在用户配过 GLM 密钥时可用 —— 路由层据此决定要不要退回别的后端。
 */
@Singleton
class GlmWebSearchProvider @Inject constructor(
    private val client: OkHttpClient,
    private val appPreferences: AppPreferences,
) : WebSearchProvider {

    override val backend: WebSearchBackend = WebSearchBackend.GLM

    override suspend fun isConfigured(): Boolean = resolveApiKey().isNotBlank()

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) {
            throw WebSearchException(
                reason = "search_not_configured",
                hint = "智谱密钥还没填。请让用户到「设置 > 云端接入」填 GLM 的 API Key，或到「设置 > 联网搜索」换一个搜索后端。",
            )
        }
        return parseGlmResults(
            postSearchJson(
                client = client,
                backend = backend,
                url = ENDPOINT,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Accept" to "application/json",
                ),
                body = buildJsonObject {
                    put("search_engine", "search_std")
                    put("search_query", query)
                    put("count", maxResults.coerceIn(1, MAX_RESULTS))
                },
            )
        ).usable()
    }

    /** 先取 per-provider 的 GLM 密钥，再退回旧版单值字段（老安装只有那一个）。 */
    private suspend fun resolveApiKey(): String =
        appPreferences.getApiKeyForProvider(LlmProvider.GLM)
            ?: appPreferences.apiKey.first()?.takeIf { it.isNotBlank() }
            ?: ""

    private companion object {
        const val ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/web_search"
        const val MAX_RESULTS = 20
    }
}
