package com.xiaoqi.companion.core.pulse

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Pulse 自己的偏好读写。
 *
 * 独立文件是刻意的:[com.xiaoqi.companion.data.datastore.AppPreferences] 是多人并行改动的
 * 热点,Pulse 只往里加 key 会和别的任务冲突。这里复用同一个 `DataStore<Preferences>`
 * 实例(DataModule 的 @Provides 是 public),所以底层仍是同一个 `companion_settings` 文件,
 * 只是 key 命名空间归 Pulse 所有。
 */
@Singleton
class PulsePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** 主动关怀总开关,默认开启但可关(D2:不允许定时无差别打扰,但允许用户彻底静音)。 */
    val pulseEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.enabled] ?: true }

    /** 后台检查周期(分钟)。默认 4h —— 只是"看一眼情绪序列",真正发不发由冷却决定。 */
    val pulseIntervalMinutes: Flow<Int> = dataStore.data.map {
        it[Keys.intervalMinutes] ?: DEFAULT_INTERVAL_MINUTES
    }

    /** 已发生的主动关怀记录(JSON),供 [PulsePolicy] 做冷却/退避判定。 */
    val triggerHistory: Flow<List<PulseTriggerRecord>> = dataStore.data.map {
        PulseTriggerHistoryCodec.decode(it[Keys.triggerHistoryJson])
    }

    /**
     * 等待消费的主动消息。Worker 生成后写这里,通知/UI 层取走后清空。
     * 通知接线未做(见 docs/plan/t6-ui-hook.md),这里就是留给接线的缝。
     */
    val pendingMessage: Flow<String?> = dataStore.data.map { it[Keys.pendingMessage] }

    suspend fun setPulseEnabled(value: Boolean) {
        dataStore.edit { it[Keys.enabled] = value }
    }

    suspend fun setPulseIntervalMinutes(minutes: Int) {
        dataStore.edit { it[Keys.intervalMinutes] = minutes }
    }

    suspend fun setPendingMessage(message: String?) {
        dataStore.edit { prefs ->
            if (message.isNullOrBlank()) prefs.remove(Keys.pendingMessage) else prefs[Keys.pendingMessage] = message
        }
    }

    /** 追加一条触发记录。[now] 由调用方注入,便于测试与历史裁剪。 */
    suspend fun recordTrigger(
        reason: PulseTriggerReason,
        now: Long,
        answered: Boolean = false,
    ) {
        dataStore.edit { prefs ->
            val existing = PulseTriggerHistoryCodec.decode(prefs[Keys.triggerHistoryJson])
            val updated = existing + PulseTriggerRecord(reason, now, answered)
            prefs[Keys.triggerHistoryJson] = PulseTriggerHistoryCodec.encode(
                PulseTriggerHistoryCodec.prune(updated, now, HISTORY_RETAIN_MILLIS),
            )
        }
    }

    /**
     * 用户回应了 → 把最近一条未回应记录标为 answered,退避计数归零。
     * 接线点:ChatViewModel 收到用户消息时调一次(见 docs/plan/t6-ui-hook.md)。
     */
    suspend fun markLatestAnswered() {
        dataStore.edit { prefs ->
            val existing = PulseTriggerHistoryCodec.decode(prefs[Keys.triggerHistoryJson])
            val lastUnansweredIndex = existing.indexOfLast { !it.answered }
            if (lastUnansweredIndex >= 0) {
                val updated = existing.toMutableList()
                updated[lastUnansweredIndex] = updated[lastUnansweredIndex].copy(answered = true)
                prefs[Keys.triggerHistoryJson] = PulseTriggerHistoryCodec.encode(updated)
            }
            prefs.remove(Keys.pendingMessage)
        }
    }

    suspend fun clearHistory() {
        dataStore.edit { it.remove(Keys.triggerHistoryJson) }
    }

    /** 供 Worker 一次性读取判定所需的全部输入,避免多次 `dataStore.data` 重放。 */
    suspend fun snapshot(): Snapshot = dataStore.data.first().let { prefs ->
        Snapshot(
            enabled = prefs[Keys.enabled] ?: true,
            intervalMinutes = prefs[Keys.intervalMinutes] ?: DEFAULT_INTERVAL_MINUTES,
            history = PulseTriggerHistoryCodec.decode(prefs[Keys.triggerHistoryJson]),
        )
    }

    data class Snapshot(
        val enabled: Boolean,
        val intervalMinutes: Int,
        val history: List<PulseTriggerRecord>,
    )

    object Keys {
        val enabled = booleanPreferencesKey("pulse_enabled")
        val intervalMinutes = intPreferencesKey("pulse_interval_minutes")
        val triggerHistoryJson = stringPreferencesKey("pulse_trigger_history_json")
        val pendingMessage = stringPreferencesKey("pulse_pending_message")
    }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 240

        /** 历史只保留 60 天 —— 覆盖最长退避(28 天)+ 每周窗口的两倍余量。 */
        const val HISTORY_RETAIN_MILLIS = 60L * PulseConfig.DAY_MILLIS
    }
}
