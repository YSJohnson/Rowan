package com.xiaoqi.companion.core.companion

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.dsl.extension.nodeExecuteMultipleTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStreamingAndSendResults
import ai.koog.agents.core.dsl.extension.onMultipleAssistantMessages
import ai.koog.agents.core.dsl.extension.onMultipleToolCalls
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.result
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponses
import com.xiaoqi.companion.core.llm.KoogPromptExecutorFactory
import com.xiaoqi.companion.core.llm.toKoogModel
import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.core.companion.model.AgentToolCall
import com.xiaoqi.companion.core.companion.model.ToolCallStatus
import com.xiaoqi.companion.core.prompt.BuiltPrompt
import com.xiaoqi.companion.core.tools.AgentToolRegistry
import com.xiaoqi.companion.core.tools.ToolCallRecorder
import com.xiaoqi.companion.core.tools.ToolResultPromptComposer
import com.xiaoqi.companion.core.tools.isError
import com.xiaoqi.companion.core.tools.isErrorResult
import com.xiaoqi.companion.core.tools.normalizeToolResultJson
import com.xiaoqi.companion.core.tools.withErrorResultKind
import com.xiaoqi.companion.core.tools.withoutToolProtocolArtifacts
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.data.repository.LlmConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.serialization.KSerializer
import kotlin.time.Clock

@Singleton
class KoogAgentFactoryImpl @Inject constructor(
    private val executorFactory: KoogPromptExecutorFactory,
    private val toolRegistry: AgentToolRegistry,
    private val toolCallRecorder: ToolCallRecorder,
) : KoogAgentFactory {

    override fun create(config: LlmConfig, sessionId: String): KoogAgentWrapper {
        AppLogger.debug(
            LogTags.Llm,
            "agent_created",
            "provider" to config.provider,
            "model" to config.modelName,
            "hasApiKey" to config.apiKey.isNotBlank(),
        )
        return KoogPromptExecutorWrapper(
            config = config,
            executor = executorFactory.create(config),
            toolRegistry = toolRegistry,
            toolCallRecorder = toolCallRecorder,
            sessionId = sessionId,
        )
    }
}

