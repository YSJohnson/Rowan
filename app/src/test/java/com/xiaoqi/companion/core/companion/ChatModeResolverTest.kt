package com.xiaoqi.companion.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModeResolverTest {

    private val now = 1_700_000_000_000L

    // --- 显式触发 ---

    @Test
    fun decide_whatShouldIDo_guides() {
        val decision = ChatModeResolver.decide("我该怎么办")
        assertEquals(ChatMode.GUIDE, decision.mode)
        assertEquals(ChatModeReason.EXPLICIT_REQUEST, decision.reason)
    }

    @Test
    fun decide_variousExplicitPhrasings_guide() {
        val phrasings = listOf(
            "工作上这件事我该怎么办啊",
            "我不知道该怎么办了",
            "你觉得呢？",
            "这事你怎么看",
            "给我点建议吧",
            "能不能给点建议",
            "你帮我拿个主意",
            "帮我想想该怎么选",
            "你说呢",
            "我该不该跟他说清楚",
        )
        phrasings.forEach { text ->
            val decision = ChatModeResolver.decide(text)
            assertEquals("应切 GUIDE: $text", ChatMode.GUIDE, decision.mode)
            assertEquals(ChatModeReason.EXPLICIT_REQUEST, decision.reason)
        }
    }

    @Test
    fun decide_explicitDecline_listens() {
        val phrasings = listOf(
            "别给建议了，我只想说说",
            "不用给我建议，你听我说就行",
            "我现在不想听建议",
            "你听着就好",
            "我不想被分析，说出来就好",
        )
        phrasings.forEach { text ->
            val decision = ChatModeResolver.decide(text)
            assertEquals("应保持 LISTEN: $text", ChatMode.LISTEN, decision.mode)
            assertEquals(ChatModeReason.EXPLICIT_DECLINE, decision.reason)
        }
    }

    @Test
    fun decide_declineWinsOverRequestInSameMessage() {
        val decision = ChatModeResolver.decide("不用给我建议，我该怎么办我自己知道")

        assertEquals(ChatMode.LISTEN, decision.mode)
        assertEquals(ChatModeReason.EXPLICIT_DECLINE, decision.reason)
    }

    // --- 默认与空输入 ---

    @Test
    fun decide_plainVenting_defaultsToListening() {
        val phrasings = listOf(
            "今天上班被老板骂了，好累",
            "我最近工作压力好大",
            "就是有点想你了",
        )
        phrasings.forEach { text ->
            val decision = ChatModeResolver.decide(text)
            assertEquals("应保持 LISTEN: $text", ChatMode.LISTEN, decision.mode)
            assertEquals(ChatModeReason.DEFAULT, decision.reason)
        }
    }

    @Test
    fun decide_blankMessage_defaultsToListening() {
        val decision = ChatModeResolver.decide("   ")

        assertEquals(ChatMode.LISTEN, decision.mode)
        assertEquals(ChatModeReason.DEFAULT, decision.reason)
    }

    // --- 显式询问后的回答 ---

    @Test
    fun decide_modeQuestionAnsweredWithYes_guides() {
        listOf("好", "好啊", "嗯嗯", "行", "帮我吧").forEach { text ->
            val decision = ChatModeResolver.decide(text, awaitingModeAnswer = true)
            assertEquals("应切 GUIDE: $text", ChatMode.GUIDE, decision.mode)
            assertEquals(ChatModeReason.MODE_QUESTION_ANSWER, decision.reason)
        }
    }

    @Test
    fun decide_modeQuestionAnsweredWithListen_listens() {
        val decision = ChatModeResolver.decide("你听着就好", awaitingModeAnswer = true)

        assertEquals(ChatMode.LISTEN, decision.mode)
        assertEquals(ChatModeReason.MODE_QUESTION_ANSWER, decision.reason)
    }

    @Test
    fun decide_awaitingAnswerButUnrelatedMessage_keepsDefault() {
        val decision = ChatModeResolver.decide("我今天还去了趟超市", awaitingModeAnswer = true)

        assertEquals(ChatMode.LISTEN, decision.mode)
        assertEquals(ChatModeReason.DEFAULT, decision.reason)
    }

    @Test
    fun decide_shortAffirmativeWithoutPendingQuestion_isNotATrigger() {
        val decision = ChatModeResolver.decide("好")

        assertEquals(ChatMode.LISTEN, decision.mode)
        assertEquals(ChatModeReason.DEFAULT, decision.reason)
    }

    // --- 隐式：同话题连续追问 ---

    @Test
    fun decide_sameTopicFollowUpTwice_guides() {
        val history = listOf(
            ChatModeTurn("他为什么总是不回我消息", now - 20 * 60 * 1000L),
            ChatModeTurn("他为什么总是不回我消息呢，我到底哪里不好", now - 5 * 60 * 1000L),
        )
        val decision = ChatModeResolver.decide(
            message = "他为什么总是不回我消息，是不是我哪里做错了",
            history = history,
            nowMillis = now,
        )

        assertEquals(ChatMode.GUIDE, decision.mode)
        assertEquals(ChatModeReason.SAME_TOPIC_FOLLOW_UP, decision.reason)
    }

    @Test
    fun decide_singleSameTopicFollowUp_staysListening() {
        val history = listOf(
            ChatModeTurn("他为什么总是不回我消息呢，我到底哪里不好", now - 5 * 60 * 1000L),
        )
        val decision = ChatModeResolver.decide(
            message = "他为什么总是不回我消息，是不是我哪里做错了",
            history = history,
            nowMillis = now,
        )

        assertEquals(ChatMode.LISTEN, decision.mode)
        assertEquals(ChatModeReason.DEFAULT, decision.reason)
    }

    @Test
    fun decide_sameTopicVentingWithoutQuestion_staysListening() {
        val history = listOf(
            ChatModeTurn("工作上的事真的好烦", now - 10 * 60 * 1000L),
            ChatModeTurn("工作上的事还是那样，我一点办法都没有", now - 5 * 60 * 1000L),
        )
        val decision = ChatModeResolver.decide(
            message = "工作上的事压得我喘不过气",
            history = history,
            nowMillis = now,
        )

        assertEquals(ChatMode.LISTEN, decision.mode)
        assertEquals(ChatModeReason.DEFAULT, decision.reason)
    }

    @Test
    fun decide_differentTopicQuestion_staysListening() {
        val history = listOf(
            ChatModeTurn("他为什么总是不回我消息", now - 20 * 60 * 1000L),
            ChatModeTurn("他为什么总是不回我消息呢，我到底哪里不好", now - 5 * 60 * 1000L),
        )
        val decision = ChatModeResolver.decide(
            message = "今天天气真好，我在海边走了很久",
            history = history,
            nowMillis = now,
        )

        assertEquals(ChatMode.LISTEN, decision.mode)
    }

    @Test
    fun decide_followUpChainOutsideWindow_staysListening() {
        val history = listOf(
            ChatModeTurn("他为什么总是不回我消息", now - 3 * 60 * 60 * 1000L),
            ChatModeTurn("他为什么总是不回我消息呢，我到底哪里不好", now - 2 * 60 * 60 * 1000L),
        )
        val decision = ChatModeResolver.decide(
            message = "他为什么总是不回我消息，是不是我哪里做错了",
            history = history,
            nowMillis = now,
        )

        assertEquals(ChatMode.LISTEN, decision.mode)
        assertEquals(ChatModeReason.DEFAULT, decision.reason)
    }

    @Test
    fun sameTopicFollowUpCount_countsChainFromNewestBackwards() {
        val history = listOf(
            ChatModeTurn("他为什么总是不回我消息", now - 20 * 60 * 1000L),
            ChatModeTurn("他为什么总是不回我消息呢，我到底哪里不好", now - 5 * 60 * 1000L),
        )

        assertEquals(
            2,
            ChatModeResolver.sameTopicFollowUpCount(
                message = "他为什么总是不回我消息，是不是我哪里做错了",
                history = history,
                nowMillis = now,
            ),
        )
        assertEquals(0, ChatModeResolver.sameTopicFollowUpCount("今天天气不错", history, now))
    }

    @Test
    fun isSameTopic_paraphraseTrue_unrelatedFalse() {
        assertTrue(
            ChatModeResolver.isSameTopic(
                "他为什么总是不回我消息，是不是我哪里做错了",
                "他为什么总是不回我消息呢，我到底哪里不好",
            ),
        )
        assertFalse(ChatModeResolver.isSameTopic("工作上的事", "海边散步很舒服"))
    }

    // --- 其他契约 ---

    @Test
    fun decide_isPure_sameInputsSameDecision() {
        val history = listOf(ChatModeTurn("工作上的事真的好烦", now - 60 * 1000L))

        val first = ChatModeResolver.decide("工作上我该怎么办", history, now)
        val second = ChatModeResolver.decide("工作上我该怎么办", history, now)

        assertEquals(first, second)
    }

    @Test
    fun decision_explicitFlagOnlyForExplicitReasons() {
        assertTrue(ChatModeResolver.decide("我该怎么办").explicit)
        assertTrue(ChatModeResolver.decide("别给建议").explicit)
        assertTrue(ChatModeResolver.decide("好", awaitingModeAnswer = true).explicit)
        assertFalse(
            ChatModeResolver.decide(
                message = "他为什么总是不回我消息，是不是我哪里做错了",
                history = listOf(
                    ChatModeTurn("他为什么总是不回我消息", now - 20 * 60 * 1000L),
                    ChatModeTurn("他为什么总是不回我消息呢，我到底哪里不好", now - 5 * 60 * 1000L),
                ),
                nowMillis = now,
            ).explicit,
        )
        assertFalse(ChatModeResolver.decide("今天有点累").explicit)
    }

    @Test
    fun chatMode_defaultIsListen_andStorageKeyRoundTrips() {
        assertEquals(ChatMode.LISTEN, ChatMode.DEFAULT)
        assertEquals(ChatMode.GUIDE, ChatMode.fromStorageKey("guide"))
        assertEquals(ChatMode.GUIDE, ChatMode.fromStorageKey(" GUIDE "))
        assertEquals(ChatMode.LISTEN, ChatMode.fromStorageKey("listen"))
        assertEquals(ChatMode.LISTEN, ChatMode.fromStorageKey(null))
        assertEquals(ChatMode.LISTEN, ChatMode.fromStorageKey("unknown"))
        assertEquals("倾听", ChatMode.LISTEN.displayName)
        assertEquals("指引", ChatMode.GUIDE.displayName)
    }
}
