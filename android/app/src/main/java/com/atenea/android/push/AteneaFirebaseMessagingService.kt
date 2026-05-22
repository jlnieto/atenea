package com.atenea.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.atenea.android.BuildConfig
import com.atenea.android.MainActivity
import com.atenea.android.R
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.secure.AteneaSessionStore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AteneaFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AteneaFirebaseInitializer.initialize(applicationContext)
        ensureNotificationChannel()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (token.isBlank()) {
            return
        }
        val sessionStore = AteneaSessionStore(applicationContext)
        if (sessionStore.load() == null) {
            AteneaPushTokenStore(applicationContext).save(token)
            return
        }
        val apiClient = AteneaApiClient(
            baseUrl = BuildConfig.ATENEA_API_BASE_URL,
            accessTokenProvider = { sessionStore.load()?.accessToken },
            refreshTokenProvider = { sessionStore.load()?.refreshToken },
            sessionUpdater = { sessionStore.save(it) }
        )
        scope.launch {
            runCatching {
                apiClient.registerPushToken(
                    token = token,
                    platform = "android",
                    appVersion = BuildConfig.VERSION_NAME
                )
                AteneaPushTokenStore(applicationContext).save(token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        ensureNotificationChannel()
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "Atenea"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: ""
        showNotification(title, body, message.data)
    }

    private fun showNotification(title: String, body: String, data: Map<String, String>) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data.forEach { (key, value) -> putExtra(key, value) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
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
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Atenea", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        private const val CHANNEL_ID = "default"
    }
}
