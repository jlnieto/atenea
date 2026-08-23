package com.atenea.android.coreconsole

import android.content.Context
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential

internal enum class PasskeyAvailability {
    AVAILABLE,
    UNSUPPORTED_ANDROID_VERSION
}

internal fun passkeyAvailability(sdkInt: Int): PasskeyAvailability =
    if (sdkInt >= Build.VERSION_CODES.P) {
        PasskeyAvailability.AVAILABLE
    } else {
        PasskeyAvailability.UNSUPPORTED_ANDROID_VERSION
    }

internal class AndroidPasskeyCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val manager by lazy { CredentialManager.create(appContext) }

    val availability: PasskeyAvailability
        get() = passkeyAvailability(Build.VERSION.SDK_INT)

    suspend fun createCredential(registrationOptionsJson: String): String {
        require(availability == PasskeyAvailability.AVAILABLE) { "Passkey no disponible" }
        val response = manager.createCredential(
            appContext,
            CreatePublicKeyCredentialRequest(registrationOptionsJson)
        ) as? CreatePublicKeyCredentialResponse
            ?: error("Respuesta passkey no compatible")
        return response.registrationResponseJson
    }

    suspend fun getCredential(authenticationOptionsJson: String): String {
        require(availability == PasskeyAvailability.AVAILABLE) { "Passkey no disponible" }
        val response = manager.getCredential(
            appContext,
            GetCredentialRequest(listOf(GetPublicKeyCredentialOption(authenticationOptionsJson)))
        )
        val credential = response.credential as? PublicKeyCredential
            ?: error("Credencial passkey no compatible")
        return credential.authenticationResponseJson
    }
}
