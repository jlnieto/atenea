package com.atenea.android.push

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.atenea.android.BuildConfig
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.coreconsole.AteneaPushRegistration
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AteneaPushRegistrar(
    context: Context,
    private val apiClient: AteneaApiClient
) : AteneaPushRegistration {
    private val appContext = context.applicationContext
    private val tokenStore = AteneaPushTokenStore(appContext)

    override fun isConfigured(): Boolean = AteneaFirebaseInitializer.isConfigured()

    override suspend fun registerForCurrentSession() = withContext(Dispatchers.IO) {
        if (!AteneaFirebaseInitializer.initialize(appContext)) {
            return@withContext
        }
        val token = Tasks.await(FirebaseMessaging.getInstance().token)
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext
        apiClient.registerPushToken(
            token = token,
            platform = "android",
            deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID),
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() },
            appVersion = BuildConfig.VERSION_NAME
        )
        tokenStore.save(token)
    }

    override suspend fun unregisterCurrentToken() = withContext(Dispatchers.IO) {
        tokenStore.load()?.let { token ->
            runCatching { apiClient.unregisterPushToken(token) }
        }
        tokenStore.clear()
    }
}
