package com.xiaoqi.companion.core.llm

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionStreamResponse
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class OpenAiCompatibleLLMClient(
    apiKey: String,
    settings: OpenAIClientSettings,
) : OpenAILLMClient(apiKey, settings) {
    // Koog 0.8 的 frame builder 按当前工具切换块，交错 index 会提前截断参数。
    override fun processStreamingResponse(response: Flow<OpenAIChatCompletionStreamResponse>): Flow<StreamFrame> = flow {
        val tools = linkedMapOf<Int, PendingTool>()
        val text = StringBuilder()
        var finishReason: String? = null
        var metaInfo = createMetaInfo(null)
        response.collect { chunk ->
            chunk.choices.firstOrNull()?.let { choice ->
                choice.delta.content?.let { delta ->
                    text.append(delta)
                    emit(StreamFrame.TextDelta(delta, choice.index))
                }
                choice.delta.toolCalls.orEmpty().forEach { delta ->
                    val tool = tools.getOrPut(delta.index) { PendingTool() }
                    delta.id?.let { tool.id.append(it) }
                    delta.function?.name?.let { tool.name.append(it) }
                    delta.function?.arguments?.let { tool.arguments.append(it) }
                    emit(StreamFrame.ToolCallDelta(delta.id, delta.function?.name, delta.function?.arguments, delta.index))
                }
                choice.finishReason?.let { finishReason = it }
            }
            chunk.usage?.let { metaInfo = createMetaInfo(it) }
        }
        check(tools.isEmpty() || finishReason == "tool_calls" || finishReason == "stop") {
            "OpenAI 工具调用流未正常结束: $finishReason"
        }
        if (text.isNotEmpty()) emit(StreamFrame.TextComplete(text.toString(), 0))
        tools.forEach { (index, tool) ->
            check(tool.name.isNotEmpty() && tool.id.isNotBlank()) { "OpenAI 工具调用缺少名称或 ID" }
            emit(StreamFrame.ToolCallComplete(tool.id.toString(), tool.name.toString(), tool.arguments.toString().ifBlank { "{}" }, index))
        }
        emit(StreamFrame.End(finishReason, metaInfo))
    }

    private class PendingTool {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
}
