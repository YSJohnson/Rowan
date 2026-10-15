package com.xiaoqi.companion.core.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 各后端响应形状的兼容性。
 *
 * 这些解析器存在的全部意义就是"形状对不上时不要炸" —— 所以用例重点在别名、缺字段、
 * 类型不对这几种情况，而不是happy path。
 */
class WebSearchParsersTest {

    private fun obj(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `glm parses search_result array`() {
        val results = parseGlmResults(
            obj(
                """
                {"search_result":[
                  {"title":"诺奖公布","link":"https://example.com/a","content":"瑞典皇家科学院…"},
                  {"title":"解读","link":"https://example.com/b","content":"今年的奖项…"}
                ]}
                """.trimIndent()
            )
        )

        assertEquals(2, results.size)
        assertEquals("诺奖公布", results[0].title)
        assertEquals("https://example.com/a", results[0].url)
        assertEquals("瑞典皇家科学院…", results[0].snippet)
    }

    @Test
    fun `glm accepts the search_results alias and url snippet fallbacks`() {
        val results = parseGlmResults(
            obj("""{"search_results":[{"title":"T","url":"https://x.test","snippet":"S"}]}""")
        )

        assertEquals(listOf(WebSearchResult("T", "https://x.test", "S")), results)
    }

    @Test
    fun `tavily parses results array`() {
        val results = parseTavilyResults(
            obj("""{"results":[{"title":"T","url":"https://t.test","content":"C"}]}""")
        )

        assertEquals(listOf(WebSearchResult("T", "https://t.test", "C")), results)
    }

    @Test
    fun `serper parses organic array`() {
        val results = parseSerperResults(
            obj("""{"organic":[{"title":"T","link":"https://s.test","snippet":"S"}]}""")
        )

        assertEquals(listOf(WebSearchResult("T", "https://s.test", "S")), results)
    }

    @Test
    fun `drops entries that carry neither url nor snippet`() {
        val results = parseGlmResults(
            obj("""{"search_result":[{"title":"只有标题"},{"title":"T","link":"https://ok.test"}]}""")
        ).usable()

        assertEquals(1, results.size)
        assertEquals("https://ok.test", results.single().url)
    }

    @Test
    fun `non-string fields are treated as absent`() {
        val results = parseSerperResults(obj("""{"organic":[{"title":42,"link":"https://n.test"}]}"""))

        assertEquals("", results.single().title)
    }

    @Test
    fun `missing container yields an empty list instead of throwing`() {
        assertTrue(parseGlmResults(obj("""{"error":"quota exceeded"}""")).isEmpty())
        assertTrue(parseTavilyResults(obj("""{}""")).isEmpty())
        assertTrue(parseSerperResults(obj("""{"organic":"not-an-array"}""")).isEmpty())
    }

    @Test
    fun `usable keeps entries with either half present`() {
        val kept = listOf(
            WebSearchResult("a", "https://a.test", ""),
            WebSearchResult("b", "", "只有摘要"),
            WebSearchResult("c", "", ""),
        ).usable()

        assertEquals(listOf("a", "b"), kept.map { it.title })
    }
}
