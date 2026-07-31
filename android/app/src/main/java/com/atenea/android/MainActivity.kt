package com.atenea.android

import android.graphics.Color
import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.mutableStateOf
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.coreconsole.CoreConsoleApp
import com.atenea.android.coreconsole.AteneaNotificationRoute
import com.atenea.android.coreconsole.AteneaOperatorTheme
import com.atenea.android.push.AteneaPushRegistrar
import com.atenea.android.push.AteneaForegroundNotificationRouter
import com.atenea.android.push.AteneaNotificationRouteParser
import com.atenea.android.secure.AteneaSessionStore
import com.atenea.android.voiceruntime.AteneaDiagnostics

class MainActivity : ComponentActivity() {
    private val requestedConversation = mutableStateOf<AteneaNotificationRoute?>(null)
    private val foregroundRouteConsumer: (AteneaNotificationRoute) -> Unit = { route ->
        runOnUiThread { requestedConversation.value = route }
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
                    requestedConversation = requestedConversation.value
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AteneaForegroundNotificationRouter.attach(foregroundRouteConsumer)
    }

    override fun onStop() {
        AteneaForegroundNotificationRouter.detach(foregroundRouteConsumer)
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
        AteneaNotificationRouteParser.parse(deepLink, values)?.let { route ->
            requestedConversation.value = route
        }
    }
}
