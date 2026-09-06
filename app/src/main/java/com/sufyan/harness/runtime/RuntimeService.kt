package com.sufyan.harness.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sufyan.harness.HarnessApp
import com.sufyan.harness.MainActivity
import com.sufyan.harness.R

/**
 * Foreground service that keeps long-running work (dev servers, installs,
 * long agent runs) alive when the app is backgrounded. State is always visible
 * in the notification and it exposes an explicit Stop action — no hidden work.
 */
class RuntimeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Background runtime active"
        startForeground(NOTIFICATION_ID, buildNotification(label))
        return START_STICKY
    }

    private fun buildNotification(label: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, HarnessApp.CHANNEL_RUNTIME)
            .setContentTitle("Sufyan Harness")
            .setContentText(label)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private var eventId = 100
        const val ACTION_STOP = "com.sufyan.harness.STOP_RUNTIME"
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String) {
            val intent = Intent(context, RuntimeService::class.java).putExtra(EXTRA_LABEL, label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RuntimeService::class.java).setAction(ACTION_STOP))
        }

        /**
         * §51 — a one-shot notification for a finished task. Silently does nothing when the user has
         * not granted the notification permission; it never pretends to have notified.
         */
        fun notifyCompleted(context: Context, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val open = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, HarnessApp.CHANNEL_EVENTS)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(context).notify(eventId++, notification)
            }
        }
    }
}
