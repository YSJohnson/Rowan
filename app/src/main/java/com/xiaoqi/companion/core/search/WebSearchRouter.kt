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
 * [WebSearchBackend.AUTO]（默认）按 [FALLBACK_ORDER] 取第一个可用的：有密钥的后端排在前面
 * （结果质量更好），免密钥的 Bing 排最后兜底 —— 所以**默认状态下永远能搜**，用户不需要
 * 先配好任何东西。这也是原来那个 bug 的根因修复：以前默认值是"智谱"，而用 DeepSeek 的
 * 用户没有智谱密钥，三个后端全不可用，搜索直接失败。
 *
 * 用户显式选了某个后端时，仍然优先用它，不可用才往后退；这样"选了 Tavily 但密钥填错"
 * 也能退到能用的后端，而不是整个功能报错。
 */
@Singleton
class WebSearchRouter @Inject constructor(
    private val glmProvider: GlmWebSearchProvider,
    private val tavilyProvider: TavilyWebSearchProvider,
    private val serperProvider: SerperWebSearchProvider,
    private val bingProvider: BingWebSearchProvider,
    private val appPreferences: AppPreferences,
) {

    private val providers: Map<WebSearchBackend, WebSearchProvider> = mapOf(
        WebSearchBackend.GLM to glmProvider,
        WebSearchBackend.TAVILY to tavilyProvider,
        WebSearchBackend.SERPER to serperProvider,
        WebSearchBackend.BING to bingProvider,
    )

    suspend fun search(query: String, maxResults: Int): WebSearchResponse {
        if (!appPreferences.webSearchEnabled.first()) {
            throw WebSearchException(
                reason = "search_disabled",
                hint = "用户在设置里关掉了联网搜索。请直接凭已有知识回答，并说明没有联网查证。",
            )
        }
        val preferred = appPreferences.webSearchBackend.first()
        val ordered = if (preferred == WebSearchBackend.AUTO) {
            FALLBACK_ORDER
        } else {
            listOf(preferred) + FALLBACK_ORDER.filterNot { it == preferred }
        }
        val provider = ordered.firstOrNull { providers.getValue(it).isConfigured() }
            ?: throw WebSearchException(
                reason = "search_not_configured",
                hint = "没有任何可用的搜索后端。请让用户到「设置 > 联网搜索」检查配置。",
            )
        if (provider != preferred && preferred != WebSearchBackend.AUTO) {
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
        /**
         * 有密钥的后端在前（结果质量更好），免密钥的 Bing 兜底。
         * GLM 排在 Tavily 之前，因为它的密钥通常已经因为对话配置而存在。
         */
        val FALLBACK_ORDER = listOf(
            WebSearchBackend.GLM,
            WebSearchBackend.TAVILY,
            WebSearchBackend.SERPER,
            WebSearchBackend.BING,
        )
    }
}
