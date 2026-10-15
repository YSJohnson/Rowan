package com.xiaoqi.companion.core.pulse

import javax.inject.Inject
import javax.inject.Singleton

/**
 * T6 / D2 的核心判定:现在该不该主动关心用户。
 *
 * **纯函数** —— 不读 DB、不读系统时间、不读 DataStore。mood 序列、当前时间戳、
 * 历史触发记录、配置全部由参数注入,因此可以穷举边界做单测(见 PulsePolicyTest)。
 *
 * 判定顺序:
 *  1. 开关关 → [PulseSkipReason.DISABLED]
 *  2. 回看窗口内无数据 → [PulseSkipReason.NO_MOOD_DATA]
 *  3. 最近一天太旧(用户已经不记情绪了) → [PulseSkipReason.STALE_DATA]
 *  4. 最新一天极端负向 → [PulseTriggerReason.EXTREME_NEGATIVE];
 *     否则连续负向天数 ≥ 门槛 → [PulseTriggerReason.NEGATIVE_STREAK];
 *     都不满足 → [PulseSkipReason.NO_NEGATIVE_SIGNAL]
 *  5. 退避静音 → 同原因冷却(退避会拉长) → 每周上限,任一命中即不打扰
 *
 * "负向一天"的口径:该天存在 mood 标签命中 [PulseConfig.negativeMoodLabels]
 * 且 intensity ≥ [PulseConfig.negativeIntensityThreshold] 的样本。
 * 一天内正负情绪都有时按负向算 —— 情绪波动日更应该关心,而不是更不该。
 */
@Singleton
class PulsePolicy @Inject constructor() {

    fun evaluate(
        samples: List<PulseMoodSample>,
        nowMillis: Long,
        history: List<PulseTriggerRecord>,
        enabled: Boolean = true,
        config: PulseConfig = PulseConfig(),
    ): PulseDecision {
        if (!enabled) return PulseDecision.Skip(PulseSkipReason.DISABLED)

        val windowStart = nowMillis - config.lookbackDays * PulseConfig.DAY_MILLIS
        val windowed = samples.filter { it.timestamp in windowStart..nowMillis }
        if (windowed.isEmpty()) return PulseDecision.Skip(PulseSkipReason.NO_MOOD_DATA)

        val byDay = windowed.groupBy { dayIndex(it.timestamp, config.zoneOffsetMillis) }
        val latestDay = byDay.keys.max()
        val todayIndex = dayIndex(nowMillis, config.zoneOffsetMillis)
        if (todayIndex - latestDay > config.maxStaleDays) {
            return PulseDecision.Skip(PulseSkipReason.STALE_DATA)
        }

        val latestDaySamples = byDay[latestDay].orEmpty()
        val streakDays = negativeStreakDays(byDay, latestDay, config)
        val extremePeak = negativePeak(latestDaySamples, config)
            ?.takeIf { it >= config.extremeIntensityThreshold }
        val streakPeak = if (streakDays > 0) {
            (0 until streakDays)
                .mapNotNull { negativePeak(byDay[latestDay - it].orEmpty(), config) }
                .maxOrNull()
        } else {
            null
        }

        val reason = when {
            extremePeak != null -> PulseTriggerReason.EXTREME_NEGATIVE
            streakDays >= config.minNegativeStreakDays -> PulseTriggerReason.NEGATIVE_STREAK
            else -> null
        } ?: return PulseDecision.Skip(PulseSkipReason.NO_NEGATIVE_SIGNAL)

        val unresolved = consecutiveUnanswered(history)
        if (unresolved >= config.muteAfterUnanswered) {
            return PulseDecision.Skip(PulseSkipReason.BACKOFF_MUTED)
        }

        val lastSameReason = history
            .filter { it.reason == reason }
            .maxOfOrNull { it.triggeredAt }
        if (lastSameReason != null &&
            nowMillis - lastSameReason < effectiveCooldownMillis(reason, unresolved, config)
        ) {
            return PulseDecision.Skip(PulseSkipReason.WITHIN_COOLDOWN)
        }

        val recentTriggers = history.count { nowMillis - it.triggeredAt < config.weeklyWindowMillis }
        if (recentTriggers >= config.maxTriggersPerWeek) {
            return PulseDecision.Skip(PulseSkipReason.WEEKLY_LIMIT_REACHED)
        }

        val latest = latestDaySamples.maxByOrNull { it.timestamp }!!
        return PulseDecision.Trigger(
            reason = reason,
            seed = PulseMessageSeed(
                reason = reason,
                negativeStreakDays = streakDays,
                latestMood = latest.mood,
            ),
            evidence = PulseMoodEvidence(
                negativeStreakDays = streakDays,
                latestMood = latest.mood,
                latestIntensity = latest.intensity,
                latestSampleAt = latest.timestamp,
                peakIntensityInStreak = streakPeak ?: latest.intensity,
            ),
        )
    }

    /**
     * 距最近一次同原因触发的实际冷却时长(退避生效时被拉长)。
     *
     * 连续不理 ≥ [PulseConfig.ignoredStreakThreshold] 次后,每多一次加一个
     * [PulseConfig.backoffStepMillis],上限 [PulseConfig.maxBackoffMillis]。
     */
    fun effectiveCooldownMillis(
        reason: PulseTriggerReason,
        unansweredStreak: Int,
        config: PulseConfig = PulseConfig(),
    ): Long {
        val base = config.sameReasonCooldownMillis
        if (unansweredStreak < config.ignoredStreakThreshold) return base
        val extraSteps = unansweredStreak - config.ignoredStreakThreshold + 1L
        return (base + extraSteps * config.backoffStepMillis)
            .coerceAtMost(config.maxBackoffMillis)
    }

    /**
     * 末尾连续"未回应"次数(按时间升序后从最新往回数)。
     * 用户回应过之后,退避次数归零。
     */
    fun consecutiveUnanswered(history: List<PulseTriggerRecord>): Int =
        history.sortedBy { it.triggeredAt }
            .reversed()
            .takeWhile { !it.answered }
            .size

    private fun negativeStreakDays(
        byDay: Map<Long, List<PulseMoodSample>>,
        latestDay: Long,
        config: PulseConfig,
    ): Int {
        var streak = 0
        var day = latestDay
        while (true) {
            val peak = negativePeak(byDay[day].orEmpty(), config) ?: break
            if (peak < config.negativeIntensityThreshold) break
            streak++
            day--
        }
        return streak
    }

    /** 该天负向样本的峰值强度;没有负向样本时返回 null。 */
    private fun negativePeak(samples: List<PulseMoodSample>, config: PulseConfig): Float? =
        samples.filter { isNegative(it.mood, config.negativeMoodLabels) }
            .maxOfOrNull { it.intensity }

    private fun isNegative(mood: String, labels: Set<String>): Boolean =
        labels.contains(mood.trim().lowercase())

    /**
     * 把时间戳折算成"本地日"序号。调用方通过 [PulseConfig.zoneOffsetMillis] 注入时区,
     * 策略本身不读 `TimeZone.getDefault()`,否则单测会随运行机器时区漂移。
     */
    internal fun dayIndex(timestamp: Long, zoneOffsetMillis: Long): Long =
        Math.floorDiv(timestamp + zoneOffsetMillis, PulseConfig.DAY_MILLIS)
}