private class KoogPromptExecutorWrapper(
    private val config: LlmConfig,
    private val executor: PromptExecutor,
    private val toolRegistry: AgentToolRegistry,
    private val toolCallRecorder: ToolCallRecorder,
    private val sessionId: String = DEFAULT_SESSION_ID,
) : KoogAgentWrapper {

    private val model = config.toKoogModel()

    override suspend fun run(prompt: BuiltPrompt): String =
        createAgent(prompt, observer = null)
            .run(prompt.userMessage)
            .withoutToolProtocolArtifacts()

    override suspend fun <T> runStructured(
        prompt: BuiltPrompt,
        serializer: KSerializer<T>,
        examples: List<T>,
    ): T {
        val structuredPrompt = prompt.copy(allowTools = false)
        return executor.executeStructured(
            prompt = structuredPrompt.toKoogAgentPrompt(),
            model = model,
            serializer = serializer,
            examples = examples,
        ).getOrThrow().data
    }

    override fun runStreaming(prompt: BuiltPrompt): Flow<String> =
        runEvents(prompt).mapNotNull { event ->
            (event as? KoogAgentEvent.TextDelta)?.text
        }

    override fun runEvents(prompt: BuiltPrompt): Flow<KoogAgentEvent> = callbackFlow {
        var hasStreamingText = false
        val streamingText = StringBuilder()
        var emittedTextLength = 0

        fun emitSanitizedStreamingText(flush: Boolean) {
            val sanitized = streamingText.toString().withoutToolProtocolArtifacts()
            val targetLength = if (flush) {
                sanitized.length
            } else {
                (sanitized.length - STREAM_PROTOCOL_GUARD_LENGTH).coerceAtLeast(0)
            }
            if (targetLength <= emittedTextLength) return
            trySend(KoogAgentEvent.TextDelta(sanitized.substring(emittedTextLength, targetLength)))
            emittedTextLength = targetLength
            hasStreamingText = true
        }

        val observer = object : KoogAgentObserver {
            override fun onToolUpdated(call: AgentToolCall) {
                trySend(KoogAgentEvent.ToolCallUpdated(call))
            }

            override fun onTextDelta(text: String) {
                streamingText.append(text)
                emitSanitizedStreamingText(flush = false)
            }

            override fun onTextComplete(text: String) {
                if (streamingText.isEmpty() && text.isNotBlank()) {
                    streamingText.append(text)
                }
                emitSanitizedStreamingText(flush = true)
            }

            override fun onProgress(stage: String, message: String) {
                trySend(KoogAgentEvent.Progress(stage, message))
            }
        }

        val job = launch {
            try {
                AppLogger.info(
                    LogTags.Llm,
                    "agent_run_started",
                    "model" to model.id,
                    "allowTools" to prompt.allowTools,
                    "hasImage" to prompt.hasImage,
                    "userMessageLength" to prompt.userMessage.length,
                )
                val result = createAgent(prompt, observer)
                    .run(prompt.userMessage)
                    .withoutToolProtocolArtifacts()
                if (result.isNotBlank()) {
                    observer.onTextComplete(result)
                }
                AppLogger.info(
                    LogTags.Llm,
                    "agent_run_completed",
                    "model" to model.id,
                    "hasStreamingText" to hasStreamingText,
                )
                close()
            } catch (e: Throwable) {
                AppLogger.error(
                    LogTags.Llm,
                    e,
                    "agent_run_failed",
                    "model" to model.id,
                    "hasStreamingText" to hasStreamingText,
                )
                close(e)
            }
        }

        awaitClose { job.cancel() }
    }

    private fun createAgent(
        prompt: BuiltPrompt,
        observer: KoogAgentObserver?,
    ) =
        AIAgent.builder()
            .promptExecutor(executor)
            .llmModel(model)
            .prompt(prompt.toKoogAgentPrompt())
            .toolRegistry(
                if (prompt.hasImage || !prompt.allowTools) {
                    ToolRegistry.EMPTY
                } else {
                    toolRegistry.create(prompt.toolPolicy)
                }
            )
            .maxIterations(MAX_AGENT_ITERATIONS)
            .id("companion-agent-${config.provider.name.lowercase()}")
            .graphStrategy(streamingSingleRunStrategy(prompt, observer))
            .install {
                install(EventHandler.Feature) {
                    onToolCallStarting { context ->
                        val callId = context.toolCallId?.ifBlank { context.eventId } ?: context.eventId
                        val argumentsJson = context.toolArgs.toString()
                        toolCallRecorder.start(
                            sessionId = sessionId,
                            callId = callId,
                            toolName = context.toolName,
                            argumentsJson = argumentsJson,
                        )
                        observer?.onToolUpdated(
                            AgentToolCall(
                                name = context.toolName,
                                status = ToolCallStatus.STARTED,
                                callId = callId,
                                argumentsJson = argumentsJson,
                            )
                        )
                    }
                    onToolCallCompleted { context ->
                        val callId = context.toolCallId?.ifBlank { context.eventId } ?: context.eventId
                        val argumentsJson = context.toolArgs.toString()
                        val resultJson = normalizeToolResultJson(context.toolResult.toString())
                        toolCallRecorder.succeed(callId = callId, resultJson = resultJson)
                        observer?.onToolUpdated(
                            AgentToolCall(
                                name = context.toolName,
                                status = ToolCallStatus.SUCCEEDED,
                                callId = callId,
                                argumentsJson = argumentsJson,
                                resultJson = resultJson,
                            )
                        )
                    }
                    onToolCallFailed { context ->
                        val callId = context.toolCallId?.ifBlank { context.eventId } ?: context.eventId
                        val argumentsJson = context.toolArgs.toString()
                        toolCallRecorder.fail(callId = callId, errorMessage = context.message)
                        observer?.onToolUpdated(
                            AgentToolCall(
                                name = context.toolName,
                                status = ToolCallStatus.FAILED,
                                callId = callId,
                                argumentsJson = argumentsJson,
                                errorMessage = context.message,
                            )
                        )
                    }
                    onToolValidationFailed { context ->
                        val callId = context.toolCallId?.ifBlank { context.eventId } ?: context.eventId
                        val argumentsJson = context.toolArgs.toString()
                        toolCallRecorder.fail(callId = callId, errorMessage = context.message)
                        observer?.onToolUpdated(
                            AgentToolCall(
                                name = context.toolName,
                                status = ToolCallStatus.FAILED,
                                callId = callId,
                                argumentsJson = argumentsJson,
                                errorMessage = context.message,
                            )
                        )
                    }
                    onLLMStreamingFrameReceived { context ->
                        when (val frame = context.streamFrame) {
                            is StreamFrame.TextDelta -> observer?.onTextDelta(frame.text)
                            is StreamFrame.TextComplete -> observer?.onTextComplete(frame.text)
                            else -> Unit
                        }
                    }
                }
            }
            .build()

    @OptIn(InternalAgentsApi::class)
    private fun streamingSingleRunStrategy(
        prompt: BuiltPrompt,
        observer: KoogAgentObserver?,
    ) = strategy<String, String>("single_run_streaming_tools") {
        var toolResultRounds = 0
        val maxToolRounds = prompt.toolPolicy.maxToolRoundsPerTurn
        val nodeCallLLM by nodeLLMRequestStreamingAndSendResults<String>()
        // 并行执行同一轮内的多个工具调用:模型可以自主把互相独立的查询放在一次响应里,不强制串行
        val nodeExecuteTool by nodeExecuteMultipleTools(parallelTools = true)
        val nodeSendToolResult by node<List<ReceivedToolResult>, List<Message.Response>> { results ->
            // 翻 envelope 失败的 resultKind,让 Koog 标准的 Message.Tool.Result.isError 也被点亮
            toolResultRounds += 1
            val patchedResults = results.withErrorResultKind()
            val hasErrors = results.any { it.isErrorResult() }
            val roundLimitReached = toolResultRounds >= maxToolRounds
            val finalWithoutTools = roundLimitReached || hasErrors
            results.forEach { result ->
                AppLogger.info(
                    LogTags.Llm,
                    "tool_execution_result_received",
                    "toolName" to result.tool,
                    "resultKind" to result.resultKind::class.simpleName.orEmpty(),
                    "contentLength" to result.content.length,
                    "contentClass" to result.content.diagnosticContentClass(),
                )
            }
            llm.writeSession {
                appendPrompt {
                    tool {
                        patchedResults.forEach { result(it) }
                    }
                }
                appendPrompt {
                    user(
                        if (finalWithoutTools) {
                            ToolResultPromptComposer.finalWithoutToolsInstruction(
                                hasErrors = hasErrors,
                                roundLimitReached = roundLimitReached,
                            )
                        } else {
                            ToolResultPromptComposer.followupInstruction(hasErrors = false)
                        }
                    )
                }

                if (finalWithoutTools) {
                    tools = emptyList()
                    setToolChoiceNone()
                }

                AppLogger.info(
                    LogTags.Llm,
                    "tool_result_llm_request_started",
                    "resultCount" to results.size,
                    "toolResultRounds" to toolResultRounds,
                    "finalWithoutTools" to finalWithoutTools,
                )
                var responses = requestLLMStreaming()
                    .toList()
                    .toMessageResponses()
                responses = if (finalWithoutTools && responses.any { it is Message.Tool.Call }) {
                    listOf(
                        Message.Assistant(
                            ToolResultPromptComposer.toolLoopFallbackMessage(
                                hasErrors = hasErrors,
                                roundLimitReached = roundLimitReached,
                            ),
                            metaInfo = ResponseMetaInfo(Clock.System.now()),
                        )
                    )
                } else {
                    responses
                }
                AppLogger.info(
                    LogTags.Llm,
                    "tool_result_llm_request_completed",
                    "resultCount" to results.size,
                    "responseCount" to responses.size,
                    "assistantTextLength" to responses.sumOf { it.content.length },
                    "toolCallCount" to responses.count { it is Message.Tool.Call },
                )
                appendPrompt { messages(responses) }
                responses
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onMultipleToolCalls { true })
        edge(
            nodeCallLLM forwardTo nodeFinish
                onMultipleAssistantMessages { true }
                transformed { it.joinToString("\n") { message -> message.content } }
        )

        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        edge(nodeSendToolResult forwardTo nodeExecuteTool onMultipleToolCalls { true })
        edge(
            nodeSendToolResult forwardTo nodeFinish
                onMultipleAssistantMessages { true }
                transformed { it.joinToString("\n") { message -> message.content } }
        )
    }

    private companion object {
        const val MAX_AGENT_ITERATIONS = 12
        const val DEFAULT_SESSION_ID = "default"
        const val STREAM_PROTOCOL_GUARD_LENGTH = 24
    }
}

private fun String.diagnosticContentClass(): String = when {
    isBlank() -> "blank"
    contains("not found", ignoreCase = true) -> "tool_not_found"
    contains("validation", ignoreCase = true) -> "validation_error"
    contains("required", ignoreCase = true) -> "missing_required_argument"
    contains("error", ignoreCase = true) -> "error_payload"
    else -> "payload"
}

private interface KoogAgentObserver {
    fun onToolUpdated(call: AgentToolCall)
    fun onTextDelta(text: String)
    fun onTextComplete(text: String)
    fun onProgress(stage: String, message: String)
}

private fun BuiltPrompt.toKoogAgentPrompt() = prompt("companion-chat") {
    system(systemPrompt)
    if (hasImage && imageBase64 != null) {
        user {
            text(userMessage)
            image(
                ContentPart.Image(
                    content = AttachmentContent.Binary.Base64(imageBase64),
                    format = "base64",
                    mimeType = imageMediaType ?: "image/jpeg",
                )
            )
        }
    } else {
        user(userMessage)
    }
}
