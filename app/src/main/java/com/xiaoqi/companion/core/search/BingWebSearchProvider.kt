package com.xiaoqi.companion.core.search

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * 免密钥搜索后端：抓取 cn.bing.com 的结果页。
 *
 * 存在的理由是**零配置可用** —— 用户用什么对话模型都行，不需要再去申请第二个服务的密钥。
 * 代价是依赖页面结构，Bing 改版时可能失效（表现为返回空列表，上层会提示换后端）。
 * 想要更稳的结果可以切到 GLM / Tavily / Serper。
 *
 * 用 cn.bing.com 而不是 www.bing.com：前者在国内网络可达，且中文结果的排序更合适。
 */
@Singleton
class BingWebSearchProvider @Inject constructor(
    private val client: OkHttpClient,
) : WebSearchProvider {

    override val backend: WebSearchBackend = WebSearchBackend.BING

    /** 免密钥，永远可用 —— 所以它是路由兜底的最后一环。 */
    override suspend fun isConfigured(): Boolean = true

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("setlang", "zh-CN")
            .build()
            .toString()
        val html = getHtml(client = client, backend = backend, url = url)
        return BingResultParser.parse(html, maxResults).usable()
    }

    private companion object {
        const val ENDPOINT = "https://cn.bing.com/search"
    }
}
