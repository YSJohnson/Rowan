package com.xiaoqi.companion.core.pulse

import com.xiaoqi.companion.data.db.converter.MemoryType
import com.xiaoqi.companion.data.db.entity.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动消息的素材筛选与措辞测试。
 *
 * 两个必须成立的护栏:
 * - 没有真实记忆 → 不发消息(不允许空泛问候)
 * - base64 绝不进文案
 */
class PulseMessageComposerTest {

    private val now = 1_700_000_000_000L
    private val day = PulseConfig.DAY_MILLIS

    private fun memory(
        id: String = "m1",
        content: String = "用户下周有个重要的面试",
        type: MemoryType = MemoryType.EPISODE,
        importance: Float = 0.7f,
        timestamp: Long = now - day,
        sensitivity: String = "normal",
        archived: Boolean = false,
        pinned: Boolean = false,
        expiresAt: Long? = null,
        imageBase64: String? = null,
    ) = MemoryEntity(
        id = id,
        type = type,
        content = content,
        importance = importance,
        timestamp = timestamp,
        updatedAt = timestamp,
        expiresAt = expiresAt,
        sensitivity = sensitivity,
        lastAccessed = timestamp,
        pinned = pinned,
        archived = archived,
        imageBase64 = imageBase64,
    )

    private fun seed(
        reason: PulseTriggerReason = PulseTriggerReason.NEGATIVE_STREAK,
        days: Int = 3,
    ) = PulseMessageSeed(reason = reason, negativeStreakDays = days, latestMood = "sad")

    // ── 素材筛选 ──

    @Test
    fun pick_returnsNull_whenNoMemories() {
        assertNull(PulseMemorySelector.pick(emptyList(), now))
    }

    @Test
    fun pick_returnsMostValuableMemory() {
        val picked = PulseMemorySelector.pick(
            listOf(
                memory(id = "low", importance = 0.3f),
                memory(id = "high", importance = 0.9f),
            ),
            now,
        )

        assertEquals("high", picked?.id)
    }

    @Test
    fun pick_prefersPinnedOverImportance() {
        val picked = PulseMemorySelector.pick(
            listOf(
                memory(id = "important", importance = 0.95f),
                memory(id = "pinned", importance = 0.2f, pinned = true),
            ),
            now,
        )

        assertEquals("pinned", picked?.id)
    }

    @Test
    fun pick_prefersEpisodeOverFact() {
        val picked = PulseMemorySelector.pick(
            listOf(
                memory(id = "fact", type = MemoryType.FACT, importance = 0.8f),
                memory(id = "episode", type = MemoryType.EPISODE, importance = 0.4f),
            ),
            now,
        )

        assertEquals("episode", picked?.id)
    }

    @Test
    fun pick_excludesSensitiveMemories() {
        val picked = PulseMemorySelector.pick(
            listOf(memory(id = "secret", sensitivity = "sensitive", importance = 0.99f)),
            now,
        )

        assertNull(picked)
    }

    @Test
    fun pick_excludesCrisisMemories() {
        val picked = PulseMemorySelector.pick(
            listOf(memory(id = "crisis", sensitivity = "crisis", importance = 0.99f)),
            now,
        )

        assertNull(picked)
    }

    @Test
    fun pick_excludesArchivedAndExpired() {
        val picked = PulseMemorySelector.pick(
            listOf(
                memory(id = "archived", archived = true, importance = 0.99f),
                memory(id = "expired", expiresAt = now - day, importance = 0.98f),
            ),
            now,
        )

        assertNull(picked)
    }

    @Test
    fun pick_excludesImageMemories() {
        val picked = PulseMemorySelector.pick(
            listOf(memory(id = "image", imageBase64 = "AAAA", content = "[图片] 一张书桌照片")),
            now,
        )

        assertNull(picked)
    }

    @Test
    fun pick_excludesStaleAndTooShortContent() {
        val picked = PulseMemorySelector.pick(
            listOf(
                memory(id = "old", timestamp = now - 90 * day, importance = 0.99f),
                memory(id = "short", content = "嗯", importance = 0.9f),
            ),
            now,
        )

        assertNull(picked)
    }

    // ── 文案 ──

    @Test
    fun compose_quotesRealMemoryContent() {
        val candidate = PulseMemorySelector.pick(listOf(memory(content = "用户下周有个重要的面试")), now)

        val message = PulseMessageComposer.compose(seed(), candidate)

        assertNotNull(message)
        assertTrue(message!!.contains("用户下周有个重要的面试"))
        assertTrue(message.contains("这几天你好像一直不太好"))
    }

    @Test
    fun compose_returnsNull_withoutMemory() {
        assertNull(PulseMessageComposer.compose(seed(), memory = null))
    }

    @Test
    fun compose_returnsNull_whenMemoryContentSanitizesToEmpty() {
        val candidate = PulseMemoryCandidate(
            id = "m1",
            content = "data:image/jpeg;base64,${"A".repeat(400)}",
            type = MemoryType.FACT,
            importance = 0.9f,
            timestamp = now,
        )

        assertNull(PulseMessageComposer.compose(seed(), candidate))
    }

    @Test
    fun compose_extremeNegativeUsesItsOwnOpening() {
        val candidate = PulseMemorySelector.pick(listOf(memory()), now)

        val message = PulseMessageComposer.compose(
            seed(PulseTriggerReason.EXTREME_NEGATIVE, days = 1),
            candidate,
        )

        assertTrue(message!!.startsWith("刚感觉你状态不太好。"))
    }

    @Test
    fun compose_streakWordingScalesWithDays() {
        val candidate = PulseMemorySelector.pick(listOf(memory()), now)

        assertTrue(PulseMessageComposer.compose(seed(days = 2), candidate)!!.contains("这两天"))
        assertTrue(PulseMessageComposer.compose(seed(days = 4), candidate)!!.contains("这几天"))
        assertTrue(PulseMessageComposer.compose(seed(days = 6), candidate)!!.contains("这段时间"))
    }

    // ── 清洗 ──

    @Test
    fun sanitize_stripsEmbeddedBase64() {
        val dirty = "用户提到面试 data:image/png;base64,${"iVBORw0KGgo".repeat(30)} 有点紧张"

        val clean = PulseMessageComposer.sanitize(dirty, maxChars = 200)

        assertTrue(!clean.contains("base64"))
        assertTrue(clean.contains("用户提到面试"))
        assertTrue(clean.contains("有点紧张"))
    }

    @Test
    fun sanitize_keepsPlainLongAlphanumericRun() {
        // 长 hash / 订单号是正常内容,不能被 base64 清洗规则吃掉。
        val plain = "订单号 " + "b".repeat(200)

        val clean = PulseMessageComposer.sanitize(plain, maxChars = 400)

        assertTrue(clean.contains("b".repeat(200)))
    }

    @Test
    fun sanitize_stripsBase64ShapedRun() {
        val dirty = "截图 " + "A".repeat(60) + "+" + "B".repeat(60)

        val clean = PulseMessageComposer.sanitize(dirty, maxChars = 400)

        assertTrue(clean.contains("截图"))
        assertTrue(!clean.contains("B".repeat(60)))
    }

    @Test
    fun sanitize_collapsesWhitespaceAndTruncates() {
        val long = "a".repeat(10) + "\n\n   " + "b".repeat(200)

        val clean = PulseMessageComposer.sanitize(long, maxChars = 40)

        assertTrue(clean.length <= 41)
        assertTrue(clean.endsWith("…"))
        assertTrue(!clean.contains("\n"))
    }
}
