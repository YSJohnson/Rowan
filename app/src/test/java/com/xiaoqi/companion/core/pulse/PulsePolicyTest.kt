package com.xiaoqi.companion.core.pulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PulsePolicy] 判定行为测试(T6 验收单)。
 *
 * 所有时间都由参数注入 —— 用 [NOW] 做锚点,样本按"几天前"构造,不依赖运行机器时钟。
 */
class PulsePolicyTest {

    private val policy = PulsePolicy()
    private val day = PulseConfig.DAY_MILLIS

    private fun sample(daysAgo: Long, mood: String, intensity: Float): PulseMoodSample =
        PulseMoodSample(mood = mood, intensity = intensity, timestamp = NOW - daysAgo * day)

    private fun record(
        reason: PulseTriggerReason,
        daysAgo: Long,
        answered: Boolean = false,
        hourOffset: Long = 0L,
    ): PulseTriggerRecord = PulseTriggerRecord(
        reason = reason,
        triggeredAt = NOW - daysAgo * day + hourOffset,
        answered = answered,
    )

    private fun evaluate(
        samples: List<PulseMoodSample>,
        history: List<PulseTriggerRecord> = emptyList(),
        enabled: Boolean = true,
        config: PulseConfig = PulseConfig(),
        nowMillis: Long = NOW,
    ): PulseDecision = policy.evaluate(
        samples = samples,
        nowMillis = nowMillis,
        history = history,
        enabled = enabled,
        config = config,
    )

    private fun assertSkip(decision: PulseDecision, expected: PulseSkipReason) {
        val skip = decision as? PulseDecision.Skip
            ?: throw AssertionError("expected Skip($expected) but was Trigger: $decision")
        assertEquals(expected, skip.skip)
    }

    private fun assertTrigger(decision: PulseDecision, expected: PulseTriggerReason): PulseDecision.Trigger {
        val trigger = decision as? PulseDecision.Trigger
            ?: throw AssertionError("expected Trigger($expected) but was Skip: $decision")
        assertEquals(expected, trigger.reason)
        return trigger
    }

    // ── 触发条件 ──

    @Test
    fun threeNegativeDays_triggersNegativeStreak() {
        val decision = evaluate(
            listOf(
                sample(2, "sad", 0.8f),
                sample(1, "难过", 0.7f),
                sample(0, "anxious", 0.75f),
            ),
        )

        val trigger = assertTrigger(decision, PulseTriggerReason.NEGATIVE_STREAK)
        assertEquals(3, trigger.seed.negativeStreakDays)
        assertEquals(3, trigger.evidence.negativeStreakDays)
        assertEquals("anxious", trigger.evidence.latestMood)
        assertEquals(0.8f, trigger.evidence.peakIntensityInStreak, 0.0001f)
    }

    @Test
    fun twoNegativeDays_triggersAtStreakThreshold() {
        val decision = evaluate(listOf(sample(1, "sad", 0.7f), sample(0, "sad", 0.7f)))

        assertEquals(2, assertTrigger(decision, PulseTriggerReason.NEGATIVE_STREAK).seed.negativeStreakDays)
    }

    @Test
    fun singleNegativeDay_doesNotTrigger() {
        assertSkip(evaluate(listOf(sample(0, "sad", 0.8f))), PulseSkipReason.NO_NEGATIVE_SIGNAL)
    }

    @Test
    fun positiveMoods_doNotTrigger() {
        val decision = evaluate(
            listOf(
                sample(2, "happy", 0.9f),
                sample(1, "calm", 0.6f),
                sample(0, "excited", 0.95f),
            ),
        )

        assertSkip(decision, PulseSkipReason.NO_NEGATIVE_SIGNAL)
    }

    @Test
    fun positiveDayInsideStreak_breaksContinuity() {
        val decision = evaluate(
            listOf(
                sample(2, "sad", 0.8f),
                sample(1, "happy", 0.9f),
                sample(0, "sad", 0.8f),
            ),
        )

        assertSkip(decision, PulseSkipReason.NO_NEGATIVE_SIGNAL)
    }

