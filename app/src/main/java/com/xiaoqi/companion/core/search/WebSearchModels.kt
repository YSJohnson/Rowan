package com.xiaoqi.companion.core.search

/**
 * 联网搜索的后端。
 *
 * [GLM] 复用对话用的智谱密钥（同一账号的 Web Search API），用户不需要额外申请 ——
 * 这是默认值，保证"装了就能搜"。另两个需要单独申请密钥。
 */
enum class WebSearchBackend {
    GLM,
    TAVILY,
    SERPER,
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
