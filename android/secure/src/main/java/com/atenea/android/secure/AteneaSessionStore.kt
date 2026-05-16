package com.atenea.android.secure

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.atenea.android.api.MobileAuthSession
import com.atenea.android.api.OperatorProfile

class AteneaSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            "atenea_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(session: MobileAuthSession) {
        preferences.edit()
            .putString("accessToken", session.accessToken)
            .putString("accessTokenExpiresAt", session.accessTokenExpiresAt)
            .putString("refreshToken", session.refreshToken)
            .putString("refreshTokenExpiresAt", session.refreshTokenExpiresAt)
            .putLong("operatorId", session.operator.id)
            .putString("operatorEmail", session.operator.email)
            .putString("operatorDisplayName", session.operator.displayName)
            .apply()
    }

    fun load(): MobileAuthSession? {
        val accessToken = preferences.getString("accessToken", null) ?: return null
        val refreshToken = preferences.getString("refreshToken", null) ?: return null
        return MobileAuthSession(
            accessToken = accessToken,
            accessTokenExpiresAt = preferences.getString("accessTokenExpiresAt", null).orEmpty(),
            refreshToken = refreshToken,
            refreshTokenExpiresAt = preferences.getString("refreshTokenExpiresAt", null).orEmpty(),
            operator = OperatorProfile(
                id = preferences.getLong("operatorId", 0),
                email = preferences.getString("operatorEmail", null).orEmpty(),
                displayName = preferences.getString("operatorDisplayName", null).orEmpty()
            )
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}