    @Test
    fun negativeMoodBelowIntensityThreshold_doesNotTrigger() {
        val decision = evaluate(
            listOf(sample(1, "sad", 0.5f), sample(0, "sad", 0.59f)),
        )

        assertSkip(decision, PulseSkipReason.NO_NEGATIVE_SIGNAL)
    }

    @Test
    fun intensityExactlyAtThreshold_counts() {
        val decision = evaluate(listOf(sample(1, "sad", 0.6f), sample(0, "sad", 0.6f)))

        assertTrigger(decision, PulseTriggerReason.NEGATIVE_STREAK)
    }

    @Test
    fun singleExtremeNegative_triggersExtremeReason() {
        val decision = evaluate(listOf(sample(0, "绝望", 0.95f)))
        val trigger = assertTrigger(decision, PulseTriggerReason.EXTREME_NEGATIVE)

        assertEquals(1, trigger.evidence.negativeStreakDays)
    }

    @Test
    fun intensityExactlyAtExtremeThreshold_triggersExtremeReason() {
        assertTrigger(
            evaluate(listOf(sample(0, "sad", 0.9f))),
            PulseTriggerReason.EXTREME_NEGATIVE,
        )
    }

    @Test
    fun extremeOutranksStreakReason() {
        val decision = evaluate(
            listOf(
                sample(2, "sad", 0.7f),
                sample(1, "sad", 0.7f),
                sample(0, "sad", 0.95f),
            ),
        )

        assertTrigger(decision, PulseTriggerReason.EXTREME_NEGATIVE)
    }

    @Test
    fun mixedDay_countsAsNegative() {
        val decision = evaluate(
            listOf(
                sample(1, "sad", 0.7f),
                sample(0, "happy", 0.9f),
                sample(0, "sad", 0.7f),
            ),
        )

        assertTrigger(decision, PulseTriggerReason.NEGATIVE_STREAK)
    }

    @Test
    fun unknownMoodLabel_isTreatedAsNonNegative() {
        val decision = evaluate(listOf(sample(1, "惆怅", 0.95f), sample(0, "惆怅", 0.95f)))

        assertSkip(decision, PulseSkipReason.NO_NEGATIVE_SIGNAL)
    }

    // ── 边界 ──

    @Test
    fun emptySamples_skipsWithNoMoodData() {
        assertSkip(evaluate(emptyList()), PulseSkipReason.NO_MOOD_DATA)
    }

    @Test
    fun samplesOutsideLookbackWindow_skipWithNoMoodData() {
        val decision = evaluate(listOf(sample(9, "sad", 0.9f), sample(8, "sad", 0.9f)))

        assertSkip(decision, PulseSkipReason.NO_MOOD_DATA)
    }

    @Test
    fun staleLatestSample_skipsInsteadOfLateCare() {
        val decision = evaluate(listOf(sample(6, "sad", 0.9f), sample(5, "sad", 0.9f)))

        assertSkip(decision, PulseSkipReason.STALE_DATA)
    }

    @Test
    fun disabledSwitch_skipsEvenWithStrongSignal() {
        val decision = evaluate(
            samples = listOf(sample(1, "sad", 0.9f), sample(0, "sad", 0.9f)),
            enabled = false,
        )

        assertSkip(decision, PulseSkipReason.DISABLED)
    }

    // ── 冷却 ──

    @Test
    fun sameReasonInsideCooldown_doesNotTrigger() {
        val decision = evaluate(
            samples = listOf(sample(1, "sad", 0.8f), sample(0, "sad", 0.8f)),
            history = listOf(record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 3)),
        )

