package com.atenea.android.coreconsole

import android.content.Context
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.SignalAllAcceptedCredentialIdsRequest
import androidx.credentials.SignalUnknownCredentialRequest
import com.atenea.android.api.PasskeySignalSnapshot
import org.json.JSONArray
import org.json.JSONObject

internal enum class PasskeyAvailability {
    AVAILABLE,
    UNSUPPORTED_ANDROID_VERSION
}

internal enum class PasskeySignalAvailability {
    AVAILABLE,
    UNSUPPORTED_ANDROID_VERSION
}

internal fun passkeyAvailability(sdkInt: Int): PasskeyAvailability =
    if (sdkInt >= Build.VERSION_CODES.P) {
        PasskeyAvailability.AVAILABLE
    } else {
        PasskeyAvailability.UNSUPPORTED_ANDROID_VERSION
    }

internal fun passkeySignalAvailability(sdkInt: Int): PasskeySignalAvailability =
    if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        PasskeySignalAvailability.AVAILABLE
    } else {
        PasskeySignalAvailability.UNSUPPORTED_ANDROID_VERSION
    }

internal class AndroidPasskeyCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val manager by lazy { CredentialManager.create(appContext) }

    val availability: PasskeyAvailability
        get() = passkeyAvailability(Build.VERSION.SDK_INT)

    val signalAvailability: PasskeySignalAvailability
        get() = passkeySignalAvailability(Build.VERSION.SDK_INT)

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

    suspend fun signalAllAcceptedCredentials(
        snapshot: PasskeySignalSnapshot,
        expectedRelyingPartyId: String
    ) {
        require(signalAvailability == PasskeySignalAvailability.AVAILABLE) {
            "Signal API no disponible"
        }
        manager.signalCredentialState(
            SignalAllAcceptedCredentialIdsRequest(
                acceptedCredentialSignalJson(snapshot, expectedRelyingPartyId)
            )
        )
    }

    suspend fun signalUnknownCredential(
        relyingPartyId: String,
        credentialId: String,
        expectedRelyingPartyId: String
    ) {
        require(signalAvailability == PasskeySignalAvailability.AVAILABLE) {
            "Signal API no disponible"
        }
        manager.signalCredentialState(
            SignalUnknownCredentialRequest(
                unknownCredentialSignalJson(
                    relyingPartyId,
                    credentialId,
                    expectedRelyingPartyId
                )
            )
        )
    }
}

internal fun acceptedCredentialSignalJson(
    snapshot: PasskeySignalSnapshot,
    expectedRelyingPartyId: String
): String {
    val ids = snapshot.allAcceptedCredentialIds
    require(snapshot.relyingPartyId == expectedRelyingPartyId)
    require(snapshot.activeCredentialCount > 0)
    require(snapshot.activeCredentialCount == ids.size)
    require(ids.distinct().size == ids.size)
    require(snapshot.credentialVersion >= 0)
    require(isCanonicalBase64Url(snapshot.userId))
    require(ids.all(::isCanonicalBase64Url))
    return JSONObject()
        .put("rpId", snapshot.relyingPartyId)
        .put("userId", snapshot.userId)
        .put("allAcceptedCredentialIds", JSONArray(ids))
        .toString()
}

internal fun unknownCredentialSignalJson(
    relyingPartyId: String,
    credentialId: String,
    expectedRelyingPartyId: String
): String {
    require(relyingPartyId == expectedRelyingPartyId)
    require(isCanonicalBase64Url(credentialId))
    return JSONObject()
        .put("rpId", relyingPartyId)
        .put("credentialId", credentialId)
        .toString()
}

private fun isCanonicalBase64Url(value: String): Boolean =
    value.isNotBlank() && value.matches(Regex("^[A-Za-z0-9_-]+$"))
