package com.xiaoqi.companion.core.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.core.search.WebSearchException
import com.xiaoqi.companion.core.search.WebSearchRouter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 联网搜索。**由模型自己决定何时调用**，没有任何关键词拦截 —— 描述里写清楚"什么时候该搜、
 * 什么时候不该搜"，剩下交给 tool-use 循环判断。
 *
 * 失败一律走 envelope 错误，让模型能区分"密钥错了别重试"和"网络抖了可以重试"，
 * 而不是笼统地报一句失败。
 */
class WebSearchTool @Inject constructor(
    private val router: WebSearchRouter,
) : SimpleTool<WebSearchTool.Args>(
    typeToken<Args>(),
    name = "web_search",
    description = "Search the live web and return ranked results with title, URL and snippet. " +
        "Call this whenever the answer depends on facts that may have changed since your training " +
        "data — news, prices, releases, schedules, scores, product or API documentation, or any " +
        "specific claim you would otherwise have to guess at. Also call it when the user explicitly " +
        "asks you to look something up. Do not call it for the user's personal memories, stored " +
        "preferences, or casual conversation. When you use results, tell the user which source the " +
        "information came from.",
) {

    @Serializable
    data class Args(
        @param:LLMDescription(
            "Search keywords written the way a search engine expects: keep the user's language, " +
                "include the year or an explicit date range for time-sensitive topics, and drop " +
                "conversational filler. Example: '2026 年 诺贝尔物理学奖 得主'.",
        )
        val query: String,
        @param:LLMDescription("How many results to return, 1 to 10. Use 3-5 unless the user asks for a broad survey.")
        val maxResults: Int = 5,
    )

    override suspend fun execute(args: Args): String = withContext(Dispatchers.IO) {
        val query = args.query.trim()
        if (query.isEmpty()) {
            return@withContext encode(
                ToolEnvelopeFactory.err(
                    reason = "empty_query",
                    hint = "搜索词是空的。请用一句具体的检索式重新调用，不要把用户的整句话原样丢进来。",
                )
            )
        }
        try {
            val response = router.search(query, args.maxResults.coerceIn(1, MAX_RESULTS))
            AppLogger.info(
                LogTags.Tools,
                "web_search_completed",
                "backend" to response.backend,
                "resultCount" to response.results.size,
                "queryLength" to query.length,
            )
            encode(
                ToolEnvelopeFactory.ok(
                    buildJsonObject {
                        put("query", query)
                        put("backend", response.backend.name)
                        put("resultCount", response.results.size)
                        putJsonArray("results") {
                            response.results.forEach { result ->
                                addJsonObject {
                                    put("title", result.title)
                                    put("url", result.url)
                                    put("snippet", result.snippet)
                                }
                            }
                        }
                    }
                )
            )
        } catch (e: WebSearchException) {
            AppLogger.warn(
                LogTags.Tools,
                "web_search_failed",
                "reason" to e.reason,
                "queryLength" to query.length,
            )
            encode(ToolEnvelopeFactory.err(reason = e.reason, hint = e.hint))
        } catch (e: Exception) {
            AppLogger.error(LogTags.Tools, e, "web_search_unexpected_failure")
            encode(
                ToolEnvelopeFactory.err(
                    reason = "search_failed",
                    hint = "联网搜索出错了（${e.message ?: e::class.simpleName.orEmpty()}）。" +
                        "请先凭已有知识回答，并说明这次没能联网查证。",
                )
            )
        }
    }

    private companion object {
        const val MAX_RESULTS = 10
    }
}
