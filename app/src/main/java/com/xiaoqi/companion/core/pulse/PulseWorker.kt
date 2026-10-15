package com.xiaoqi.companion.core.pulse

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.data.db.dao.MoodSnapshotDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulse 周期检查(T6 / D2)。
 *
 * 每次醒来只做四件事:读情绪序列 → [PulsePolicy] 判定 → 挑一条真实记忆 →
 * 把文案写进 [PulsePreferences.pendingMessage]。
 * 判定不通过就什么都不做 —— 这个 Worker 的大部分运行结果都应该是"不打扰"。
 *
 * 通知投放不在本类里(见 docs/plan/t6-ui-hook.md):
 * 文案落在 `pulse_pending_message`,同时通过 Result 的 outputData 带出
 * `KEY_MESSAGE` / `KEY_REASON`,接线方取一处即可。
 */
@HiltWorker
class PulseWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val moodSnapshotDao: MoodSnapshotDao,
    private val pulsePreferences: PulsePreferences,
    private val pulsePolicy: PulsePolicy,
    private val memorySource: PulseMemorySource,
    private val notificationPoster: PulseNotificationPoster,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val prefs = pulsePreferences.snapshot()

        if (!prefs.enabled) {
            AppLogger.info(LogTags.Config, "pulse_skipped_disabled")
            return@withContext Result.success()
        }

        val decision = runCatching {
            val samples = loadMoodSamples(now)
            pulsePolicy.evaluate(
                samples = samples,
                nowMillis = now,
                history = prefs.history,
                enabled = prefs.enabled,
                config = defaultConfig(now),
            )
        }.onFailure {
            AppLogger.error(LogTags.Config, it, "pulse_evaluate_failed")
        }.getOrNull() ?: return@withContext Result.success()

        val trigger = decision as? PulseDecision.Trigger
        if (trigger == null) {
            val skip = (decision as PulseDecision.Skip).skip
            AppLogger.info(
                LogTags.Config,
                "pulse_skipped",
                "reason" to skip.name,
                "historySize" to prefs.history.size,
            )
            return@withContext Result.success()
        }

        val memories = runCatching {
            memorySource.recentMemories(MEMORY_FETCH_LIMIT)
        }.getOrDefault(emptyList())
        val candidate = PulseMemorySelector.pick(memories, now)
        val message = PulseMessageComposer.compose(trigger.seed, candidate)
        if (message == null) {
            AppLogger.info(
                LogTags.Config,
                "pulse_skipped",
                "reason" to PulseSkipReason.NO_MEMORY_EVIDENCE.name,
                "triggerReason" to trigger.reason.name,
                "memoriesConsidered" to memories.size,
            )
            return@withContext Result.success()
        }

        pulsePreferences.recordTrigger(trigger.reason, now)
        pulsePreferences.setPendingMessage(message)
        // Worker 是唯一能在后台把通知推出去的地方；留在 UI 层收集会让离开前台时彻底静音。
        notificationPoster.postPending()
        AppLogger.info(
            LogTags.Config,
            "pulse_triggered",
            "reason" to trigger.reason.name,
            "negativeStreakDays" to trigger.evidence.negativeStreakDays,
            "latestMood" to trigger.evidence.latestMood,
            "latestIntensity" to trigger.evidence.latestIntensity,
            "memoryId" to candidate?.id,
            "messageLength" to message.length,
        )
        Result.success(
            workDataOf(
                KEY_MESSAGE to message,
                KEY_REASON to trigger.reason.name,
            ),
        )
    }

    private suspend fun loadMoodSamples(now: Long): List<PulseMoodSample> {
        val start = now - LOOKBACK_DAYS * PulseConfig.DAY_MILLIS
        return moodSnapshotDao.findInRange(DEFAULT_COMPANION_ID, start, now)
            .map(PulseMoodSample::from)
    }

    /** 时区只在调用侧注入一次:本地日边界必须跟用户手机一致,策略本身保持无时区依赖。 */
    private fun defaultConfig(now: Long): PulseConfig = PulseConfig(
        zoneOffsetMillis = TimeZone.getDefault().getOffset(now).toLong(),
    )

    companion object {
        const val KEY_MESSAGE = "pulse_message"
        const val KEY_REASON = "pulse_reason"

        const val DEFAULT_COMPANION_ID = "default"
        const val LOOKBACK_DAYS = 7
        const val MEMORY_FETCH_LIMIT = 200
    }
}
