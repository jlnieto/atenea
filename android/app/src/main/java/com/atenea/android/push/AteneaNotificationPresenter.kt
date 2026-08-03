package com.atenea.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.atenea.android.MainActivity
import com.atenea.android.R

internal class AteneaNotificationPresenter(private val context: Context) {
    fun show(title: String, body: String, payload: Map<String, String>) {
        ensureChannel()
        val route = AteneaNotificationRouteParser.parse(payload["deepLink"], payload)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (route != null) {
                data = Uri.parse(payload.getValue("deepLink"))
                SAFE_PAYLOAD_KEYS.forEach { key ->
                    payload[key]?.let { value -> putExtra(key, value) }
                }
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            route?.requestKey?.hashCode() ?: System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.rgb(37, 99, 235))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(route?.requestKey?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
        }
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Atenea", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "default"
        val SAFE_PAYLOAD_KEYS = setOf(
            "schemaVersion",
            "eventType",
            "category",
            "type",
            "templateVersion",
            "notificationEventId",
            "runId",
            "sessionId",
            "deepLinkKind",
            "deepLink"
        )
    }
}
