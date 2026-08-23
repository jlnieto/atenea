package com.atenea.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AteneaPasskeyInventoryClientTest {

    @Test
    fun `inventory is credential centric sanitized and revokes by public record id`() =
        withPasskeyServer { server ->
            val recordId = "00000000-0000-4000-8000-000000000091"
            server.enqueue(passkeyJsonResponse("""
                {
                  "state":"ACTION_REQUIRED",
                  "credentials":[{
                    "recordId":"$recordId",
                    "label":"1Password · 2",
                    "providerCategory":"ONE_PASSWORD",
                    "provenance":"OPERATOR_DECLARED",
                    "backupEligible":true,
                    "backupState":true,
                    "transports":["internal"],
                    "createdAt":"2026-08-15T10:20:00Z",
                    "lastUsedAt":null,
                    "lastVerifiedAt":"2026-08-15T10:21:00Z",
                    "state":"ACTIVE"
                  }],
                  "requiredProviderDomains":["GOOGLE_PASSWORD_MANAGER","ONE_PASSWORD"],
                  "verifiedProviderDomains":["ONE_PASSWORD"],
                  "independentDomainsReady":false,
                  "signallingEnabled":true,
                  "readOnly":false,
                  "nextAction":"Verifica el dominio restante."
                }
            """.trimIndent()))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "access" })

            val inventory = runBlocking {
                val value = client.fetchPasskeyInventory()
                client.revokePasskey(value.credentials.single().recordId)
                value
            }

            assertEquals("1Password · 2", inventory.credentials.single().label)
            assertEquals(PasskeyProviderCategory.ONE_PASSWORD,
                inventory.credentials.single().providerCategory)
            assertFalse(inventory.independentDomainsReady)
            assertFalse(inventory.readOnly)
            assertFalse(inventory.toString().contains("credentialId", ignoreCase = true))
            assertEquals("/api/auth/webauthn/credentials", server.takeRequest().path)
            val revoke = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("DELETE", revoke.method)
            assertEquals("/api/auth/webauthn/credentials/$recordId", revoke.path)
        }

    @Test
    fun `signal snapshot remains redacted in diagnostics`() = withPasskeyServer { server ->
        server.enqueue(passkeyJsonResponse("""
            {
              "relyingPartyId":"atenea.yudri.es",
              "userId":"c3ludGhldGljLXVzZXI",
              "allAcceptedCredentialIds":["c3ludGhldGljLWNyZWRlbnRpYWw"],
              "activeCredentialCount":1,
              "credentialVersion":8
            }
        """.trimIndent()))
        val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "access" })

        val snapshot = runBlocking { client.fetchPasskeySignalSnapshot() }

        assertEquals(1, snapshot.activeCredentialCount)
        assertTrue(snapshot.toString().contains("REDACTED"))
        assertFalse(snapshot.toString().contains("c3ludGhldGljLWNyZWRlbnRpYWw"))
    }

    @Test
    fun `discovery mode is parsed as read only without credential identifiers`() =
        withPasskeyServer { server ->
            server.enqueue(passkeyJsonResponse("""
                {
                  "state":"ACTION_REQUIRED",
                  "credentials":[],
                  "requiredProviderDomains":["GOOGLE_PASSWORD_MANAGER","ONE_PASSWORD"],
                  "verifiedProviderDomains":[],
                  "independentDomainsReady":false,
                  "signallingEnabled":false,
                  "readOnly":true,
                  "nextAction":"Selecciona y verifica una sola passkey activa."
                }
            """.trimIndent()))
            val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "access" })

            val inventory = runBlocking { client.fetchPasskeyInventory() }

            assertTrue(inventory.readOnly)
            assertFalse(inventory.signallingEnabled)
            assertTrue(inventory.credentials.isEmpty())
            assertFalse(inventory.toString().contains("credentialId", ignoreCase = true))
        }
}

private fun passkeyJsonResponse(body: String): MockResponse = MockResponse()
    .setResponseCode(200)
    .setHeader("Content-Type", "application/json")
    .setBody(body)

private inline fun withPasskeyServer(block: (MockWebServer) -> Unit) {
    val server = MockWebServer()
    server.start()
    try {
        block(server)
    } finally {
        server.shutdown()
    }
}
