package com.xiaoqi.companion.core.pulse

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulse 的 WorkManager 门面,形状与 `core/presence/runtime/WorkScheduler` 完全一致
 * (enqueuePeriodic / cancelPeriodic / enqueueOneTime)。
 *
 * 之所以不复用那个接口:它唯一的实现 `WorkManagerScheduler` 把 `DreamLoopWorker` 写死在
 * 请求构造里,**没有 worker class 参数**。PulseScheduler 若注入它,enqueue 出来的会是
 * DreamLoop 而不是 PulseWorker。T6 的写范围不允许改 core/presence/runtime,
 * 所以这里照抄它的语义(UPDATE / REPLACE + 电量约束),换成本模块的 worker。
 */
interface PulseWorkScheduler {
    fun enqueuePeriodic(uniqueName: String, intervalMinutes: Long)
    fun cancelPeriodic(uniqueName: String)
    fun enqueueOneTime(uniqueName: String)
}

/**
 * [PulseWorkScheduler] 的 Android 实现。
 *
 * 周期任务用 [ExistingPeriodicWorkPolicy.UPDATE]:冷启动时 unique 不存在等同于 KEEP,
 * 用户改周期时 WorkManager 内部更新参数,不重启 Worker。
 * 一次性任务用 [ExistingWorkPolicy.REPLACE]:同一 unique 只保留最近一次。
 */
@Singleton
class WorkManagerPulseScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : PulseWorkScheduler {

    override fun enqueuePeriodic(uniqueName: String, intervalMinutes: Long) {
        val minutes = intervalMinutes.coerceAtLeast(MIN_PERIODIC_MINUTES)
        val request = PeriodicWorkRequestBuilder<PulseWorker>(
            minutes, TimeUnit.MINUTES,
        ).setConstraints(BATTERY_CONSTRAINTS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancelPeriodic(uniqueName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName)
    }

    override fun enqueueOneTime(uniqueName: String) {
        val request = OneTimeWorkRequestBuilder<PulseWorker>()
            .setConstraints(BATTERY_CONSTRAINTS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        /** WorkManager 周期任务硬下限 15min;配置被写坏时兜底,不再往上报错。 */
        const val MIN_PERIODIC_MINUTES = 15L

        private val BATTERY_CONSTRAINTS = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
    }
}
