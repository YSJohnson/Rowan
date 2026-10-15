package com.xiaoqi.companion.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelListParserTest {

    @Test
    fun `parses openai data array`() {
        val body = """
            {"object":"list","data":[
              {"id":"gpt-4o-mini","object":"model","owned_by":"openai"},
              {"id":"gpt-4o","object":"model","owned_by":"openai"}
            ]}
        """.trimIndent()

        assertEquals(listOf("gpt-4o-mini", "gpt-4o"), LlmModelListParser.parse(body))
    }

    @Test
    fun `parses anthropic data array with display_name`() {
        val body = """
            {"data":[{"id":"claude-3-5-sonnet","display_name":"Claude 3.5 Sonnet"}],"has_more":false}
        """.trimIndent()

        assertEquals(listOf("claude-3-5-sonnet"), LlmModelListParser.parse(body))
    }

    @Test
    fun `parses google models array keyed by name`() {
        val body = """{"models":[{"name":"models/gemini-1.5-pro"}]}"""

        assertEquals(listOf("models/gemini-1.5-pro"), LlmModelListParser.parse(body))
    }

    @Test
    fun `parses plain string array`() {
        val body = """{"data":["qwen-plus","qwen-max"]}"""

        assertEquals(listOf("qwen-plus", "qwen-max"), LlmModelListParser.parse(body))
    }

    @Test
    fun `keeps provider order and drops duplicates`() {
        val body = """{"data":[{"id":"b"},{"id":"a"},{"id":"b"}]}"""

        assertEquals(listOf("b", "a"), LlmModelListParser.parse(body))
    }

    @Test
    fun `ignores entries containing whitespace or oversized ids`() {
        val body = """{"data":[{"id":"not a model"},{"id":"${"x".repeat(200)}"},{"id":"ok"}]}"""

        assertEquals(listOf("ok"), LlmModelListParser.parse(body))
    }

    @Test
    fun `returns empty for blank malformed or unrelated bodies`() {
        assertTrue(LlmModelListParser.parse("").isEmpty())
        assertTrue(LlmModelListParser.parse("   ").isEmpty())
        assertTrue(LlmModelListParser.parse("<html>502 Bad Gateway</html>").isEmpty())
        assertTrue(LlmModelListParser.parse("""{"foo":"bar"}""").isEmpty())
        assertTrue(LlmModelListParser.parse("""[{"id":"top-level-array"}]""").isEmpty())
    }
}
