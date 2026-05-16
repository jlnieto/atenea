package com.atenea.android

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.coreconsole.CoreConsoleApp
import com.atenea.android.coreconsole.AteneaOperatorTheme
import com.atenea.android.secure.AteneaSessionStore
import com.atenea.android.voiceruntime.AteneaDiagnostics

class MainActivity : ComponentActivity() {
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

        setContent {
            AteneaOperatorTheme {
                CoreConsoleApp(
                    apiClient = apiClient,
                    sessionStore = sessionStore,
                    apiBaseUrl = BuildConfig.ATENEA_API_BASE_URL,
                    updateManifestUrl = BuildConfig.ATENEA_ANDROID_UPDATE_MANIFEST_URL,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    currentVersionName = BuildConfig.VERSION_NAME
                )
            }
        }
    }
}
