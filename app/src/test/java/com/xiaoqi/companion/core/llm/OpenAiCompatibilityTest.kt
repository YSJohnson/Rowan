package com.xiaoqi.companion.core.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.data.repository.LlmConfig
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibilityTest {
    private fun config(baseUrl: String) = LlmConfig(
        provider = LlmProvider.OPENAI_COMPATIBLE,
        baseUrl = baseUrl,
        apiKey = "test-key",
        modelName = "vendor/custom-model",
    )

    @Test
    fun completion_preservesEndpointPrefixAndToolHistory() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            for ((prefix, path) in listOf(
                "/" to "/v1/chat/completions",
                "/v1" to "/v1/chat/completions",
                "/proxy/v1/" to "/proxy/v1/chat/completions",
            )) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"id":"reply","object":"chat.completion","created":1,"model":"vendor/custom-model","choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}"""
                ))
                val cfg = config(server.url(prefix).toString())
                val client = DefaultKoogPromptExecutorFactory(AnthropicMessagesLLMClientFactory()).create(cfg)
                try {
                    val now = Clock.System.now()
                    val input = prompt("followup") {
                        system("system")
                        user("What time is it?")
                        message(Message.Tool.Call("call-1", "clock", "{}", ResponseMetaInfo(now)))
                        message(Message.Tool.Result("call-1", "clock", "10:00", RequestMetaInfo(now)))
                    }
                    assertEquals("done", client.execute(input, cfg.toKoogModel(), emptyList()).single().content)
                    val recorded = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    assertEquals(path, recorded.path)
                    assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
                    assertNull(recorded.getHeader("x-api-key"))
                    val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
                    assertEquals(cfg.modelName, body.getValue("model").jsonPrimitive.content)
                    val messages = body.getValue("messages").jsonArray
                    assertEquals("assistant", messages[2].jsonObject.getValue("role").jsonPrimitive.content)
                    assertEquals("call-1", messages[2].jsonObject.getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
                    assertEquals("tool", messages[3].jsonObject.getValue("role").jsonPrimitive.content)
                    assertEquals("call-1", messages[3].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
                } finally {
                    client.close()
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun streaming_accumulatesInterleavedToolArgumentsAndStopsAtDone() = runTest {
        val server = MockWebServer()
        server.start()
        val cfg = config(server.url("/gateway/v1/").toString())
        val client = OpenAiCompatibleLLMClient(cfg.apiKey, openAiClientSettings(cfg.baseUrl))
        try {
            val chunks = listOf(
                """{"content":"checking ","tool_calls":[{"index":0,"id":"a_","type":"function","function":{"name":"fir","arguments":"{\"x\":"}},{"index":1,"id":"b","type":"function","function":{"name":"second","arguments":"{\"y\":"}}]}""",
                """{"tool_calls":[{"index":1,"id":"","function":{"arguments":"2}"}},{"index":0,"id":"123","function":{"name":"st","arguments":"1}"}}]}""",
            )
            val stream = chunks.joinToString("") { delta ->
                "data: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"vendor/custom-model\",\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":null}]}\n\n"
            } + "data: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"vendor/custom-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream))
            val frames = client.executeStreaming(prompt("stream") { user("check") }, cfg.toKoogModel(), emptyList()).toList()
            assertTrue(frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text }.contains("checking"))
            val tools = frames.filterIsInstance<StreamFrame.ToolCallComplete>().associateBy { it.id }
            assertEquals("first", tools.getValue("a_123").name)
            assertEquals("{\"x\":1}", tools.getValue("a_123").content)
            assertEquals("{\"y\":2}", tools.getValue("b").content)
            assertEquals("tool_calls", frames.filterIsInstance<StreamFrame.End>().single().finishReason)
            assertEquals("/gateway/v1/chat/completions", checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun connectivity_usesBearerAndPreservesPrefix() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200))
            assertTrue(LlmConnectivityChecker().check(config(server.url("/proxy/v1/").toString())) is ConnectivityResult.Success)
            val recorded = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/proxy/v1/models", recorded.path)
            assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
            assertNull(recorded.getHeader("anthropic-version"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun streaming_doesNotCompleteToolCallsWhenTruncatedOrFiltered() = runTest {
        val server = MockWebServer()
        server.start()
        val cfg = config(server.url("/v1/").toString())
        val client = OpenAiCompatibleLLMClient(cfg.apiKey, openAiClientSettings(cfg.baseUrl))
        try {
            for (reason in listOf("null", "\"length\"", "\"content_filter\"")) {
                val chunk = """{"id":"r","object":"chat.completion.chunk","created":1,"model":"custom","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"a","type":"function","function":{"name":"clock","arguments":"{}"}}]},"finish_reason":$reason}]}"""
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: [DONE]\n\n"))
                assertTrue(runCatching { client.executeStreaming(prompt("truncated") { user("check") }, cfg.toKoogModel(), emptyList()).toList() }.isFailure)
            }
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun endpoint_rejectsCredentialsQueriesAndInvalidSchemes() {
        for (url in listOf("not a url", "ftp://example.com", "https://user:pass@example.com", "https://example.com?key=secret")) {
            assertTrue(url, runCatching { openAiClientSettings(url) }.isFailure)
        }
    }
}
