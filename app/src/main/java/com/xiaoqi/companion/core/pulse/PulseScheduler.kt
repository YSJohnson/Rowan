package com.xiaoqi.companion.core.pulse

import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.core.presence.runtime.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Pulse 调度器(照 `core/presence/runtime/DreamLoopScheduler` 的结构)。
 *
 * 必须在 `CompanionApplication.onCreate` 调一次 [start](接线说明见 docs/plan/t6-ui-hook.md):
 * - 启动后台 collector 持续监听开关 + 周期
 * - 关闭时 `cancelPeriodic`,开启/改档时 `enqueuePeriodic(UPDATE)`
 *
 * 依赖注入的 [scope] 用 `core/presence/runtime/ApplicationScope` 限定符
 * (`di/AppCoroutineModule` 已提供全局 scope),测试用 TestScope 替换。
 */
@Singleton
class PulseScheduler @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val pulsePreferences: PulsePreferences,
    private val workScheduler: PulseWorkScheduler,
) {

    private var started = false

    /** 幂等 —— 重复调 [start] 不会创建第二个 collector。 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                pulsePreferences.pulseEnabled,
                pulsePreferences.pulseIntervalMinutes,
            ) { enabled, minutes -> enabled to minutes }
                .distinctUntilChanged()
                .collect { (enabled, minutes) -> applyPeriodic(enabled, minutes) }
        }
    }

    /** 立即跑一次(OneTimeWorkRequest),不影响周期任务。 */
    fun triggerNow() {
        workScheduler.enqueueOneTime(UNIQUE_TRIGGER_NAME)
        AppLogger.info(LogTags.Config, "pulse_trigger_now_enqueued")
    }

    private fun applyPeriodic(enabled: Boolean, intervalMinutes: Int) {
        if (!enabled) {
            workScheduler.cancelPeriodic(UNIQUE_NAME)
            AppLogger.info(LogTags.Config, "pulse_disabled")
            return
        }
        workScheduler.enqueuePeriodic(UNIQUE_NAME, intervalMinutes.toLong())
        AppLogger.info(
            LogTags.Config,
            "pulse_rescheduled",
            "intervalMinutes" to intervalMinutes,
        )
    }

    companion object {
        const val UNIQUE_NAME = "pulse"
        const val UNIQUE_TRIGGER_NAME = "pulse_now"
    }
}
