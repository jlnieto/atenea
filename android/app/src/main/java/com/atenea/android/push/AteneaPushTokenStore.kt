package com.atenea.android.push

import android.content.Context

internal class AteneaPushTokenStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("atenea_push", Context.MODE_PRIVATE)

    fun save(token: String) {
        preferences.edit().putString("fcmToken", token).apply()
    }

    fun load(): String? =
        preferences.getString("fcmToken", null)?.takeIf { it.isNotBlank() }

    fun clear() {
        preferences.edit().remove("fcmToken").apply()
    }
}
