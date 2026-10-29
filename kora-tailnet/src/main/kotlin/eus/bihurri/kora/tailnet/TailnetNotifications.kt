package eus.bihurri.kora.tailnet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

/** Builds the foreground-service notification from the app's [TailnetNotification]. */
internal object TailnetNotifications {
    /** Creates the channel (idempotent) with low importance: it is a status, not an alert. */
    fun ensureChannel(context: Context, config: TailnetNotification) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(config.channelId, config.channelName, NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun build(context: Context, config: TailnetNotification, status: TailnetStatus): Notification {
        val text = config.text(status)
        val stop = PendingIntent.getService(
            context,
            0,
            Intent(context, TailnetService::class.java).setAction(TailnetService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, config.channelId)
            .setSmallIcon(config.smallIcon)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setContentIntent(config.contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, config.smallIcon), config.stopActionLabel, stop).build())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            .build()
    }

    /** Shown only when the service was started without a runtime to describe: it is stopped right away. */
    fun placeholder(context: Context): Notification {
        val config = TailnetNotification(smallIcon = android.R.drawable.stat_sys_warning)
        ensureChannel(context, config)
        return build(context, config, TailnetStatus.Off)
    }
}
