package com.xiaoqi.companion.core.pulse

import com.xiaoqi.companion.data.db.entity.MoodSnapshotEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pulse(T6 / D2「只在情绪不好时主动关心」)的契约层。
 *
 * 这里只有纯数据 + 纯函数编解码,不依赖 Android / Room / DataStore,便于单测。
 * 判定逻辑在 [PulsePolicy],文案在 [PulseMessageComposer],调度与 IO 在 Scheduler / Worker。
 */

/** 一天聚合出的情绪样本(由 [MoodSnapshotEntity] 降维而来)。 */
data class PulseMoodSample(
    val mood: String,
    val intensity: Float,
    val timestamp: Long,
) {
    companion object {
        fun from(entity: MoodSnapshotEntity): PulseMoodSample = PulseMoodSample(
            mood = entity.mood.ifBlank { "neutral" },
            intensity = entity.intensity,
            timestamp = entity.timestamp,
        )
    }
}

/**
 * 触发原因。两种原因各自独立冷却(见 [PulseConfig.sameReasonCooldownMillis]),
 * 但共用每周总量上限。
 */
enum class PulseTriggerReason {
    /** 连续 ≥ [PulseConfig.minNegativeStreakDays] 天负向情绪且强度超阈值。 */
    NEGATIVE_STREAK,

    /** 最近一天出现单次极端负向(强度 ≥ [PulseConfig.extremeIntensityThreshold])。 */
    EXTREME_NEGATIVE,
}

/** 不触发的原因。落在 [PulseDecision.Skip] 里,用于日志与单测断言。 */
enum class PulseSkipReason {
    /** 开关关闭(默认开启)。 */
    DISABLED,

    /** 窗口内没有任何 mood_snapshots — 新装 App 或用户从不聊天。 */
    NO_MOOD_DATA,

    /** 有数据但没有负向信号(正向/中性/未知标签)。 */
    NO_NEGATIVE_SIGNAL,

    /** 最新一条情绪记录已经太旧(用户最近没再用 App),不追上去打扰。 */
    STALE_DATA,

    /** 同一原因还在冷却期内(含退避拉长后的冷却)。 */
    WITHIN_COOLDOWN,

    /** 滚动 7 天内总次数已达上限。 */
    WEEKLY_LIMIT_REACHED,

    /** 用户连续不理会已达 [PulseConfig.muteAfterUnanswered] 次,彻底停止。 */
    BACKOFF_MUTED,

    /** 找不到可用真实记忆 — 宁可不打扰,也不发空泛问候。 */
    NO_MEMORY_EVIDENCE,
}

/** 一条已发生的主动关怀记录。`answered` = 用户之后是否有回应(用于退避)。 */
data class PulseTriggerRecord(
    val reason: PulseTriggerReason,
    val triggeredAt: Long,
    val answered: Boolean = false,
)

/** 触发时携带的情绪证据(不进文案,只做日志/诊断)。 */
data class PulseMoodEvidence(
    val negativeStreakDays: Int,
    val latestMood: String,
    val latestIntensity: Float,
    val latestSampleAt: Long,
    val peakIntensityInStreak: Float,
)

/** 文案种子:策略只产出"该说什么方向",措辞由 [PulseMessageComposer] 结合真实记忆生成。 */
data class PulseMessageSeed(
    val reason: PulseTriggerReason,
    val negativeStreakDays: Int,
    val latestMood: String,
)

sealed interface PulseDecision {

    data class Trigger(
        val reason: PulseTriggerReason,
        val seed: PulseMessageSeed,
        val evidence: PulseMoodEvidence,
    ) : PulseDecision

    data class Skip(val skip: PulseSkipReason) : PulseDecision
}

/**
 * Pulse 判定参数。
 *
 * [zoneOffsetMillis] 是"本地日"边界的注入点:策略本身不读系统时区,
 * 调用方传 `TimeZone.getDefault().getOffset(now)`;单测传 0 即可按 UTC 切天。
 */
