package com.xiaoqi.companion.core.llm

import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.data.repository.LlmConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmConnectivityCheckerTest {

    private fun buildResponse(code: Int): Response {
        val request = Request.Builder().url("https://example.test/v1/models").build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("status $code")
            .body("".toResponseBody(null))
            .build()
    }

    @Test
    fun check_glmProvider_returnsSuccessOn200() = runTest {
        val call = mockk<Call>(relaxed = true) {
            every { execute() } returns buildResponse(200)
        }
        val client = mockk<OkHttpClient> {
            every { newCall(any()) } returns call
        }
        val checker = LlmConnectivityChecker(client)
        val config = LlmConfig(
            provider = LlmProvider.GLM,
            baseUrl = "https://open.bigmodel.cn/api/anthropic",
            apiKey = "test-key",
            modelName = "glm-5v-turbo",
        )

        val result = checker.check(config)

        assertTrue(result is ConnectivityResult.Success)
        assertEquals("glm-5v-turbo", (result as ConnectivityResult.Success).modelName)
    }

    @Test
    fun check_unauthorized_returnsAuthFailure() = runTest {
        val call = mockk<Call>(relaxed = true) {
            every { execute() } returns buildResponse(401)
        }
        val client = mockk<OkHttpClient> {
            every { newCall(any()) } returns call
        }
        val checker = LlmConnectivityChecker(client)
        val config = LlmConfig(
            provider = LlmProvider.KIMI,
            baseUrl = "https://api.kimi.com/coding",
            apiKey = "bad-key",
            modelName = "kimi-for-coding",
        )

        val result = checker.check(config)

        assertTrue(result is ConnectivityResult.AuthFailure)
        assertEquals(401, (result as ConnectivityResult.AuthFailure).statusCode)
    }

    @Test
    fun check_403_returnsAuthFailure() = runTest {
        val call = mockk<Call>(relaxed = true) {
            every { execute() } returns buildResponse(403)
        }
        val client = mockk<OkHttpClient> {
            every { newCall(any()) } returns call
        }
        val checker = LlmConnectivityChecker(client)
        val config = LlmConfig(
            provider = LlmProvider.GLM,
            baseUrl = "https://open.bigmodel.cn/api/anthropic",
            apiKey = "denied",
            modelName = "glm-5v-turbo",
        )

        val result = checker.check(config)

        assertTrue(result is ConnectivityResult.AuthFailure)
        assertEquals(403, (result as ConnectivityResult.AuthFailure).statusCode)
    }

    @Test
    fun check_serverError_returnsUnreachable() = runTest {
        val call = mockk<Call>(relaxed = true) {
            every { execute() } returns buildResponse(500)
        }
        val client = mockk<OkHttpClient> {
            every { newCall(any()) } returns call
        }
        val checker = LlmConnectivityChecker(client)
        val config = LlmConfig(
            provider = LlmProvider.GLM,
            baseUrl = "https://open.bigmodel.cn/api/anthropic",
            apiKey = "k",
            modelName = "glm-5v-turbo",
        )

        val result = checker.check(config)

        assertTrue(result is ConnectivityResult.Unreachable)
        assertEquals("HTTP 500", (result as ConnectivityResult.Unreachable).cause)
    }

    @Test
    fun check_ioException_returnsUnreachable() = runTest {
        val call = mockk<Call>(relaxed = true) {
            every { execute() } throws java.io.IOException("connection refused")
        }
        val client = mockk<OkHttpClient> {
            every { newCall(any()) } returns call
        }
        val checker = LlmConnectivityChecker(client)
        val config = LlmConfig(
            provider = LlmProvider.GLM,
            baseUrl = "https://open.bigmodel.cn/api/anthropic",
            apiKey = "k",
            modelName = "glm-5v-turbo",
        )

        val result = checker.check(config)

        assertTrue(result is ConnectivityResult.Unreachable)
        assertEquals("connection refused", (result as ConnectivityResult.Unreachable).cause)
    }

    @Test
    fun check_anthropicStyleProvider_usesApiKeyHeader() = runTest {
        val capturedSlot = slot<Request>()
        val call = mockk<Call>(relaxed = true) {
            every { execute() } returns buildResponse(200)
        }
        val client = mockk<OkHttpClient> {
            every { newCall(capture(capturedSlot)) } returns call
        }
        val checker = LlmConnectivityChecker(client)

        checker.check(
            LlmConfig(
                provider = LlmProvider.GLM,
                baseUrl = "https://open.bigmodel.cn/api/anthropic",
                apiKey = "glm-key",
                modelName = "glm-5v-turbo",
            )
        )

        val request = capturedSlot.captured
        assertEquals("glm-key", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertEquals(null, request.header("Authorization"))
        assertEquals("https://open.bigmodel.cn/api/anthropic/v1/models", request.url.toString())
    }

    @Test
    fun check_openAiCompatibleProvider_usesBearerAuthorization() = runTest {
        val capturedSlot = slot<Request>()
        val call = mockk<Call>(relaxed = true) {
            every { execute() } returns buildResponse(200)
        }
        val client = mockk<OkHttpClient> {
            every { newCall(capture(capturedSlot)) } returns call
        }
        val checker = LlmConnectivityChecker(client)

        val result = checker.check(
            LlmConfig(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                baseUrl = "https://example.test/proxy/v1",
                apiKey = "openai-key",
                modelName = "vendor/custom-model",
            )
        )

        assertTrue(result is ConnectivityResult.Success)
        val request = capturedSlot.captured
        assertEquals("Bearer openai-key", request.header("Authorization"))
        assertEquals(null, request.header("x-api-key"))
        assertEquals("https://example.test/proxy/v1/models", request.url.toString())
    }

    @Test
    fun check_invalidBaseUrl_returnsUnreachable() = runTest {
        val client = mockk<OkHttpClient>(relaxed = true)
        val checker = LlmConnectivityChecker(client)
        val config = LlmConfig(
            provider = LlmProvider.GLM,
            baseUrl = "not a url",
            apiKey = "k",
            modelName = "glm-5v-turbo",
        )

        val result = checker.check(config)

        assertTrue(result is ConnectivityResult.Unreachable)
        assertTrue(
            "expected cause to mention 解析失败, was: ${(result as ConnectivityResult.Unreachable).cause}",
            result.cause.contains("解析失败"),
        )
    }
}
