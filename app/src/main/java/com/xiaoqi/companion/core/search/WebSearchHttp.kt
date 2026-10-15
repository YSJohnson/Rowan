package com.xiaoqi.companion.core.search

import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
private val json = Json { ignoreUnknownKeys = true }

/**
 * 抓取 HTML 页面（免密钥后端用）。
 *
 * 带桌面浏览器 UA：Bing 对 `okhttp/x.y` 这类默认 UA 会返回精简页甚至直接拒绝，
 * 换成浏览器 UA 才拿得到完整的 `b_algo` 结果块。
 */
internal suspend fun getHtml(
    client: OkHttpClient,
    backend: WebSearchBackend,
    url: String,
    acceptLanguage: String = "zh-CN,zh;q=0.9,en;q=0.8",
): String = withContext(Dispatchers.IO) {
    val startedAt = System.currentTimeMillis()
    AppLogger.info(LogTags.Tools, "web_search_request_started", "backend" to backend)

    val request = Request.Builder()
        .url(url)
        .header("User-Agent", DESKTOP_USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml")
        .header("Accept-Language", acceptLanguage)
        .get()
        .build()

    val text = execute(client, backend, startedAt, request, hasApiKey = false)
    text
}

/**
 * 几个 JSON 后端都是「POST 一个 JSON、拿回一个 JSON」，差异只在 URL / 鉴权头 / 请求体字段。
 * 所以传输层收在这一处，provider 只负责拼请求体和解析响应形状。
 *
 * 日志只记搜索词长度、不记原文 —— 检索词是用户隐私。
 */
internal suspend fun postSearchJson(
    client: OkHttpClient,
    backend: WebSearchBackend,
    url: String,
    headers: Map<String, String>,
    body: JsonObject,
): JsonObject = withContext(Dispatchers.IO) {
    val startedAt = System.currentTimeMillis()
    AppLogger.info(LogTags.Tools, "web_search_request_started", "backend" to backend)

    val request = Request.Builder()
        .url(url)
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType))
        .build()

    val text = execute(client, backend, startedAt, request, hasApiKey = true)
    runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: throw WebSearchException(
            reason = "search_bad_response",
            hint = "搜索服务返回的内容无法解析。请先凭已有知识回答，并说明这次没能联网查证。",
        )
}

/** 两个传输入口共用的执行 + 错误映射。调用方必须已在 IO 线程上。 */
private fun execute(
    client: OkHttpClient,
    backend: WebSearchBackend,
    startedAt: Long,
    request: Request,
    hasApiKey: Boolean,
): String {
    val response = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        AppLogger.warn(
            LogTags.Tools,
            "web_search_request_unreachable",
            "backend" to backend,
            "cause" to (e.message ?: e::class.simpleName.orEmpty()),
            "durationMs" to (System.currentTimeMillis() - startedAt),
        )
        throw searchUnreachable(e)
    }

    response.use { res ->
        val text = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
        val duration = System.currentTimeMillis() - startedAt
        if (!res.isSuccessful) {
            AppLogger.warn(
                LogTags.Tools,
                "web_search_request_http_error",
                "backend" to backend,
                "statusCode" to res.code,
                "bodyLength" to text.length,
                "durationMs" to duration,
            )
            throw httpFailure(res.code, hasApiKey)
        }
        AppLogger.info(
            LogTags.Tools,
            "web_search_request_completed",
            "backend" to backend,
            "statusCode" to res.code,
            "bodyLength" to text.length,
            "durationMs" to duration,
        )
        return text
    }
}

private fun searchUnreachable(e: IOException) = WebSearchException(
    reason = "search_unreachable",
    hint = "搜索服务连不上（${e.message ?: "网络异常"}）。请告诉用户稍后重试，或先凭已有知识回答并说明没有联网。",
    cause = e,
)

private fun httpFailure(code: Int, hasApiKey: Boolean): WebSearchException = when {
    code == 401 || code == 403 -> WebSearchException(
        reason = "search_auth_failed",
        hint = "搜索服务的密钥无效或没有权限（HTTP $code）。请让用户到「设置 > 联网搜索」检查密钥，不要重试同一个请求。",
    )
    code == 429 -> WebSearchException(
        reason = "search_rate_limited",
        hint = "搜索服务限流了（HTTP 429）。请告诉用户稍后再试，不要连续重试。",
    )
    else -> WebSearchException(
        reason = "search_http_error",
        hint = if (hasApiKey) {
            "搜索服务返回 HTTP $code。请先凭已有知识回答，并说明这次没能联网查证。"
        } else {
            "搜索引擎返回 HTTP $code（可能是被限流或页面改版）。请让用户在「设置 > 联网搜索」换成需要密钥的后端，或稍后重试。"
        },
    )
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"
