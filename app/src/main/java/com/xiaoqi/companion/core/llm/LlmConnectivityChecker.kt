package com.xiaoqi.companion.core.llm

import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.data.repository.LlmConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 模型连通性检查结果。
 *
 * - [Success]: 端点可达 + 鉴权有效(200/204)。携带延迟和模型名。
 * - [AuthFailure]: 端点可达但鉴权失败(401/403)。区分"配置问题"与"网络问题"。
 * - [Unreachable]: 端点不可达(IOException、timeout、URL 解析失败等)。
 */
sealed class ConnectivityResult {
    data class Success(
        val latencyMs: Long,
        val modelName: String,
    ) : ConnectivityResult()

    data class AuthFailure(
        val statusCode: Int,
    ) : ConnectivityResult()

    data class Unreachable(
        val cause: String,
    ) : ConnectivityResult()

    val isHealthy: Boolean
        get() = this is Success
}

/**
 * 模型清单探测结果。与 [ConnectivityResult] 分开是因为"端点通"和"能列出模型"是两件事：
 * 不少 Anthropic 兼容端点不实现 `/v1/models`，这时端点仍然是可用的。
 */
sealed class ModelProbeResult {
    data class Success(
        val models: List<String>,
        val latencyMs: Long,
    ) : ModelProbeResult()

    data class AuthFailure(
        val statusCode: Int,
    ) : ModelProbeResult()

    data class Unreachable(
        val cause: String,
    ) : ModelProbeResult()
}

/**
 * 轻量模型连通性 + 模型清单探测:不依赖 Koog / Tool Registry,直接用 OkHttp 探测 baseUrl。
 *
 * 探测策略:
 * - GLM/Kimi/魔搭兼容 Anthropic Messages 协议,统一 `GET {baseUrl}/v1/models` + `x-api-key`
 * - OpenAI 兼容服务商同路径,但走 `Authorization: Bearer`
 *
 * 超时配置:connectTimeout 5s + readTimeout 8s — 必须快速失败,避免阻塞设置 UI。
 *
 * 线程:挂起 + `withContext(Dispatchers.IO)`,调用方在 ViewModel scope 中 launch。
 */
@Singleton
class LlmConnectivityChecker @Inject constructor() {

    // 测试可通过次构造注入 mock OkHttpClient;生产主构造走默认 OkHttp。
    constructor(injectedClient: OkHttpClient) : this() {
        this.injectedClient = injectedClient
    }

    private var injectedClient: OkHttpClient? = null

    private val client: OkHttpClient
        get() = injectedClient ?: OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    suspend fun check(config: LlmConfig): ConnectivityResult = withContext(Dispatchers.IO) {
        val request = runCatching { modelsRequest(config) }.getOrNull()
            ?: return@withContext ConnectivityResult.Unreachable("Base URL 解析失败")
        val startedAt = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startedAt
                val result = when (response.code) {
                    200, 204 -> ConnectivityResult.Success(
                        latencyMs = latency,
                        modelName = config.modelName,
                    )
                    401, 403 -> ConnectivityResult.AuthFailure(statusCode = response.code)
                    else -> ConnectivityResult.Unreachable(cause = "HTTP ${response.code}")
                }
                AppLogger.info(
                    LogTags.Config,
                    "llm_connectivity_check_completed",
                    "provider" to config.provider,
                    "model" to config.modelName,
                    "latencyMs" to latency,
                    "outcome" to result::class.simpleName,
                )
                result
            }
        } catch (e: IOException) {
            val cause = e.message ?: e::class.simpleName.orEmpty()
            AppLogger.warn(
                LogTags.Config,
                "llm_connectivity_check_unreachable",
                "provider" to config.provider,
                "cause" to cause,
            )
            ConnectivityResult.Unreachable(cause = cause)
        }
    }

    /**
     * 拉取服务商真实可用的模型 ID 列表，供设置页下拉选择。
     *
     * 端点存在但返回体解析不出模型时返回 [ModelProbeResult.Success] + 空列表 —— 调用方据
     * "列表为空"提示用户回退到手填，而不是报"探测失败"。
     */
    suspend fun listModels(config: LlmConfig): ModelProbeResult = withContext(Dispatchers.IO) {
        val request = runCatching { modelsRequest(config) }.getOrNull()
            ?: return@withContext ModelProbeResult.Unreachable("Base URL 解析失败")
        val startedAt = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startedAt
                val result = when {
                    response.code == 401 || response.code == 403 ->
                        ModelProbeResult.AuthFailure(statusCode = response.code)
                    !response.isSuccessful ->
                        ModelProbeResult.Unreachable("HTTP ${response.code}")
                    else -> ModelProbeResult.Success(
                        models = LlmModelListParser.parse(response.bodyOrEmpty()),
                        latencyMs = latency,
                    )
                }
                AppLogger.info(
                    LogTags.Config,
                    "llm_model_probe_completed",
                    "provider" to config.provider,
                    "latencyMs" to latency,
                    "modelCount" to ((result as? ModelProbeResult.Success)?.models?.size ?: -1),
                    "outcome" to result::class.simpleName,
                )
                result
            }
        } catch (e: IOException) {
            val cause = e.message ?: e::class.simpleName.orEmpty()
            AppLogger.warn(
                LogTags.Config,
                "llm_model_probe_unreachable",
                "provider" to config.provider,
                "cause" to cause,
            )
            ModelProbeResult.Unreachable(cause = cause)
        }
    }

    private fun modelsRequest(config: LlmConfig): Request {
        val endpoint = openAiClientSettings(config.baseUrl)
        return Request.Builder()
            .url(endpoint.baseUrl + endpoint.modelsPath)
            .apply {
                if (config.provider.usesOpenAiProtocol) {
                    header("Authorization", "Bearer ${config.apiKey}")
                } else {
                    header("x-api-key", config.apiKey)
                    header("anthropic-version", ANTHROPIC_VERSION)
                }
            }
            .get()
            .build()
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val READ_TIMEOUT_SECONDS = 8L
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/** 响应体读取失败（连接被对端提前关闭等）不该让整次探测崩掉，退回空串。 */
private fun Response.bodyOrEmpty(): String =
    runCatching { body?.string().orEmpty() }.getOrDefault("")
