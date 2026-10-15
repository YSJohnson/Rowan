package com.xiaoqi.companion.core.search

/**
 * 联网搜索的后端。
 *
 * [AUTO] 是默认值：按"优先用已配置的密钥、否则用免密钥"的顺序自动挑一个，保证**零配置就能搜**。
 * 用户想固定某一个后端时再显式选。
 *
 * - [GLM] 复用对话用的智谱密钥（同一账号的 Web Search API），配了 GLM 密钥的人不必额外申请。
 * - [TAVILY] / [SERPER] 需要各自申请密钥，结果质量最好（[usesDedicatedKey]）。
 * - [BING] 免密钥，抓取 cn.bing.com 结果页。可用性不依赖任何账号，代价是依赖页面结构。
 */
enum class WebSearchBackend(val label: String, val usesDedicatedKey: Boolean) {
    AUTO("自动", usesDedicatedKey = false),
    GLM("智谱", usesDedicatedKey = false),
    TAVILY("Tavily", usesDedicatedKey = true),
    SERPER("Serper", usesDedicatedKey = true),
    BING("必应", usesDedicatedKey = false),
}

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

/**
 * 搜索失败。
 *
 * [reason] 是机器码，会进 [com.xiaoqi.companion.core.tools.ToolEnvelope] 给模型判断下一步；
 * [hint] 是中文话术，模型据此向用户解释。分开是为了让模型能区分"密钥错了别重试"和
 * "网络抖了可以重试"。
 */
class WebSearchException(
    val reason: String,
    val hint: String,
    cause: Throwable? = null,
) : Exception(hint, cause)

interface WebSearchProvider {
    val backend: WebSearchBackend

    /** 密钥是否齐备。false 时路由层跳过这个后端，不会发起请求。 */
    suspend fun isConfigured(): Boolean

    /** 失败一律抛 [WebSearchException]，调用方不需要认识 OkHttp 的异常。 */
    suspend fun search(query: String, maxResults: Int): List<WebSearchResult>
}
