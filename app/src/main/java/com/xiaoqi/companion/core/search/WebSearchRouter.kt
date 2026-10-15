package com.xiaoqi.companion.core.search

import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.data.datastore.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class WebSearchResponse(
    val backend: WebSearchBackend,
    val results: List<WebSearchResult>,
)

/**
 * 选后端 + 兜底。
 *
 * 规则：优先用用户在设置里选的后端；它没配密钥就按 [FALLBACK_ORDER] 依次退到别的后端，
 * 全都没配才报 `search_not_configured`。这样"默认值 GLM 但用户其实只填了 Tavily"这种
 * 配置也能直接搜，不必先让用户去改设置。
 */
@Singleton
class WebSearchRouter @Inject constructor(
    private val glmProvider: GlmWebSearchProvider,
    private val tavilyProvider: TavilyWebSearchProvider,
    private val serperProvider: SerperWebSearchProvider,
    private val appPreferences: AppPreferences,
) {

    private val providers: Map<WebSearchBackend, WebSearchProvider> = mapOf(
        WebSearchBackend.GLM to glmProvider,
        WebSearchBackend.TAVILY to tavilyProvider,
        WebSearchBackend.SERPER to serperProvider,
    )

    suspend fun search(query: String, maxResults: Int): WebSearchResponse {
        if (!appPreferences.webSearchEnabled.first()) {
            throw WebSearchException(
                reason = "search_disabled",
                hint = "用户在设置里关掉了联网搜索。请直接凭已有知识回答，并说明没有联网查证。",
            )
        }
        val preferred = appPreferences.webSearchBackend.first()
        val ordered = listOf(preferred) + FALLBACK_ORDER.filterNot { it == preferred }
        val provider = ordered.firstOrNull { providers.getValue(it).isConfigured() }
            ?: throw WebSearchException(
                reason = "search_not_configured",
                hint = "没有任何可用的搜索后端。请让用户到「设置 > 云端接入」填 GLM 密钥，" +
                    "或到「设置 > 联网搜索」选一个后端并填密钥。",
            )
        if (provider != preferred) {
            AppLogger.info(
                LogTags.Tools,
                "web_search_backend_fallback",
                "preferred" to preferred,
                "used" to provider,
            )
        }
        return WebSearchResponse(
            backend = provider,
            results = providers.getValue(provider).search(query, maxResults),
        )
    }

    private companion object {
        /** 用户选的后端不可用时依次尝试的顺序。GLM 放最前：它复用对话密钥，命中率最高。 */
        val FALLBACK_ORDER = listOf(WebSearchBackend.GLM, WebSearchBackend.TAVILY, WebSearchBackend.SERPER)
    }
}
