package com.xiaoqi.companion.core.companion

import com.xiaoqi.companion.data.db.converter.MemoryType
import com.xiaoqi.companion.data.repository.MemoryRepository
import com.xiaoqi.companion.data.repository.SaveMemoryResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModePreferenceStoreTest {

    private val explicitGuide = ChatModeDecision(ChatMode.GUIDE, ChatModeReason.EXPLICIT_REQUEST)
    private val explicitListen = ChatModeDecision(ChatMode.LISTEN, ChatModeReason.EXPLICIT_DECLINE)

    // --- 情境分类 ---

    @Test
    fun classify_knownSituations() {
        assertEquals(ChatModeSituation.WORK, ChatModePreferenceExtractor.classify("工作上那件事我该怎么办"))
        assertEquals(ChatModeSituation.STUDY, ChatModePreferenceExtractor.classify("考研压力好大，你觉得呢"))
        assertEquals(ChatModeSituation.RELATIONSHIP, ChatModePreferenceExtractor.classify("我跟男朋友分手了"))
        assertEquals(ChatModeSituation.FAMILY, ChatModePreferenceExtractor.classify("我妈妈今天又给我打电话了"))
        assertEquals(ChatModeSituation.HEALTH, ChatModePreferenceExtractor.classify("医院检查结果出来了"))
        assertEquals(ChatModeSituation.MONEY, ChatModePreferenceExtractor.classify("这个月房贷还不上了"))
        assertEquals(ChatModeSituation.EMOTION, ChatModePreferenceExtractor.classify("今天心情特别低落"))
        assertEquals(ChatModeSituation.OTHER, ChatModePreferenceExtractor.classify("楼下咖啡店换了新豆子"))
    }

    @Test
    fun classify_tieBreaksByDeclarationOrder() {
        // 「工作」和「压力」各命中一次，取声明顺序靠前的工作情境
        assertEquals(ChatModeSituation.WORK, ChatModePreferenceExtractor.classify("工作压力好大"))
    }

    @Test
    fun situationSensitivity_followsMemoryLayering() {
        assertEquals("normal", ChatModeSituation.WORK.sensitivity)
        assertEquals("normal", ChatModeSituation.STUDY.sensitivity)
        assertEquals("private", ChatModeSituation.FAMILY.sensitivity)
        assertEquals("private", ChatModeSituation.EMOTION.sensitivity)
        assertEquals("sensitive", ChatModeSituation.HEALTH.sensitivity)
        assertEquals("sensitive", ChatModeSituation.MONEY.sensitivity)
        assertEquals("sensitive", ChatModeSituation.RELATIONSHIP.sensitivity)
    }

    // --- 抽取：只记显式偏好 ---

    @Test
    fun extract_explicitRequest_recordsSituationAndMode() {
        val preference = ChatModePreferenceExtractor.extract(
            turn = ChatModeTurn("工作上我该怎么办", atMillis = 1_000L),
            decision = explicitGuide,
        )

        assertEquals(ChatModeSituation.WORK, preference?.situation)
        assertEquals(ChatMode.GUIDE, preference?.mode)
        assertEquals(1_000L, preference?.observedAtMillis)
        assertTrue(ChatModePreferenceExtractor.toMemoryContent(preference!!).contains("直接给建议"))
    }

    @Test
    fun extract_explicitDecline_recordsListenPreference() {
        val preference = ChatModePreferenceExtractor.extract(
            turn = ChatModeTurn("感情的事别给建议，我只想说说", atMillis = 2_000L),
            decision = explicitListen,
        )

        assertEquals(ChatModeSituation.RELATIONSHIP, preference?.situation)
        assertEquals(ChatMode.LISTEN, preference?.mode)
        assertTrue(ChatModePreferenceExtractor.toMemoryContent(preference!!).contains("只想被倾听"))
    }

    @Test
    fun extract_implicitFollowUp_isNotRecorded() {
        val decision = ChatModeDecision(ChatMode.GUIDE, ChatModeReason.SAME_TOPIC_FOLLOW_UP)

        assertNull(
            ChatModePreferenceExtractor.extract(ChatModeTurn("工作上那件事到底为什么", atMillis = 3_000L), decision),
        )
    }

    @Test
    fun extract_defaultDecision_isNotRecorded() {
        val decision = ChatModeDecision(ChatMode.LISTEN, ChatModeReason.DEFAULT)

        assertNull(ChatModePreferenceExtractor.extract(ChatModeTurn("今天有点累", atMillis = 4_000L), decision))
    }

    @Test
    fun toMemoryContent_differsPerMode() {
        val guide = ChatModePreference(ChatModeSituation.WORK, ChatMode.GUIDE, 0L)
        val listen = ChatModePreference(ChatModeSituation.WORK, ChatMode.LISTEN, 0L)

        assertNotEquals(
            ChatModePreferenceExtractor.toMemoryContent(guide),
            ChatModePreferenceExtractor.toMemoryContent(listen),
        )
    }

    // --- Store：显式才写、冷却、去重 ---

    @Test
    fun record_explicitPreference_writesToSinkWithSituationSensitivity() = runTest {
        val sink = mockk<ChatModePreferenceSink>(relaxed = true)
        val store = ChatModePreferenceStore(sink, nowMillis = { 100L })

        val preference = store.record(
            turn = ChatModeTurn("工作上我该怎么办", atMillis = 100L),
            decision = explicitGuide,
            sourceMessageIds = listOf("msg-1"),
        )

        assertEquals(ChatModeSituation.WORK, preference?.situation)
        coVerify(exactly = 1) {
            sink.remember(
                match { it.contains("工作/职场") && it.contains("直接给建议") },
                "normal",
                listOf("msg-1"),
            )
        }
    }

    @Test
    fun record_sensitiveSituation_usesSensitiveLayering() = runTest {
        val sink = mockk<ChatModePreferenceSink>(relaxed = true)
        val store = ChatModePreferenceStore(sink, nowMillis = { 100L })

        store.record(
            turn = ChatModeTurn("医院的事我想让你帮我拿个主意", atMillis = 100L),
            decision = explicitGuide,
        )

        coVerify(exactly = 1) { sink.remember(any(), "sensitive", any()) }
    }

    @Test
    fun record_nonExplicitDecision_doesNotWrite() = runTest {
        val sink = mockk<ChatModePreferenceSink>(relaxed = true)
        val store = ChatModePreferenceStore(sink, nowMillis = { 100L })

        val preference = store.record(
            turn = ChatModeTurn("工作上那件事到底为什么", atMillis = 100L),
            decision = ChatModeDecision(ChatMode.GUIDE, ChatModeReason.SAME_TOPIC_FOLLOW_UP),
        )

        assertNull(preference)
        coVerify(exactly = 0) { sink.remember(any(), any(), any()) }
    }

    @Test
    fun record_samePreferenceWithinCooldown_writesOnlyOnce() = runTest {
        val sink = mockk<ChatModePreferenceSink>(relaxed = true)
        var clock = 0L
        val store = ChatModePreferenceStore(sink, nowMillis = { clock })
        val turn = ChatModeTurn("工作上我该怎么办", atMillis = 0L)

        assertEquals(ChatModeSituation.WORK, store.record(turn, explicitGuide)?.situation)
        clock += 24 * 60 * 60 * 1000L
        assertNull(store.record(turn, explicitGuide))
        coVerify(exactly = 1) { sink.remember(any(), any(), any()) }
    }

    @Test
    fun record_afterCooldown_writesAgain() = runTest {
        val sink = mockk<ChatModePreferenceSink>(relaxed = true)
        var clock = 0L
        val store = ChatModePreferenceStore(sink, nowMillis = { clock })
        val turn = ChatModeTurn("工作上我该怎么办", atMillis = 0L)

        store.record(turn, explicitGuide)
        clock += ChatModePreferenceStore.DEFAULT_COOLDOWN_MS + 1L
        assertEquals(ChatModeSituation.WORK, store.record(turn, explicitGuide)?.situation)
        coVerify(exactly = 2) { sink.remember(any(), any(), any()) }
    }

    @Test
    fun record_differentSituations_areTrackedIndependently() = runTest {
        val sink = mockk<ChatModePreferenceSink>(relaxed = true)
        val store = ChatModePreferenceStore(sink, nowMillis = { 0L })

        store.record(ChatModeTurn("工作上我该怎么办", 0L), explicitGuide)
        store.record(ChatModeTurn("感情的事别给建议，我只想说说", 0L), explicitListen)

        coVerify(exactly = 1) { sink.remember(match { it.contains("工作/职场") }, any(), any()) }
        coVerify(exactly = 1) { sink.remember(match { it.contains("感情/亲密关系") }, any(), any()) }
    }

    // --- MemoryRepository 写路径 ---

    @Test
    fun memoryRepositorySink_savesFactMemoryWithChatModeSource() = runTest {
        val memoryRepository = mockk<MemoryRepository>(relaxed = true)
        coEvery { memoryRepository.saveMemory(any()) } returns mockk<SaveMemoryResult>(relaxed = true)

        MemoryRepositoryChatModePreferenceSink(memoryRepository)
            .remember("在「身体/健康」话题上，用户只想被倾听，不要主动给建议", "sensitive", listOf("msg-9"))

        coVerify(exactly = 1) {
            memoryRepository.saveMemory(
                match {
                    it.content.contains("身体/健康") &&
                        it.type == MemoryType.FACT &&
                        it.source == SOURCE_CHAT_MODE_PREFERENCE &&
                        it.sensitivity == "sensitive" &&
                        it.sourceMessageIds == listOf("msg-9")
                },
            )
        }
    }
}
