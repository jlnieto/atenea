package com.atenea.android.push

import com.atenea.android.BuildConfig
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
        AteneaNotificationPresenter(applicationContext).ensureChannel()
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
        val route = AteneaNotificationRouteParser.parse(message.data["deepLink"], message.data)
        if (route != null && AteneaForegroundNotificationRouter.deliver(route)) {
            return
        }
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "Atenea"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: ""
        AteneaNotificationPresenter(applicationContext).show(title, body, message.data)
    }
}
