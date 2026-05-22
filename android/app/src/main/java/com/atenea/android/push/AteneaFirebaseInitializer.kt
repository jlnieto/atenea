package com.atenea.android.push

import android.content.Context
import com.atenea.android.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

internal object AteneaFirebaseInitializer {
    fun isConfigured(): Boolean =
        BuildConfig.ATENEA_FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.ATENEA_FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.ATENEA_FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.ATENEA_FIREBASE_GCM_SENDER_ID.isNotBlank()

    fun initialize(context: Context): Boolean {
        if (!isConfigured()) {
            return false
        }
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            return true
        }
        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.ATENEA_FIREBASE_API_KEY)
            .setProjectId(BuildConfig.ATENEA_FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.ATENEA_FIREBASE_APP_ID)
            .setGcmSenderId(BuildConfig.ATENEA_FIREBASE_GCM_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(context, options)
        return true
    }
}