        assertSkip(decision, PulseSkipReason.WITHIN_COOLDOWN)
    }

    @Test
    fun sameReasonExactlyAtCooldownBoundary_triggers() {
        val decision = evaluate(
            samples = listOf(sample(1, "sad", 0.8f), sample(0, "sad", 0.8f)),
            history = listOf(record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 7)),
        )

        assertTrigger(decision, PulseTriggerReason.NEGATIVE_STREAK)
    }

    @Test
    fun otherReasonCooldown_doesNotBlockThisReason() {
        val decision = evaluate(
            samples = listOf(sample(1, "sad", 0.8f), sample(0, "sad", 0.8f)),
            history = listOf(record(PulseTriggerReason.EXTREME_NEGATIVE, daysAgo = 1)),
        )

        assertTrigger(decision, PulseTriggerReason.NEGATIVE_STREAK)
    }

    @Test
    fun weeklyLimitReached_doesNotTrigger() {
        val decision = evaluate(
            samples = listOf(sample(1, "sad", 0.8f), sample(0, "sad", 0.8f)),
            history = listOf(
                record(PulseTriggerReason.EXTREME_NEGATIVE, daysAgo = 1),
                record(PulseTriggerReason.EXTREME_NEGATIVE, daysAgo = 2),
            ),
        )

        assertSkip(decision, PulseSkipReason.WEEKLY_LIMIT_REACHED)
    }

    @Test
    fun triggersOlderThanWeeklyWindow_doNotCountTowardsLimit() {
        val decision = evaluate(
            samples = listOf(sample(1, "sad", 0.8f), sample(0, "sad", 0.8f)),
            history = listOf(
                record(PulseTriggerReason.EXTREME_NEGATIVE, daysAgo = 9),
                record(PulseTriggerReason.EXTREME_NEGATIVE, daysAgo = 10),
            ),
        )

        assertTrigger(decision, PulseTriggerReason.NEGATIVE_STREAK)
    }

    // ── 退避 ──

    @Test
    fun effectiveCooldown_staysBaseBelowIgnoredThreshold() {
        assertEquals(
            PulseConfig().sameReasonCooldownMillis,
            policy.effectiveCooldownMillis(PulseTriggerReason.NEGATIVE_STREAK, unansweredStreak = 1),
        )
    }

    @Test
    fun effectiveCooldown_growsByStepPerIgnoredStreak() {
        val config = PulseConfig()

        assertEquals(14 * day, policy.effectiveCooldownMillis(PulseTriggerReason.NEGATIVE_STREAK, 2, config))
        assertEquals(21 * day, policy.effectiveCooldownMillis(PulseTriggerReason.NEGATIVE_STREAK, 3, config))
    }

    @Test
    fun effectiveCooldown_isCappedAtMaxBackoff() {
        val config = PulseConfig()

        assertEquals(config.maxBackoffMillis, policy.effectiveCooldownMillis(PulseTriggerReason.NEGATIVE_STREAK, 4, config))
        assertEquals(28 * day, policy.effectiveCooldownMillis(PulseTriggerReason.NEGATIVE_STREAK, 9, config))
    }

    @Test
    fun ignoredTwice_extendsCooldownToTwoWeeks() {
        val samples = listOf(sample(1, "sad", 0.8f), sample(0, "sad", 0.8f))
        val history = listOf(
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 8),
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 9),
        )

        // 基础冷却 7 天,8 天前那次本应放行;退避把它拉到 14 天。
        assertSkip(evaluate(samples, history), PulseSkipReason.WITHIN_COOLDOWN)
    }

    @Test
    fun answeredHistory_doesNotExtendCooldown() {
        val samples = listOf(sample(1, "sad", 0.8f), sample(0, "sad", 0.8f))
        val history = listOf(
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 8, answered = true),
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 9, answered = true),
        )

        assertTrigger(evaluate(samples, history), PulseTriggerReason.NEGATIVE_STREAK)
    }

    @Test
    fun backoffCapBoundary_triggersPastCap() {
        val samples = listOf(sample(1, "sad", 0.8f), sample(0, "sad", 0.8f))
        val history = listOf(
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 29),
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 30),
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 31),
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 32),
        )

        assertTrigger(evaluate(samples, history), PulseTriggerReason.NEGATIVE_STREAK)
    }

    @Test
    fun fiveUnanswered_mutesPulseEntirely() {
        val samples = listOf(sample(1, "sad", 0.9f), sample(0, "sad", 0.9f))
        val history = (40L..44L).map { record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = it) }

        assertSkip(evaluate(samples, history), PulseSkipReason.BACKOFF_MUTED)
    }

    @Test
    fun consecutiveUnanswered_countsTrailingRunOnly() {
        val history = listOf(
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 5, answered = false),
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 4, answered = true),
            record(PulseTriggerReason.EXTREME_NEGATIVE, daysAgo = 3, answered = false),
            record(PulseTriggerReason.EXTREME_NEGATIVE, daysAgo = 2, answered = false),
        )

        assertEquals(2, policy.consecutiveUnanswered(history))
        assertEquals(0, policy.consecutiveUnanswered(emptyList()))
    }

    @Test
    fun consecutiveUnanswered_ignoresRecordOrder() {
        val history = listOf(
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 1),
            record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = 3),
        )

        assertEquals(2, policy.consecutiveUnanswered(history))
    }

    @Test
    fun historyWithFutureTimestamp_doesNotTrigger() {
        // 时钟回拨 / 脏数据:未来时间的记录不该让策略崩掉,也不该放开冷却。
        val samples = listOf(sample(1, "sad", 0.8f), sample(0, "sad", 0.8f))
        val history = listOf(record(PulseTriggerReason.NEGATIVE_STREAK, daysAgo = -10))

        assertSkip(evaluate(samples, history), PulseSkipReason.WITHIN_COOLDOWN)
    }

    // ── 时区注入 ──

    @Test
    fun dayIndex_appliesInjectedZoneOffset() {
        val offset = 8L * 60L * 60L * 1000L
        val localMidnight = 0L
        val sameLocalDayLater = 15L * 60L * 60L * 1000L
        val nextLocalDay = 17L * 60L * 60L * 1000L

        assertEquals(policy.dayIndex(localMidnight, offset), policy.dayIndex(sameLocalDayLater, offset))
        assertEquals(policy.dayIndex(localMidnight, offset) + 1, policy.dayIndex(nextLocalDay, offset))
    }

    @Test
    fun injectedZoneOffset_changesDayBucketing() {
        // 两条样本:UTC 11-14 20:00 / UTC 11-15 01:00。
        // 按 UTC 切是两天(触发连续负向);按 +08:00 切是同一个本地日(不触发)。
        val offset = 8L * 60L * 60L * 1000L
        val now = 1_700_013_600_000L
        val samples = listOf(
            PulseMoodSample("sad", 0.8f, now - 5L * 60L * 60L * 1000L),
            PulseMoodSample("sad", 0.8f, now - 60L * 60L * 1000L),
        )

        assertTrigger(
            evaluate(samples, nowMillis = now),
            PulseTriggerReason.NEGATIVE_STREAK,
        )
        assertSkip(
            evaluate(samples, config = PulseConfig(zoneOffsetMillis = offset), nowMillis = now),
            PulseSkipReason.NO_NEGATIVE_SIGNAL,
        )
    }

    @Test
    fun triggerDecision_carriesSeedOfChosenReason() {
        val trigger = assertTrigger(
            evaluate(listOf(sample(1, "sad", 0.8f), sample(0, "sad", 0.8f))),
            PulseTriggerReason.NEGATIVE_STREAK,
        )

        assertEquals(PulseTriggerReason.NEGATIVE_STREAK, trigger.seed.reason)
        assertTrue(trigger.seed.latestMood.isNotBlank())
    }

    companion object {
        /** 2023-11-14T22:13:20Z,对时区无特殊含义,只做锚点。 */
        private const val NOW = 1_700_000_000_000L
    }
}
