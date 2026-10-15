package com.atenea.android.api

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

data class ReleasePasskeyStatus(val enabled: Boolean, val hasRegisteredPasskey: Boolean,
    val configured: Boolean, val setupRequired: Boolean)

internal fun parseReleasePasskeyStatus(json: JSONObject): ReleasePasskeyStatus {
    for (key in listOf("enabled", "hasRegisteredPasskey", "configured", "setupRequired")) require(json.opt(key) is Boolean)
    val result=ReleasePasskeyStatus(json.getBoolean("enabled"),json.getBoolean("hasRegisteredPasskey"),
        json.getBoolean("configured"),json.getBoolean("setupRequired"))
    require(!result.configured || (result.enabled && result.hasRegisteredPasskey && !result.setupRequired))
    require(result.setupRequired == (result.enabled && result.hasRegisteredPasskey && !result.configured))
    return result
}

class ReleasePasskeyChallenge internal constructor(
    val requestId: UUID,
    val requestJson: String,
    internal val challenge: String
) {
    override fun toString() = "ReleasePasskeyChallenge(REDACTED)"
}

internal fun releasePasskeyHeaders(origin: String): Map<String, String> {
    require(origin.matches(Regex("android:apk-key-hash:[A-Za-z0-9_-]{43}")))
    return mapOf("X-Atenea-Android-Origin" to origin)
}

internal fun parseReleasePasskeyChallenge(json: JSONObject, expectedRpId: String): ReleasePasskeyChallenge {
    val id = UUID.fromString(json.getString("requestId"))
    require(id.toString() == json.getString("requestId"))
    val challenge = canonicalEncoded(json.getString("challenge"), 32, 32)
    require(json.getString("relyingPartyId") == expectedRpId)
    require(json.getString("userVerification") == "required")
    require(json.opt("timeoutMillis") is Number)
    val timeout = json.getLong("timeoutMillis").also { require(it in 1..300_000) }
    val credentials = json.getJSONArray("credentials")
    require(credentials.length() in 1..100)
    val allow = JSONArray()
    for (index in 0 until credentials.length()) {
        val credential = credentials.getJSONObject(index)
        require(credential.getString("type") == "public-key")
        val descriptor = JSONObject().put("type", "public-key")
            .put("id", canonicalEncoded(credential.getString("id"), 1, 1024))
        val transports = credential.optJSONArray("transports")
        if (transports != null && transports.length() > 0) descriptor.put("transports", transports)
        allow.put(descriptor)
    }
    return ReleasePasskeyChallenge(id, JSONObject().put("challenge", challenge).put("rpId", expectedRpId)
        .put("timeout", timeout).put("userVerification", "required").put("allowCredentials", allow).toString(), challenge)
}

internal fun releasePasskeyAssertion(challenge: ReleasePasskeyChallenge, origin: String, assertionJson: String): JSONObject {
    releasePasskeyHeaders(origin)
    require(assertionJson.length <= 65_536)
    val assertion = JSONObject(assertionJson)
    require(assertion.getString("type") == "public-key")
    val id = canonicalEncoded(assertion.getString("rawId"), 1, 1024)
    require(assertion.getString("id") == id)
    val response = assertion.getJSONObject("response")
    val clientData = canonicalEncoded(response.getString("clientDataJSON"), 1, 8192)
    val client = JSONObject(String(Base64.getUrlDecoder().decode(clientData), Charsets.UTF_8))
    require(client.getString("type") == "webauthn.get" && client.getString("challenge") == challenge.challenge)
    require(client.getString("origin") == origin && (!client.has("crossOrigin") || client.opt("crossOrigin") == false))
    val handle = if (response.isNull("userHandle")) JSONObject.NULL
        else canonicalEncoded(response.getString("userHandle"), 1, 1024)
    return JSONObject().put("requestId", challenge.requestId.toString()).put("credentialId", id)
        .put("userHandle", handle).put("clientDataJson", clientData)
        .put("authenticatorData", canonicalEncoded(response.getString("authenticatorData"), 1, 8192))
        .put("signature", canonicalEncoded(response.getString("signature"), 1, 8192))
}

private fun canonicalEncoded(value: String, min: Int, max: Int): String {
    require(value.length <= (max * 4 / 3 + 4) && value.matches(Regex("[A-Za-z0-9_-]+")))
    val bytes = Base64.getUrlDecoder().decode(value)
    require(bytes.size in min..max && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
    return value
}
