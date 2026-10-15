package com.atenea.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class ReleasePasskeyTest {
    private val id = UUID.randomUUID()
    private val requestId = UUID.randomUUID()
    private val origin = "android:apk-key-hash:" + "A".repeat(43)
    private val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
    private fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun options(rp: String = "atenea.yudri.es") = JSONObject().put("requestId", requestId.toString())
        .put("challenge", challenge).put("timeoutMillis", 300_000).put("relyingPartyId", rp)
        .put("userVerification", "required").put("credentials", JSONArray().put(JSONObject().put("type", "public-key")
            .put("id", encoded("credential")).put("transports", JSONArray().put("internal"))))
    private fun assertion(client: JSONObject = JSONObject().put("type", "webauthn.get").put("challenge", challenge)
        .put("origin", origin).put("crossOrigin", false)) = JSONObject().put("id", encoded("credential"))
        .put("rawId", encoded("credential")).put("type", "public-key").put("response", JSONObject()
            .put("clientDataJSON", encoded(client.toString())).put("authenticatorData", encoded("authenticator"))
            .put("signature", encoded("signature")).put("userHandle", encoded("operator")))

    @Test fun providerReceivesOnlyTheServerChallengeRpAllowedCredentialsAndRequiredVerification() {
        val value = parseReleasePasskeyChallenge(options(), "atenea.yudri.es")
        val request = JSONObject(value.requestJson)
        assertEquals(setOf("challenge", "timeout", "rpId", "allowCredentials", "userVerification"), request.keys().asSequence().toSet())
        assertEquals(challenge, request.getString("challenge")); assertEquals("required", request.getString("userVerification"))
        assertEquals(encoded("credential"), request.getJSONArray("allowCredentials").getJSONObject(0).getString("id"))
        assertFalse(value.toString().contains(challenge))
    }

    @Test fun foreignRpWeakVerificationEmptyCredentialsAndMalformedChallengesAreRejected() {
        for ((key, value) in listOf("relyingPartyId" to "foreign.test", "userVerification" to "preferred",
            "challenge" to "malformed", "timeoutMillis" to 0, "timeoutMillis" to "300000",
            "credentials" to JSONArray())) {
            assertFails { parseReleasePasskeyChallenge(options().put(key, value), "atenea.yudri.es") }
        }
    }

    @Test fun assertionCannotSubstituteChallengeOriginTypeOrClaimedVerification() {
        val value = parseReleasePasskeyChallenge(options(), "atenea.yudri.es")
        for ((key, replacement) in listOf("challenge" to encoded("foreign"), "origin" to "https://foreign.test",
            "type" to "webauthn.create", "crossOrigin" to true, "crossOrigin" to "false")) {
            val client = JSONObject().put("type", "webauthn.get").put("challenge", challenge)
                .put("origin", origin).put("crossOrigin", false).put(key, replacement)
            assertFails { releasePasskeyAssertion(value, origin, assertion(client).toString()) }
        }
        assertFails { releasePasskeyAssertion(value, origin, assertion().put("rawId", encoded("other")).toString()) }
        assertFails { releasePasskeyHeaders(origin + "\r\nInjected: true") }
    }

    @Test fun nullableUserHandleIsForwardedForServerPolicyNeverFabricatedFromAccountData() {
        val assertion = assertion().apply { getJSONObject("response").remove("userHandle") }
        val result = releasePasskeyAssertion(parseReleasePasskeyChallenge(options(), "atenea.yudri.es"), origin, assertion.toString())
        assertTrue(result.has("userHandle")); assertTrue(result.isNull("userHandle"))
    }

    @Test fun authenticatedOptionsAndProofIssueOnlyAGrantWithoutConfirmingOrSendingClientAuthority() {
        val server = MockWebServer(); server.start()
        try {
            val api = AteneaApiClient(server.url("/").toString(), { "synthetic-access" })
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(options(server.url("/").host).toString()))
            val value = runBlocking { api.beginReleasePasskey(id, origin) }
            val start = server.takeRequest()
            assertEquals("/api/mobile/delivery/$id/authorize/passkey/options", start.path)
            assertEquals("{}", start.body.readUtf8()); assertEquals("Bearer synthetic-access", start.getHeader("Authorization"))
            assertEquals(origin, start.getHeader("X-Atenea-Android-Origin"))
            val grant = UUID.randomUUID()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"authorization\":\"$grant\"}"))
            assertEquals(grant, runBlocking { api.authorizeReleasePasskey(id, origin, value, assertion().toString()) })
            val finish = server.takeRequest(); val body = JSONObject(finish.body.readUtf8())
            assertEquals("/api/mobile/delivery/$id/authorize/passkey", finish.path)
            assertEquals("Bearer synthetic-access", finish.getHeader("Authorization")); assertEquals(origin, finish.getHeader("X-Atenea-Android-Origin"))
            assertEquals(setOf("requestId", "credentialId", "userHandle", "clientDataJson", "authenticatorData", "signature"), body.keys().asSequence().toSet())
            assertEquals(requestId.toString(), body.getString("requestId")); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun authenticatorFallbackKeepsItsExistingClosedAuthenticatedContract() {
        val server = MockWebServer(); server.start()
        try {
            val api = AteneaApiClient(server.url("/").toString(), { "synthetic-access" })
            val grant = UUID.randomUUID()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"authorization\":\"$grant\"}"))
            assertEquals(grant, runBlocking { api.authorizeRelease(id, "123456") })
            val request = server.takeRequest()
            assertEquals("/api/mobile/delivery/$id/authorize", request.path)
            assertEquals(setOf("totp"), JSONObject(request.body.readUtf8()).keys().asSequence().toSet())
            assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun oneTimeSetupRequiresProofAndTotpAndCannotChooseATargetOrPublish() {
        val server = MockWebServer(); server.start()
        try {
            val api = AteneaApiClient(server.url("/").toString(), { "synthetic-access" })
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(options(server.url("/").host).toString()))
            val challenge = runBlocking { api.beginReleasePasskeySetup(origin) }
            assertEquals("/api/mobile/release-passkeys/setup/options", server.takeRequest().path)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"configured\":true}"))
            runBlocking { api.configureReleasePasskey(origin, challenge, assertion().toString(), "123456") }
            val request = server.takeRequest(); val body = JSONObject(request.body.readUtf8())
            assertEquals("/api/mobile/release-passkeys/setup", request.path)
            assertEquals(setOf("totp", "requestId", "credentialId", "userHandle", "clientDataJson", "authenticatorData", "signature"), body.keys().asSequence().toSet())
            assertEquals("123456", body.getString("totp")); assertEquals(2, server.requestCount)
            assertFails { runBlocking { api.configureReleasePasskey(origin, challenge, assertion().toString(), "invalid") } }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun setupStatusIsAnAuthenticatedReadWithoutChallengeGrantOrPublication() {
        val server = MockWebServer(); server.start()
        try {
            val api = AteneaApiClient(server.url("/").toString(), { "synthetic-access" })
            val status = JSONObject().put("enabled", true).put("hasRegisteredPasskey", true)
                .put("configured", false).put("setupRequired", true)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(status.toString()))
            assertEquals(ReleasePasskeyStatus(true, true, false, true), runBlocking { api.fetchReleasePasskeyStatus(origin) })
            val request = server.takeRequest()
            assertEquals("GET", request.method); assertEquals("/api/mobile/release-passkeys", request.path)
            assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
            assertEquals(origin, request.getHeader("X-Atenea-Android-Origin")); assertEquals(0, request.bodySize)
            assertEquals(1, server.requestCount)
            assertFails { parseReleasePasskeyStatus(status.put("configured", true)) }
            assertFails { parseReleasePasskeyStatus(status.put("configured", false).put("enabled", "true")) }
        } finally { server.shutdown() }
    }
}
