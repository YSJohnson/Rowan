package com.xiaoqi.companion.core.pulse

import com.xiaoqi.companion.data.db.converter.MemoryType
import com.xiaoqi.companion.data.db.entity.MemoryEntity

/**
 * 主动消息的记忆素材。
 *
 * **结构上不含 `imageBase64` / `imageMediaType`** —— 和
 * `core/presence/runtime/DreamDataCollector.ImageMemorySummary` 同一个护栏思路:
 * 图片原图 base64 有几十 KB,进任何 prompt / 文案都会烧 token 且毫无意义。
 * 这里不给字段,而不是"记得别用",让误用编译不过。
 */
data class PulseMemoryCandidate(
    val id: String,
    val content: String,
    val type: MemoryType,
    val importance: Float,
    val timestamp: Long,
)

/** 记忆筛选参数。 */
data class PulseMemoryConfig(
    /** 只认最近这段时间内被提到的记忆 —— "上次你说那件事" 得真的是"上次"。 */
    val lookbackDays: Int = 30,
    /** 太短的内容(嗯/好的)引用出来没有意义。 */
    val minContentChars: Int = 6,
    /** 引用进文案的最大长度,超出截断。 */
    val maxQuoteChars: Int = 60,
) {
    companion object {
        /** 允许被主动提起的 sensitivity 值;其余(敏感/危机/未知)一律不提。 */
        val ELIGIBLE_SENSITIVITY: Set<String> = setOf("", "normal")
    }
}

/**
 * 从候选记忆里挑一条最值得引用的。纯函数 —— 不碰 DB,调用方传 MemoryDao 的只读结果。
 */
object PulseMemorySelector {

    fun pick(
        memories: List<MemoryEntity>,
        nowMillis: Long,
        config: PulseMemoryConfig = PulseMemoryConfig(),
    ): PulseMemoryCandidate? {
        val windowStart = nowMillis - config.lookbackDays * PulseConfig.DAY_MILLIS
        return memories
            .asSequence()
            .filter { !it.archived }
            // 敏感记忆永不被 AI 主动提起(T7 分层 + T8 危机内容策略),所以只认 normal。
            .filter { it.sensitivity.lowercase() in PulseMemoryConfig.ELIGIBLE_SENSITIVITY }
            .filter { it.expiresAt?.let { expiry -> expiry > nowMillis } ?: true }
            // 有图记忆的 content 是图片摘要而非用户原话,引用成"你说过"会失真;同时这也是
            // base64 不可能进入文案的第二道结构护栏。
            .filter { it.imageBase64 == null }
            .filter { it.timestamp in windowStart..nowMillis }
            .filter { it.content.trim().length >= config.minContentChars }
            .sortedWith(
                compareByDescending<MemoryEntity> { it.pinned }
                    .thenByDescending { TYPE_RANK[it.type] ?: 0 }
                    .thenByDescending { it.importance }
                    .thenByDescending { it.timestamp },
            )
            .firstOrNull()
            ?.let {
                PulseMemoryCandidate(
                    id = it.id,
                    content = it.content,
                    type = it.type,
                    importance = it.importance,
                    timestamp = it.timestamp,
                )
            }
    }

    /** EPISODE(发生过的具体事情)最适合被关心式引用,其次是事实,再次是习惯类。 */
    private val TYPE_RANK = mapOf(
        MemoryType.EPISODE to 2,
        MemoryType.FACT to 1,
        MemoryType.PROCEDURAL to 0,
    )
}

/**
 * 把 [PulseMessageSeed] + 真实记忆拼成一条主动消息。
 *
 * 刻意做成确定性文案而不是再调一次 LLM:主动关怀是"准时 + 具体",引用的就是用户原话,
 * 多一次模型调用只会引入不确定性、延迟和成本。要润色再走 LLM 属于接线阶段的事,
 * 见 docs/plan/t6-ui-hook.md。
 */
object PulseMessageComposer {

    /** 没有真实记忆时返回 null —— 宁可不打扰,也不发"最近还好吗"这种空泛问候。 */
    fun compose(
        seed: PulseMessageSeed,
        memory: PulseMemoryCandidate?,
        config: PulseMemoryConfig = PulseMemoryConfig(),
    ): String? {
        val candidate = memory ?: return null
        val quote = sanitize(candidate.content, config.maxQuoteChars)
        if (quote.isBlank()) return null

        val opening = when (seed.reason) {
            PulseTriggerReason.EXTREME_NEGATIVE -> "刚感觉你状态不太好。"
            PulseTriggerReason.NEGATIVE_STREAK -> when {
                seed.negativeStreakDays <= 0 -> "最近你好像不太轻松。"
                seed.negativeStreakDays == 1 -> "今天你好像不太轻松。"
                seed.negativeStreakDays == 2 -> "这两天你好像一直不太好。"
                seed.negativeStreakDays <= 4 -> "这几天你好像一直不太好。"
                else -> "这段时间你好像一直不太好。"
            }
        }
        return "$opening 上次你说「$quote」——现在还在为这件事烦吗？"
    }

    /**
     * 去 base64 + 压空白 + 截断。
     *
     * base64 清洗是冗余防线:素材层已经排除了有图记忆,但记忆内容本身可能内嵌
     * `data:image/...;base64,...`(例如未来某条反射写入的脏数据)。这里是最后一道闸。
     */
    internal fun sanitize(content: String, maxChars: Int): String {
        val withoutBase64 = BASE64_DATA_URI.replace(content, "")
            .let { text ->
                BASE64_RUN.replace(text) { match ->
                    // 只有出现 + / = 才认定是 base64:纯字母数字长串(哈希、订单号)是正常内容,
                    // 一刀切会把用户原话吃掉。
                    if (match.value.any { it == '+' || it == '/' || it == '=' }) "" else match.value
                }
            }
        val flattened = withoutBase64.replace(WHITESPACE, " ").trim()
        return if (flattened.length <= maxChars) flattened else flattened.take(maxChars) + "…"
    }

    private val BASE64_DATA_URI = Regex("data:[^;\\s]{0,64};base64,[A-Za-z0-9+/=]+")
    private val BASE64_RUN = Regex("[A-Za-z0-9+/]{120,}={0,2}")
    private val WHITESPACE = Regex("\\s+")
}
