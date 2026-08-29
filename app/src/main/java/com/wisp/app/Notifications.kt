package com.wisp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Notification channels, the persistent service notification and call alerts. */
object Notifications {

    const val CHANNEL_SERVICE = "wisp_service"
    const val CHANNEL_INCOMING = "wisp_incoming"
    const val CHANNEL_ONGOING = "wisp_ongoing"

    const val ID_SERVICE = 1
    const val ID_INCOMING = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = context.getString(R.string.channel_service_desc)
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING,
                context.getString(R.string.channel_incoming),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_incoming_desc)
                // The service drives the ringtone and vibration itself so it can
                // loop for the whole ring and respect silent/vibrate mode; the
                // channel staying quiet avoids ringing twice.
                enableVibration(false)
                setSound(null, null)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                context.getString(R.string.channel_ongoing),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_ongoing_desc)
                setShowBadge(false)
                setSound(null, null)
            }
        )
    }

    /**
     * Android 14 gates full-screen intents behind a per-app grant that is
     * separate from the manifest permission; without it the incoming-call
     * notification degrades to a heads-up banner.
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    fun idle(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_wisp)
            .setContentTitle(context.getString(R.string.notif_idle_title))
            .setContentText(context.getString(R.string.notif_idle_text))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun incoming(context: Context, peerName: String, peerCode: String): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            10,
            CallActivity.incomingIntent(context, peerCode, peerName),
            pendingFlags()
        )
        return NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_wisp)
            .setContentTitle(peerName)
            .setContentText(context.getString(R.string.notif_incoming_text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(
                R.drawable.ic_call_end,
                context.getString(R.string.decline),
                serviceAction(context, CallService.ACTION_DECLINE, 11)
            )
            // Answering goes through the activity rather than straight to the
            // service: Android 14 only grants camera/microphone to a foreground
            // service while the app is visible, and the call screen makes it so.
            .addAction(
                R.drawable.ic_answer,
                context.getString(R.string.answer),
                PendingIntent.getActivity(
                    context,
                    12,
                    CallActivity.answerIntent(context, peerCode, peerName),
                    pendingFlags()
                )
            )
            .build()
    }

    fun ongoing(context: Context, peerName: String, statusText: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_wisp)
            .setContentTitle(peerName)
            .setContentText(statusText)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    13,
                    CallActivity.ongoingIntent(context),
                    pendingFlags()
                )
            )
            .addAction(
                R.drawable.ic_call_end,
                context.getString(R.string.hang_up),
                serviceAction(context, CallService.ACTION_HANGUP, 14)
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun cancelIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_INCOMING)
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingFlags()
        )

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallService::class.java).setAction(action)
        return PendingIntent.getForegroundService(context, requestCode, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
