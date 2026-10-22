package com.atenea.android

import android.graphics.Color
import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.coreconsole.CoreConsoleApp
import com.atenea.android.coreconsole.AteneaNotificationRoute
import com.atenea.android.coreconsole.AteneaNotificationState
import com.atenea.android.coreconsole.AteneaOperatorTheme
import com.atenea.android.push.AteneaPushRegistrar
import com.atenea.android.push.AteneaForegroundNotificationObserver
import com.atenea.android.push.AteneaNotificationRouteParser
import com.atenea.android.secure.AteneaSessionStore
import com.atenea.android.voiceruntime.AteneaDiagnostics

class MainActivity : ComponentActivity() {
    private val notifications = AteneaNotificationState()
    private val foregroundRouteConsumer: (AteneaNotificationRoute) -> Unit = { route ->
        runOnUiThread { notifications.onReceived(route) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AteneaDiagnostics.installCrashHandler(
            context = applicationContext,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE
        )
        AteneaDiagnostics.info("app", "main_activity_created")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val sessionStore = AteneaSessionStore(applicationContext)
        val apiClient = AteneaApiClient(
            baseUrl = BuildConfig.ATENEA_API_BASE_URL,
            accessTokenProvider = { sessionStore.load()?.accessToken },
            refreshTokenProvider = { sessionStore.load()?.refreshToken },
            operatorRoleProvider = { sessionStore.load()?.operator?.codexOperationsRole },
            sessionUpdater = { sessionStore.save(it) }
        )
        val pushRegistrar = AteneaPushRegistrar(applicationContext, apiClient)
        acceptNotificationIntent(intent)

        setContent {
            AteneaOperatorTheme {
                CoreConsoleApp(
                    apiClient = apiClient,
                    sessionStore = sessionStore,
                    apiBaseUrl = BuildConfig.ATENEA_API_BASE_URL,
                    updateManifestUrl = BuildConfig.ATENEA_ANDROID_UPDATE_MANIFEST_URL,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    pushRegistration = pushRegistrar,
                    requestedConversation = notifications.openedRoute,
                    receivedNotificationKey = notifications.receivedKey
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AteneaForegroundNotificationObserver.attach(foregroundRouteConsumer)
    }

    override fun onStop() {
        AteneaForegroundNotificationObserver.detach(foregroundRouteConsumer)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNotificationIntent(intent)
    }

    private fun acceptNotificationIntent(intent: Intent?) {
        intent ?: return
        val values = buildMap {
            intent.extras?.keySet()?.forEach { key ->
                intent.extras?.getString(key)?.let { value -> put(key, value) }
            }
        }
        val deepLink = intent.dataString ?: values["deepLink"]
        AteneaNotificationRouteParser.parseIntent(deepLink, values)?.let { route ->
            notifications.onOpened(route)
        }
    }
}
