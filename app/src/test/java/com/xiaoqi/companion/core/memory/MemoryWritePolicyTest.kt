package com.xiaoqi.companion.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWritePolicyTest {

    @Test
    fun `plain user statement is stored as normal`() {
        val decision = MemoryWritePolicy.evaluate("我最近在学做菜")

        assertTrue(decision.shouldStore)
        assertEquals(MemorySensitivity.NORMAL, decision.sensitivity)
        assertEquals("我最近在学做菜", decision.content)
        assertNull(decision.rejection)
    }

    @Test
    fun `empty content is rejected`() {
        val decision = MemoryWritePolicy.evaluate("   ")

        assertFalse(decision.shouldStore)
        assertEquals(MemoryWriteDecision.Rejection.EMPTY_CONTENT, decision.rejection)
    }

    @Test
    fun `model synthesis is rejected because it is not what the user said`() {
        val decision = MemoryWritePolicy.evaluate(
            content = "用户最近三周情绪波动与工作压力高度相关",
            requestedSource = MemorySourceKinds.REFLECTION_SYNTHESIS,
        )

        assertFalse(decision.shouldStore)
        assertEquals(MemoryWriteDecision.Rejection.INFERRED_NOT_STATED, decision.rejection)
    }

    @Test
    fun `personality inference source is rejected`() {
        val decision = MemoryWritePolicy.evaluate(
            content = "用户是一个回避型依恋的人",
            requestedSource = MemorySourceKinds.AI_INFERENCE,
        )

        assertFalse(decision.shouldStore)
        assertEquals(MemoryWriteDecision.Rejection.INFERRED_NOT_STATED, decision.rejection)
    }

    @Test
    fun `external tool result is rejected`() {
        val decision = MemoryWritePolicy.evaluate(
            content = "附近有三家咖啡店",
            requestedSource = MemorySourceKinds.EXTERNAL_TOOL_RESULT,
        )

        assertFalse(decision.shouldStore)
        assertEquals(MemoryWriteDecision.Rejection.INFERRED_NOT_STATED, decision.rejection)
    }

    @Test
    fun `inference phrase is rejected even from a trusted source`() {
        val decision = MemoryWritePolicy.evaluate(
            content = "用户喜欢整个日料品类",
            requestedSource = MemorySourceKinds.TOOL_SAVE_MEMORY,
        )

        assertFalse(decision.shouldStore)
        assertEquals(MemoryWriteDecision.Rejection.INFERENCE_PHRASE, decision.rejection)
    }

    @Test
    fun `personality conclusion phrase is rejected`() {
        val decision = MemoryWritePolicy.evaluate("综上可以看出用户是个完美主义者")

        assertFalse(decision.shouldStore)
        assertEquals(MemoryWriteDecision.Rejection.INFERENCE_PHRASE, decision.rejection)
    }

    @Test
    fun `forget request is not stored`() {
        val decision = MemoryWritePolicy.evaluate("忘掉这件事吧，我不想再提了")

        assertFalse(decision.shouldStore)
        assertEquals(MemoryWriteDecision.Rejection.FORGET_REQUEST, decision.rejection)
    }

    @Test
    fun `symptom mentioned by the user stays attributed to the user`() {
        assertEquals(
            "用户说自己有抑郁症",
            MemoryWritePolicy.sanitizeSymptomAttribution("用户有抑郁症"),
        )
        assertEquals(
            "用户说自己得了焦虑症",
            MemoryWritePolicy.sanitizeSymptomAttribution("用户得了焦虑症"),
        )
    }

    @Test
    fun `already attributed statement is left untouched`() {
        val original = "用户说自己最近老失眠"

        assertEquals(original, MemoryWritePolicy.sanitizeSymptomAttribution(original))
    }

    @Test
    fun `non diagnostic text is left untouched`() {
        val original = "用户今天去看了场电影"

        assertEquals(original, MemoryWritePolicy.sanitizeSymptomAttribution(original))
    }

    @Test
    fun `health content is promoted to sensitive`() {
        val decision = MemoryWritePolicy.evaluate("我下周要去医院复诊")

        assertTrue(decision.shouldStore)
        assertEquals(MemorySensitivity.SENSITIVE, decision.sensitivity)
    }

    @Test
    fun `finance content is promoted to sensitive`() {
        assertEquals(
            MemorySensitivity.SENSITIVE,
            MemoryWritePolicy.evaluate("这个月信用卡又逾期了").sensitivity,
        )
    }

    @Test
    fun `relationship content is promoted to sensitive`() {
        assertEquals(
            MemorySensitivity.SENSITIVE,
            MemoryWritePolicy.evaluate("我发现他出轨了").sensitivity,
        )
    }

    @Test
    fun `relative mention is promoted to private`() {
        assertEquals(
            MemorySensitivity.PRIVATE,
            MemoryWritePolicy.evaluate("我妈今天又催我结婚").sensitivity,
        )
    }

    @Test
    fun `explicit secrecy request wins over everything`() {
        val decision = MemoryWritePolicy.evaluate("我小时候养过一只猫，这是秘密，别告诉别人")

        assertTrue(decision.shouldStore)
        assertEquals(MemorySensitivity.SENSITIVE, decision.sensitivity)
    }

    @Test
    fun `vision derived memory is at least private`() {
        val decision = MemoryWritePolicy.evaluate(
            content = "[图片] 一杯手冲咖啡",
            requestedSource = MemorySourceKinds.REFLECTION_VISION,
        )

        assertTrue(decision.shouldStore)
        assertEquals(MemorySensitivity.PRIVATE, decision.sensitivity)
    }

    @Test
    fun `stronger requested sensitivity is never downgraded`() {
        val decision = MemoryWritePolicy.evaluate(
            content = "我最近在学做菜",
            requestedSensitivity = MemorySensitivity.SENSITIVE,
        )

        assertEquals(MemorySensitivity.SENSITIVE, decision.sensitivity)
    }

    @Test
    fun `sensitive content is excluded from summaries and collapsed by default`() {
        assertFalse(MemoryWritePolicy.isEligibleForSummary(MemorySensitivity.SENSITIVE))
        assertFalse(MemoryWritePolicy.isEligibleForSummary(MemorySensitivity.PRIVATE))
        assertTrue(MemoryWritePolicy.isEligibleForSummary(MemorySensitivity.NORMAL))

        assertTrue(MemoryWritePolicy.isCollapsedByDefault(MemorySensitivity.SENSITIVE))
        assertTrue(MemoryWritePolicy.isCollapsedByDefault(MemorySensitivity.PRIVATE))
        assertFalse(MemoryWritePolicy.isCollapsedByDefault(MemorySensitivity.NORMAL))
    }

    /**
     * `SaveMemoryResult.stored` 的契约靠"被拒时不留可用的 memory"成立：
     * 仓库层会返回 `id` 为空的临时对象，调用方不得拿它去查/删。行为验证见
     * `MemoryRepositoryTest.saveMemory_rejectedWriteDoesNotPersist`。
     */
    @Test
    fun `rejected decision carries the sanitized content for diagnostics`() {
        val rejected = MemoryWritePolicy.evaluate(
            content = "用户有抑郁症",
            requestedSource = MemorySourceKinds.AI_INFERENCE,
        )

        assertFalse(rejected.shouldStore)
        // 即便被拒也保留净化后的文本，方便日志里看清"拒的是什么"
        assertEquals("用户说自己有抑郁症", rejected.content)
    }
}
