package com.xiaoqi.companion.core.presence.runtime

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xiaoqi.companion.core.insight.CloudInsightExecutor
import com.xiaoqi.companion.core.insight.EvidenceResolver
import com.xiaoqi.companion.core.insight.InsightOutputParser
import com.xiaoqi.companion.core.insight.InsightPrompts
import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.data.datastore.AppPreferences
import com.xiaoqi.companion.data.repository.InsightRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Dream Loop 主循环(plan §3 + §6.4):每天 1-2 次,跑 `patternDetect` prompt。
 *
 * 触发:由 [DreamLoopScheduler] 调度,WorkManager 周期 6h + 电量约束。
 *
 * 失败处理:
 * - 云端调用超时/网络错误 → `Result.retry()` 让 WorkManager backoff 重试
 * - API Key 缺失/Base URL 非法 → `Result.failure()`,重试也不会自愈
 * - 0 数据(新装 App 第一次) → `Result.success()` 跳过,不浪费 worker
 * - 解析失败/Validator 全拒 → `Result.success()`,数据已尽力
 */
@HiltWorker
class DreamLoopWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dataCollector: DreamDataCollector,
    private val insightExecutor: CloudInsightExecutor,
    private val insightRepository: InsightRepository,
    private val evidenceResolver: EvidenceResolver,
    private val appPreferences: AppPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        AppLogger.info(
            LogTags.Config,
            "dream_loop_started",
            "runAttemptCount" to runAttemptCount,
        )

        if (BatteryHelper.isLow(applicationContext)) {
            AppLogger.info(LogTags.Config, "dream_loop_skipped_low_battery")
            return@withContext Result.success()
        }

        val snapshot = runCatching { dataCollector.collectLast7Days() }
            .onFailure {
                AppLogger.error(
                    LogTags.Config,
                    it,
                    "dream_loop_data_collection_failed",
                )
            }
            .getOrNull()
        if (snapshot == null) return@withContext Result.retry()

        if (snapshot.isEmpty) {
            AppLogger.info(
                LogTags.Config,
                "dream_loop_skipped_empty_data",
                "durationMs" to (System.currentTimeMillis() - startedAt),
            )
            return@withContext Result.success()
        }

        val result = insightExecutor.execute(
            CloudInsightExecutor.Request(
                systemPrompt = InsightPrompts.patternDetect,
                userMessage = dataCollector.render(snapshot),
            ),
        )
        AppLogger.info(
            LogTags.Config,
            "dream_loop_raw_output",
            "textLength" to result.text.length,
            "raw0" to result.text.take(120),
        )
        if (result.errorMessage != null) {
            AppLogger.warn(
                LogTags.Config,
                "dream_loop_executor_error",
                "cause" to result.errorMessage,
                "latencyMs" to result.latencyMs,
            )
            return@withContext if (isConfigurationError(result.errorMessage)) {
                Result.failure(workDataOf(KEY_ERROR to result.errorMessage))
            } else {
                Result.retry()
            }
        }
        if (result.text.isBlank()) {
            AppLogger.warn(
                LogTags.Config,
                "dream_loop_empty_model_output",
                "durationMs" to result.latencyMs,
            )
            return@withContext Result.retry()
        }

        val drafts = InsightOutputParser.parsePatternDetectOutput(result.text)
        if (drafts.isEmpty()) {
            AppLogger.warn(
                LogTags.Config,
                "dream_loop_no_drafts_parsed",
                "latencyMs" to result.latencyMs,
            )
            return@withContext Result.success()
        }

        // Post-hoc evidence resolution: 从 LLM 文本反查真实 DB ID，模型给的 evidence_ids 通常是编造的
        val currentSessionId = appPreferences.currentSessionId.first()
        val resolvedDrafts = evidenceResolver.resolve(drafts, snapshot, currentSessionId)

        resolvedDrafts.forEachIndexed { index, draft ->
            AppLogger.info(
                LogTags.Config,
                "dream_loop_resolved_draft",
                "index" to index,
                "headline" to draft.headline.take(40),
                "msgEvidence" to draft.evidenceMessageIds.size,
                "memEvidence" to draft.evidenceMemoryIds.size,
                "moodEvidence" to draft.evidenceMoodSnapshotIds.size,
                "confidence" to draft.confidence,
            )
        }

        var savedCount = 0
        resolvedDrafts.forEach { draft ->
            val id = insightRepository.saveIfValid(draft)
            if (id != null) savedCount++
        }

        AppLogger.info(
            LogTags.Config,
            "dream_loop_completed",
            "draftsParsed" to drafts.size,
            "saved" to savedCount,
            "latencyMs" to result.latencyMs,
            "durationMs" to (System.currentTimeMillis() - startedAt),
        )
        Result.success(
            workDataOf(
                KEY_SAVED_COUNT to savedCount,
                KEY_DRAFTS_PARSED to drafts.size,
            ),
        )
    }

    private fun isConfigurationError(message: String): Boolean =
        message.contains("API Key", ignoreCase = true) ||
            message.contains("Base URL", ignoreCase = true)

    companion object {
        const val KEY_SAVED_COUNT = "dream_loop_saved_count"
        const val KEY_DRAFTS_PARSED = "dream_loop_drafts_parsed"
        const val KEY_ERROR = "dream_loop_error"
    }
}
