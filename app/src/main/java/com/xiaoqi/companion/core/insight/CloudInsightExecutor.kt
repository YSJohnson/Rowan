package com.xiaoqi.companion.core.insight

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import com.xiaoqi.companion.core.llm.KoogPromptExecutorFactory
import com.xiaoqi.companion.core.llm.toKoogModel
import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.data.repository.ConfigRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 后台洞察（Dream Loop `patternDetect` + 对话后 `conversationReflection`）的云端执行器。
 *
 * 取代原先的 `LocalQwenExecutor`：不再有设备端推理，两处后台链路都走用户当前配置的云端
 * Provider，与主对话共用 [KoogPromptExecutorFactory]，因此 Anthropic / OpenAI 兼容两种协议
 * 的行为完全一致。
 */
@Singleton
class CloudInsightExecutor @Inject constructor(
    private val configRepository: ConfigRepository,
    private val executorFactory: KoogPromptExecutorFactory,
) {

    data class Request(
        val systemPrompt: String,
        val userMessage: String,
        val maxTokens: Int = DEFAULT_MAX_TOKENS,
        val temperature: Double = DEFAULT_TEMPERATURE,
        /** 单次调用上限；后台任务没有用户在等，默认给足云端排队时间。 */
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    )

    data class ExecutionResult(
        val text: String,
        val latencyMs: Long,
        /** null = 成功无错误；非 null = 失败原因，worker 据此走 failure/retry。 */
        val errorMessage: String? = null,
    )

    suspend fun execute(req: Request): ExecutionResult {
        val startedAt = System.currentTimeMillis()
        val config = runCatching { configRepository.getCurrentLlmConfig().first() }
            .getOrElse { error ->
                return failure(startedAt, error, "config_unavailable")
            }
        if (config.apiKey.isBlank()) {
            return failure(startedAt, "Missing API Key", "api_key_missing", config.modelName)
        }

        val outcome = runCatching {
            val executor: PromptExecutor = executorFactory.create(config)
            val koogPrompt = prompt(PROMPT_ID) {
                system(req.systemPrompt)
                user(req.userMessage)
            }.withParams(
                LLMParams(
                    temperature = req.temperature,
                    maxTokens = req.maxTokens,
                )
            )
            withTimeoutOrNull(req.timeoutMs) {
                executor.execute(koogPrompt, config.toKoogModel(), emptyList())
            }
        }

        outcome.getOrElse { error ->
            if (error is CancellationException) throw error
            return failure(startedAt, error, "execute_failed", config.modelName)
        }?.let { responses ->
            val text = responses.filterIsInstance<Message.Assistant>().joinToString("") { it.content }
            return ExecutionResult(
                text = text,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
        }

        return failure(startedAt, "timeout after ${req.timeoutMs}ms", "timeout", config.modelName)
    }

    private fun failure(
        startedAt: Long,
        cause: Any,
        stage: String,
        model: String? = null,
    ): ExecutionResult {
        val message = (cause as? Throwable)?.let { it.message ?: it::class.simpleName.orEmpty() }
            ?: cause.toString()
        AppLogger.warn(
            LogTags.Llm,
            "cloud_insight_execute_failed",
            "stage" to stage,
            "model" to (model ?: "(unknown)"),
            "cause" to message,
            "latencyMs" to (System.currentTimeMillis() - startedAt),
        )
        return ExecutionResult(
            text = "",
            latencyMs = System.currentTimeMillis() - startedAt,
            errorMessage = message,
        )
    }

    companion object {
        const val DEFAULT_MAX_TOKENS = 512
        const val DEFAULT_TEMPERATURE = 0.3
        // 后台任务没有用户等待，给足云端排队时间；超时后按失败处理让 WorkManager 退避重试。
        const val DEFAULT_TIMEOUT_MS = 90_000L
        private const val PROMPT_ID = "companion-insight"
    }
}
