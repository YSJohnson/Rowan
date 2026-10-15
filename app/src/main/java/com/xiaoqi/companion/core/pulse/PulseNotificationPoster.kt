package com.xiaoqi.companion.core.pulse

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.xiaoqi.companion.MainActivity
import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 把 [PulsePreferences.pendingMessage] 投成一条通知（T6 §3-T6 第 4 条）。
 *
 * 与提醒共用「Aura」渠道体系但用独立 channel：主动关怀的分量和用户自己设的提醒不同，
 * 用户可能想单独静音它。文案已经由 [PulseMessageComposer] 基于真实记忆生成，
 * 这里只负责投递，不做任何加工 —— 尤其**不要**再拼接通用问候。
 *
 * 消费即清空：`pendingMessage` 是"最近一条未消费文案"不是队列，
 * 投完调 [PulsePreferences.setPendingMessage] 置空，避免下次误投旧内容。
 */
@Singleton
class PulseNotificationPoster @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pulsePreferences: PulsePreferences,
) {

    /** 取走并投递待发文案；没有待发内容时直接返回 false（这是常态，不是错误）。 */
    suspend fun postPending(): Boolean {
        val message = runCatching { pulsePreferences.pendingMessage.first() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        post(message)
        pulsePreferences.setPendingMessage(null)
        return true
    }

    fun post(message: String) {
        if (!canPostNotifications()) {
            // 没有权限仍未消费文案：留着，等用户下次授予权限后还有机会看到。
            AppLogger.warn(LogTags.Config, "pulse_notification_skipped", "reason" to "post_notifications_denied")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
        AppLogger.info(LogTags.Config, "pulse_notification_posted", "messageLength" to message.length)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Aura 关心", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildNotification(message: String): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Aura")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "aura_pulse"
        // 固定 id：新的关心覆盖旧的，不在通知栏堆积过期情绪关怀
        const val NOTIFICATION_ID = 0x9A17
    }
}
