package com.xiaoqi.companion.core.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三层解析容错的行为契约。原属 `LocalQwenExecutorTest`，端侧执行器下线后迁到云端
 * 洞察链路的公共解析入口 [InsightOutputParser]。
 */
class InsightOutputParserTest {

    // ── Layer 1: 标准 JSON ──

    @Test
    fun parse_validJson_returnsDrafts() {
        val raw = """
            [
              { "headline": "周日下午情绪偏低", "body": "连续 3 周", "evidence_ids": ["m1", "m2"], "confidence": 0.78 },
              { "headline": "睡眠关键词上升", "body": "你最近提到 4 次", "evidence_ids": [], "confidence": 0.65 }
            ]
        """.trimIndent()

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertEquals(2, drafts.size)
        assertEquals("周日下午情绪偏低", drafts[0].headline)
        assertEquals(0.78f, drafts[0].confidence, 0.001f)
        assertEquals(listOf("m1", "m2"), drafts[0].evidenceMessageIds)
        assertEquals("近 7 天", drafts[0].relevanceWindow)
        assertEquals("PATTERN_DETECT", drafts[0].triggerType)
    }

    @Test
    fun parse_markdownFences_stripped() {
        val raw = """
            ```json
            [{ "headline": "X", "body": "Y", "evidence_ids": [], "confidence": 0.7 }]
            ```
        """.trimIndent()

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertEquals(1, drafts.size)
        assertEquals("X", drafts[0].headline)
    }

    @Test
    fun parse_entryWithoutHeadline_skipped() {
        val raw = """
            [
              { "body": "no headline here" },
              { "headline": "valid", "body": "ok", "evidence_ids": [], "confidence": 0.5 }
            ]
        """.trimIndent()

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertEquals(1, drafts.size)
        assertEquals("valid", drafts[0].headline)
    }

    @Test
    fun parse_confidenceClampedTo01() {
        val raw = """[{ "headline": "X", "body": "Y", "evidence_ids": [], "confidence": 1.5 }]"""

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertEquals(1, drafts.size)
        assertEquals(1.0f, drafts[0].confidence, 0.001f)
    }

    // ── Layer 2: 归一化修复 ──

    @Test
    fun parse_singleQuotes_normalized() {
        val raw = """[{'headline': '周日下午情绪偏低', 'body': '连续3周', 'evidence_ids': ['m1','m2'], 'confidence': 0.78}]"""

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertEquals(1, drafts.size)
        assertEquals("周日下午情绪偏低", drafts[0].headline)
        assertEquals(0.78f, drafts[0].confidence, 0.001f)
    }

    @Test
    fun parse_trailingComma_normalized() {
        val raw = """[{"headline":"X","body":"Y","evidence_ids":[],"confidence":0.7,}]"""

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertEquals(1, drafts.size)
        assertEquals("X", drafts[0].headline)
    }

    @Test
    fun parse_textAfterJsonArray_trimmed() {
        // 模型常在 JSON 后追加解释文字
        val raw = """[{"headline":"X","body":"Y","evidence_ids":[],"confidence":0.7}]
以上是基于数据分析的结果。"""

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertEquals(1, drafts.size)
        assertEquals("X", drafts[0].headline)
    }

    @Test
    fun parse_bareObjectWrapped() {
        val raw = """这里是分析结果:{"headline":"X","body":"Y","evidence_ids":[],"confidence":0.6}结束"""

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertEquals(1, drafts.size)
        assertEquals("X", drafts[0].headline)
    }

    @Test
    fun parse_chinesePunctuation_normalized() {
        val raw = """[{"headline"："X"，"body"："Y"，"evidence_ids"：[]，"confidence"：0.7}]"""

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertEquals(1, drafts.size)
        assertEquals("X", drafts[0].headline)
    }

    @Test
    fun parse_allLayer2IssuesCombined() {
        // 单引号 + 尾逗号 + 后缀文字 + 中文标点 — 最坏情况
        val raw = """[{'headline'：'测试标题'，'body'：'描述内容'，'evidence_ids'：[]，'confidence'：0.75，}]
分析完成，共发现 1 条模式。
"""

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertEquals(1, drafts.size)
        assertEquals("测试标题", drafts[0].headline)
        assertEquals("描述内容", drafts[0].bodyMarkdown)
        assertEquals(0.75f, drafts[0].confidence, 0.001f)
    }

    // ── Layer 3: 正则兜底 ──

    @Test
    fun parse_freeText_extractsHeadline() {
        val raw = """基于数据分析,我发现以下模式:
headline: 周末睡眠时间明显延长
body: 相比工作日平均多睡 1.5 小时
confidence: 0.72
这个趋势在最近两周比较明显。"""

        val drafts = InsightOutputParser.parsePatternDetectOutput(raw)

        assertTrue(drafts.isNotEmpty())
        assertEquals("周末睡眠时间明显延长", drafts[0].headline)
    }

    // ── 边界 ──

    @Test
    fun parse_emptyInput_returnsEmpty() {
        assertEquals(emptyList<InsightDraft>(), InsightOutputParser.parsePatternDetectOutput(""))
        assertEquals(emptyList<InsightDraft>(), InsightOutputParser.parsePatternDetectOutput("   \n  "))
    }

    @Test
    fun parse_completelyGarbage_returnsEmpty() {
        val drafts = InsightOutputParser.parsePatternDetectOutput("今天天气真好，没什么特别的发现")
        assertTrue(drafts.isEmpty())
    }
}
