package com.atenea.android.coreconsole

interface AteneaPushRegistration {
    fun isConfigured(): Boolean
    suspend fun registerForCurrentSession()
    suspend fun unregisterCurrentToken()
}
