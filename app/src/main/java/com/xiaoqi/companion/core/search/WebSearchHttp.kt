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
 * 三个搜索后端都是「POST 一个 JSON、拿回一个 JSON」，差异只在 URL / 鉴权头 / 请求体字段。
 * 所以传输层收在这一处，provider 只负责拼请求体和解析响应形状。
 *
 * `query` 只以长度记日志、不落原文 —— 搜索词是用户隐私，日志里不该出现完整内容。
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
        throw WebSearchException(
            reason = "search_unreachable",
            hint = "搜索服务连不上（${e.message ?: "网络异常"}）。请告诉用户稍后重试，或先凭已有知识回答并说明没有联网。",
            cause = e,
        )
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
            throw when (res.code) {
                401, 403 -> WebSearchException(
                    reason = "search_auth_failed",
                    hint = "搜索服务的密钥无效或没有权限（HTTP ${res.code}）。请让用户到「设置 > 联网搜索」检查密钥，不要重试同一个请求。",
                )
                429 -> WebSearchException(
                    reason = "search_rate_limited",
                    hint = "搜索服务限流了（HTTP 429）。请告诉用户稍后再试，不要连续重试。",
                )
                else -> WebSearchException(
                    reason = "search_http_error",
                    hint = "搜索服务返回 HTTP ${res.code}。请先凭已有知识回答，并说明这次没能联网查证。",
                )
            }
        }
        AppLogger.info(
            LogTags.Tools,
            "web_search_request_completed",
            "backend" to backend,
            "statusCode" to res.code,
            "bodyLength" to text.length,
            "durationMs" to duration,
        )
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw WebSearchException(
                reason = "search_bad_response",
                hint = "搜索服务返回的内容无法解析。请先凭已有知识回答，并说明这次没能联网查证。",
            )
    }
}
