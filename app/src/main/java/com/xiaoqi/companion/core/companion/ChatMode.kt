package com.xiaoqi.companion.core.companion

/**
 * 对话模式（aura-handoff §3-T5 / D4）：LISTEN 只倾听，GUIDE 主动给建议。
 * 默认 LISTEN —— 说心事时先接住情绪，只有用户明确索要建议、或同一话题连续追问时才切 GUIDE。
 */
enum class ChatMode(
    val storageKey: String,
    val displayName: String,
) {
    LISTEN("listen", "倾听"),
    GUIDE("guide", "指引");

    companion object {
        val DEFAULT: ChatMode = LISTEN

        fun fromStorageKey(key: String?): ChatMode =
            entries.firstOrNull { it.storageKey.equals(key?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/** 判定器输入的一轮用户发言；时间戳可注入，用于测试追问窗口过期。 */
data class ChatModeTurn(
    val text: String,
    val atMillis: Long = 0L,
)

enum class ChatModeReason {
    /** 「我该怎么办」「你觉得呢」这类明确索要建议 */
    EXPLICIT_REQUEST,

    /** 「别给建议，我只想说说话」 */
    EXPLICIT_DECLINE,

    /** Aura 问过「要我听着，还是帮你想办法」之后用户的回答 */
    MODE_QUESTION_ANSWER,

    /** 同一话题在窗口内已经追问到阈值轮数 */
    SAME_TOPIC_FOLLOW_UP,

    /** 没有触发信号，保持默认 LISTEN */
    DEFAULT,
}

data class ChatModeDecision(
    val mode: ChatMode,
    val reason: ChatModeReason,
) {
    /**
     * 只有显式信号代表用户的真实偏好，才允许写进长期记忆；
     * 隐式追问（SAME_TOPIC_FOLLOW_UP）是推断，按 D1「不记推断」不落库。
     */
    val explicit: Boolean
        get() = reason == ChatModeReason.EXPLICIT_REQUEST ||
            reason == ChatModeReason.EXPLICIT_DECLINE ||
            reason == ChatModeReason.MODE_QUESTION_ANSWER
}

/**
 * 纯函数模式判定器：不持有状态、不依赖时钟，时间与历史都由调用方注入，便于单测。
 * 接线位置见 docs/plan/t5-ui-hook.md。
 */
object ChatModeResolver {

    /** 追问窗口：间隔超过它就算换了件事，旧轮数不再累计。 */
    const val FOLLOW_UP_WINDOW_MS: Long = 30L * 60L * 1000L

    /** 窗口内同一话题已追问过的轮数达到该值 → GUIDE（即用户第 3 次问同一件事）。 */
    const val SAME_TOPIC_FOLLOW_UP_THRESHOLD: Int = 2

    /** Dice 系数门槛：容忍「工作上的事」→「那个工作的事」这类改写，又不把不同话题算成一件。 */
    private const val SAME_TOPIC_SIMILARITY: Double = 0.4

    private val LISTEN_PATTERNS = listOf(
        "别给建议", "不要给建议", "不用给建议", "不用给我建议", "不要给我建议", "不用建议", "别建议",
        "我不想听建议", "不想听建议",
        "别分析", "不用分析", "别分析我", "不用分析我", "不想被分析", "别出主意", "不要出主意", "不用出主意",
        "别劝我", "不用劝我",
        "我只想说说", "我只想说", "就是想说说", "只是想说说", "只想找人说说",
        "听我说", "你听我说", "听着就好", "你听着就行", "听我倾诉",
        "不用安慰我", "不用帮我拿主意", "别帮我拿主意",
    )

    /** 回答「要我听着还是帮你想办法」时选了倾听。 */
    private val LISTEN_ANSWER_PATTERNS = listOf(
        "听着", "听我说", "倾诉", "只想说说", "只想说", "说出来就好", "不用建议", "别建议", "听就行",
    )

    private val GUIDE_PATTERNS = listOf(
        "我该怎么办", "该怎么办", "我该怎么做", "我该怎么", "怎么办", "如何是好",
        "你觉得呢", "你怎么看", "你觉得", "你说呢",
        "给我点建议", "给点建议", "给个建议", "给点意见", "有什么建议", "建议一下", "求建议",
        "帮我拿个主意", "帮我拿主意", "出个主意", "帮我分析", "帮我判断", "帮我想想", "帮我想个办法",
        "想个办法", "想想办法", "支个招", "给个方向",
        "我该不该", "我应该怎么", "该怎么选", "怎么选", "该怎么走", "想听听你的想法",
    )

    /** 「要我帮你想办法吗？」之后的短肯定回答。 */
    private val AFFIRMATIVE_ANSWERS = setOf(
        "好", "好啊", "好的", "好呀", "嗯", "嗯嗯", "行", "可以", "要", "来吧", "帮我吧", "想", "是的", "对",
    )

    private val QUESTION_PATTERNS = listOf(
        "吗", "呢", "?", "？", "怎么", "如何", "为什么", "为啥", "该不该", "要不要", "能不能", "是不是", "可不可以",
    )

    fun decide(
        message: String,
        history: List<ChatModeTurn> = emptyList(),
        nowMillis: Long = 0L,
        awaitingModeAnswer: Boolean = false,
    ): ChatModeDecision {
        val text = message.trim()
        if (text.isEmpty()) return ChatModeDecision(ChatMode.DEFAULT, ChatModeReason.DEFAULT)

        // Aura 刚问过「想被听着还是想让我帮你想办法」时，用户的回答优先解释为对该问题的回答
        if (awaitingModeAnswer && text.containsAny(LISTEN_ANSWER_PATTERNS)) {
            return ChatModeDecision(ChatMode.LISTEN, ChatModeReason.MODE_QUESTION_ANSWER)
        }
        if (text.containsAny(LISTEN_PATTERNS)) {
            return ChatModeDecision(ChatMode.LISTEN, ChatModeReason.EXPLICIT_DECLINE)
        }
        if (text.containsAny(GUIDE_PATTERNS)) {
            return ChatModeDecision(ChatMode.GUIDE, ChatModeReason.EXPLICIT_REQUEST)
        }
        if (awaitingModeAnswer && text.isShortAffirmative()) {
            return ChatModeDecision(ChatMode.GUIDE, ChatModeReason.MODE_QUESTION_ANSWER)
        }
        // 追问也要像在问问题：连发三条同话题的倾诉不该被当成「要建议」，否则说心事时会被打断。
        if (looksLikeQuestion(text) &&
            sameTopicFollowUpCount(text, history, nowMillis) >= SAME_TOPIC_FOLLOW_UP_THRESHOLD
        ) {
            return ChatModeDecision(ChatMode.GUIDE, ChatModeReason.SAME_TOPIC_FOLLOW_UP)
        }
        return ChatModeDecision(ChatMode.DEFAULT, ChatModeReason.DEFAULT)
    }

    /** 从最近一轮往前数，连续同话题且在 [FOLLOW_UP_WINDOW_MS] 内的轮数。 */
    fun sameTopicFollowUpCount(
        message: String,
        history: List<ChatModeTurn>,
        nowMillis: Long = 0L,
    ): Int {
        var reference = message
        var count = 0
        for (turn in history.asReversed()) {
            if (turn.atMillis > 0L && nowMillis > 0L && nowMillis - turn.atMillis > FOLLOW_UP_WINDOW_MS) break
            if (!isSameTopic(reference, turn.text)) break
            count++
            reference = turn.text
        }
        return count
    }

    fun isSameTopic(left: String, right: String): Boolean {
        val a = left.contentChars()
        val b = right.contentChars()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.length >= 3 && b.length >= 3 && (a.contains(b) || b.contains(a))) return true
        val gramsA = a.bigrams()
        val gramsB = b.bigrams()
        if (gramsA.size < 2 || gramsB.size < 2) return false
        val shared = gramsA.count { it in gramsB }
        return 2.0 * shared / (gramsA.size + gramsB.size) >= SAME_TOPIC_SIMILARITY
    }

    fun looksLikeQuestion(text: String): Boolean = text.containsAny(QUESTION_PATTERNS)

    private fun String.isShortAffirmative(): Boolean {
        val normalized = trim().trim('。', '！', '!', '.', '~', '，', ',', ' ')
        return normalized.length <= 6 && normalized in AFFIRMATIVE_ANSWERS
    }

    private fun String.containsAny(patterns: List<String>): Boolean {
        val haystack = lowercase()
        return patterns.any { haystack.contains(it) }
    }

    /** 只留中文、字母和数字，避免标点差异把同一话题判成两件。 */
    private fun String.contentChars(): String =
        lowercase().filter { it.isLetterOrDigit() }

    private fun String.bigrams(): Set<String> =
        if (length < 2) emptySet() else (0 until length - 1).mapTo(mutableSetOf()) { substring(it, it + 2) }
}
