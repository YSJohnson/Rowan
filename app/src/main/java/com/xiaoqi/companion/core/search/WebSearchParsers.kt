package com.xiaoqi.companion.core.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 各家响应体的解析。
 *
 * 抽成纯函数而不是写在 provider 里，是因为 provider 的端点是写死的常量、走 Hilt 注入
 * （`@Inject` 构造不支持默认参数，没法塞 MockWebServer 的地址）。把解析独立出来后，
 * 响应形状的兼容逻辑就能直接用 JSON 字符串做单测，不必起 HTTP。
 */

internal fun JsonObject.arrayOf(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** 只取字符串型字段 —— 数字/布尔一律当空，避免把 `id: 123` 渲染成标题。 */
internal fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

/** 智谱：`{"search_result":[{"title","link","content"}]}`，个别版本用 `search_results`。 */
internal fun parseGlmResults(root: JsonObject): List<WebSearchResult> =
    (root.arrayOf("search_result") + root.arrayOf("search_results")).map { item ->
        WebSearchResult(
            title = item.string("title"),
            url = item.string("link").ifBlank { item.string("url") },
            snippet = item.string("content").ifBlank { item.string("snippet") },
        )
    }

/** Tavily：`{"results":[{"title","url","content"}]}`。 */
internal fun parseTavilyResults(root: JsonObject): List<WebSearchResult> =
    root.arrayOf("results").map { item ->
        WebSearchResult(
            title = item.string("title"),
            url = item.string("url"),
            snippet = item.string("content"),
        )
    }

/** Serper：`{"organic":[{"title","link","snippet"}]}`。 */
internal fun parseSerperResults(root: JsonObject): List<WebSearchResult> =
    root.arrayOf("organic").map { item ->
        WebSearchResult(
            title = item.string("title"),
            url = item.string("link").ifBlank { item.string("url") },
            snippet = item.string("snippet"),
        )
    }

/** 丢掉既没链接又没摘要的空条目 —— 它们对模型没有任何信息量。 */
internal fun List<WebSearchResult>.usable(): List<WebSearchResult> =
    filter { it.url.isNotBlank() || it.snippet.isNotBlank() }