data class PulseConfig(
    /** 负向样本强度下限(含等号)。低于此值的一天不算"负向"。 */
    val negativeIntensityThreshold: Float = 0.6f,
    /** 单次极端负向强度下限(含等号)。 */
    val extremeIntensityThreshold: Float = 0.9f,
    /** 连续负向天数门槛(含等号),文档建议 2。 */
    val minNegativeStreakDays: Int = 2,
    /** 回看窗口天数(含今天)。 */
    val lookbackDays: Int = 7,
    /** 最新情绪记录距今天最多几天,超过即认为用户已经脱离使用状态,不打扰。 */
    val maxStaleDays: Int = 1,
    /** 同原因冷却,建议 7 天(handoff §7 第 3 条)。 */
    val sameReasonCooldownMillis: Long = 7L * DAY_MILLIS,
    /** 每周总量上限的滚动窗口。 */
    val weeklyWindowMillis: Long = 7L * DAY_MILLIS,
    /** 每周最多打扰次数(handoff §7 第 3 条建议 2)。 */
    val maxTriggersPerWeek: Int = 2,
    /** 连续不理达到该次数后开始拉长冷却。 */
    val ignoredStreakThreshold: Int = 2,
    /** 每多一次不理会,冷却额外增加的时长。 */
    val backoffStepMillis: Long = 7L * DAY_MILLIS,
    /** 退避冷却上限。 */
    val maxBackoffMillis: Long = 28L * DAY_MILLIS,
    /** 连续不理达到该次数后彻底停止("多久没理它就别再来")。 */
    val muteAfterUnanswered: Int = 5,
    /** 本地日边界偏移,调用方注入;0 = 按 UTC 切天。 */
    val zoneOffsetMillis: Long = 0L,
    /** 负向情绪标签集合。未命中一律按"非负向"处理 —— 宁可漏报也不无差别打扰。 */
    val negativeMoodLabels: Set<String> = DEFAULT_NEGATIVE_MOOD_LABELS,
) {
    companion object {
        const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

        /**
         * 负向标签表。数据源是 `mood_snapshots.mood`(LLM 自由填写的 mood name,
         * 见 UpdateStateTool 的 `happy/sad/calm/excited/neutral` 提示),所以中英文都收。
         */
        val DEFAULT_NEGATIVE_MOOD_LABELS: Set<String> = setOf(
            "sad", "unhappy", "depressed", "down", "upset", "lonely", "anxious",
            "worried", "stressed", "angry", "frustrated", "hopeless", "grief",
            "grieving", "hurt", "afraid", "scared", "exhausted", "overwhelmed",
            "难过", "伤心", "低落", "消沉", "不开心", "沮丧", "郁闷", "崩溃",
            "焦虑", "紧张", "担心", "害怕", "恐慌", "压力大", "生气", "愤怒",
            "烦", "烦躁", "孤独", "委屈", "无助", "绝望", "累", "疲惫", "心累",
        )
    }
}

/**
 * 触发历史的持久化编解码。
 *
 * 存 DataStore 里的一个 JSON 数组字符串;解析失败一律当空历史 —— 历史丢了最坏结果
 * 是"冷却可能不生效",而抛异常会让 Worker 整个失败。
 */
object PulseTriggerHistoryCodec {

    fun encode(records: List<PulseTriggerRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put(KEY_REASON, record.reason.name)
                    .put(KEY_TRIGGERED_AT, record.triggeredAt)
                    .put(KEY_ANSWERED, record.answered),
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<PulseTriggerRecord> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val reason = PulseTriggerReason.entries
                    .firstOrNull { it.name == obj.optString(KEY_REASON) }
                    ?: return@mapNotNull null
                PulseTriggerRecord(
                    reason = reason,
                    triggeredAt = obj.optLong(KEY_TRIGGERED_AT),
                    answered = obj.optBoolean(KEY_ANSWERED, false),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 只保留 [retainMillis] 内的记录,防止历史无限增长。 */
    fun prune(records: List<PulseTriggerRecord>, nowMillis: Long, retainMillis: Long): List<PulseTriggerRecord> =
        records.filter { nowMillis - it.triggeredAt < retainMillis }

    private const val KEY_REASON = "reason"
    private const val KEY_TRIGGERED_AT = "at"
    private const val KEY_ANSWERED = "answered"
}
