package com.xiaoqi.companion.core.pulse

import com.xiaoqi.companion.data.db.entity.MoodSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约层编解码 + 实体降维测试(纯 JVM,不碰 DataStore/Room)。
 */
class PulseModelsTest {

    private val now = 1_700_000_000_000L

    @Test
    fun historyCodec_roundTripsAllFields() {
        val records = listOf(
            PulseTriggerRecord(PulseTriggerReason.NEGATIVE_STREAK, now - 1000L, answered = false),
            PulseTriggerRecord(PulseTriggerReason.EXTREME_NEGATIVE, now, answered = true),
        )

        assertEquals(records, PulseTriggerHistoryCodec.decode(PulseTriggerHistoryCodec.encode(records)))
    }

    @Test
    fun historyCodec_emptyInputs() {
        assertEquals(emptyList<PulseTriggerRecord>(), PulseTriggerHistoryCodec.decode(null))
        assertEquals(emptyList<PulseTriggerRecord>(), PulseTriggerHistoryCodec.decode(""))
        assertEquals("[]", PulseTriggerHistoryCodec.encode(emptyList()))
    }

    @Test
    fun historyCodec_invalidJsonDegradesToEmptyHistory() {
        assertEquals(emptyList<PulseTriggerRecord>(), PulseTriggerHistoryCodec.decode("{not json"))
    }

    @Test
    fun historyCodec_skipsUnknownReasonInsteadOfThrowing() {
        val json = """[{"reason":"MOON_PHASE","at":1,"answered":false},
            |{"reason":"NEGATIVE_STREAK","at":2,"answered":true}]""".trimMargin()

        val decoded = PulseTriggerHistoryCodec.decode(json)

        assertEquals(1, decoded.size)
        assertEquals(PulseTriggerReason.NEGATIVE_STREAK, decoded.first().reason)
        assertTrue(decoded.first().answered)
    }

    @Test
    fun historyCodec_pruneDropsRecordsOlderThanRetention() {
        val records = listOf(
            PulseTriggerRecord(PulseTriggerReason.NEGATIVE_STREAK, now - 100L),
            PulseTriggerRecord(PulseTriggerReason.NEGATIVE_STREAK, now - 10_000L),
        )

        val pruned = PulseTriggerHistoryCodec.prune(records, now, retainMillis = 1000L)

        assertEquals(1, pruned.size)
        assertEquals(now - 100L, pruned.first().triggeredAt)
    }

    @Test
    fun moodSampleFrom_entityNormalizesBlankMood() {
        val sample = PulseMoodSample.from(
            MoodSnapshotEntity(
                id = "s1",
                companionId = "default",
                mood = "  ",
                intensity = 0.8f,
                timestamp = now,
            ),
        )

        assertEquals("neutral", sample.mood)
        assertEquals(0.8f, sample.intensity, 0.0001f)
        assertEquals(now, sample.timestamp)
    }

    @Test
    fun configDefaults_matchHandoffFrequencyCap() {
        val config = PulseConfig()

        // handoff §7 第 3 条:同一原因 7 天内最多 1 次,总体每周最多 2 次。
        assertEquals(7L * PulseConfig.DAY_MILLIS, config.sameReasonCooldownMillis)
        assertEquals(2, config.maxTriggersPerWeek)
        assertEquals(2, config.minNegativeStreakDays)
        assertTrue(config.extremeIntensityThreshold > config.negativeIntensityThreshold)
    }
}
