package com.xiaoqi.companion.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免密钥后端靠解析 Bing 的结果页 HTML，所以这些用例是它的**唯一保障** ——
 * 抓页面没有契约可依，只能把真实结构固定下来，改版时测试先红。
 */
class BingResultParserTest {

    private val html = """
        <html><body><ol id="b_results">
          <li class="b_algo" data-bm="8">
            <h2><a href="https://tags.news.sina.com.cn/ai" h="ID=SERP,1">人工智能 - 最新<b>人工智能</b>快讯</a></h2>
            <div><p class="b_lineclamp4">11 小时之前&ensp;&#0183;&ensp;首届上海&ldquo;人工智能+消费&rdquo;创新大赛举行 &amp; 更多</p></div>
          </li>
          <li class="b_algo">
            <h2><a href="https://example.com/b">第二条结果</a></h2>
            <p>第二条摘要</p>
          </li>
        </ol></body></html>
    """.trimIndent()

    @Test
    fun `parses title url and snippet from b_algo blocks`() {
        val results = BingResultParser.parse(html, maxResults = 5)

        assertEquals(2, results.size)
        assertEquals("https://tags.news.sina.com.cn/ai", results[0].url)
        assertEquals("第二条结果", results[1].title)
        assertEquals("第二条摘要", results[1].snippet)
    }

    @Test
    fun `strips inner tags and decodes entities`() {
        val first = BingResultParser.parse(html, maxResults = 5).first()

        assertEquals("人工智能 - 最新人工智能快讯", first.title)
        // &ensp; 与 &#0183; 是 Bing 摘要里的常客，不还原的话读起来全是乱码
        assertEquals("11 小时之前 · 首届上海“人工智能+消费”创新大赛举行 & 更多", first.snippet)
    }

    @Test
    fun `respects the requested result count`() {
        assertEquals(1, BingResultParser.parse(html, maxResults = 1).size)
    }

    @Test
    fun `skips blocks that carry no link`() {
        val withNoise = """
            <ol>
              <li class="b_algo"><div>没有链接的块</div></li>
              <li class="b_algo"><h2><a href="https://ok.test">有效结果</a></h2><p>摘要</p></li>
            </ol>
        """.trimIndent()

        val results = BingResultParser.parse(withNoise, maxResults = 5)

        assertEquals(1, results.size)
        assertEquals("https://ok.test", results.single().url)
    }

    @Test
    fun `returns empty instead of throwing on blank or restructured pages`() {
        assertTrue(BingResultParser.parse("", maxResults = 5).isEmpty())
        assertTrue(BingResultParser.parse("   ", maxResults = 5).isEmpty())
        assertTrue(BingResultParser.parse("<html><body>验证码页面</body></html>", maxResults = 5).isEmpty())
    }

    @Test
    fun `decodes named and numeric entities`() {
        assertEquals("a & b < c > d ‘e’ “f”…", BingResultParser.decodeHtmlEntities("a &amp; b &lt; c &gt; d &lsquo;e&rsquo; &ldquo;f&rdquo;&hellip;"))
        assertEquals("A中B", BingResultParser.decodeHtmlEntities("A&#20013;B"))
        assertEquals("x·y", BingResultParser.decodeHtmlEntities("x&#0183;y"))
        // 未收录的实体原样保留，不要吃字符
        assertEquals("&unknown;", BingResultParser.decodeHtmlEntities("&unknown;"))
    }
}
