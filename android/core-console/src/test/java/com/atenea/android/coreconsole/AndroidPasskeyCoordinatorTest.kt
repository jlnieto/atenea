package com.atenea.android.coreconsole

import com.atenea.android.api.PasskeySignalSnapshot
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AndroidPasskeyCoordinatorTest {
    @Test
    fun `passkey is explicit on API 28 plus and unavailable on API 26 27`() {
        assertEquals(PasskeyAvailability.UNSUPPORTED_ANDROID_VERSION, passkeyAvailability(26))
        assertEquals(PasskeyAvailability.UNSUPPORTED_ANDROID_VERSION, passkeyAvailability(27))
        assertEquals(PasskeyAvailability.AVAILABLE, passkeyAvailability(28))
        assertEquals(PasskeyAvailability.AVAILABLE, passkeyAvailability(35))
    }

    @Test
    fun `signal api is feature detected only on Android 15 plus`() {
        assertEquals(
            PasskeySignalAvailability.UNSUPPORTED_ANDROID_VERSION,
            passkeySignalAvailability(34)
        )
        assertEquals(PasskeySignalAvailability.AVAILABLE, passkeySignalAvailability(35))
    }

    @Test
    fun `complete signal requires exact rp unique ids and a complete snapshot`() {
        val snapshot = PasskeySignalSnapshot(
            relyingPartyId = "atenea.yudri.es",
            userId = "c3ludGhldGljLXVzZXI",
            allAcceptedCredentialIds = listOf("c3ludGhldGljLTE", "c3ludGhldGljLTI"),
            activeCredentialCount = 2,
            credentialVersion = 7
        )

        val json = JSONObject(acceptedCredentialSignalJson(snapshot, "atenea.yudri.es"))

        assertEquals("atenea.yudri.es", json.getString("rpId"))
        assertEquals(2, json.getJSONArray("allAcceptedCredentialIds").length())
        assertFalse(snapshot.toString().contains("c3ludGhldGljLTE"))
        assertFailsWith<IllegalArgumentException> {
            acceptedCredentialSignalJson(snapshot.copy(activeCredentialCount = 1), "atenea.yudri.es")
        }
        assertFailsWith<IllegalArgumentException> {
            acceptedCredentialSignalJson(snapshot, "preview.atenea.yudri.es")
        }
        assertFailsWith<IllegalArgumentException> {
            acceptedCredentialSignalJson(
                snapshot.copy(allAcceptedCredentialIds = listOf("same", "same")),
                "atenea.yudri.es"
            )
        }
    }

    @Test
    fun `unknown signal is exact and rejects foreign rp or malformed ids`() {
        val json = JSONObject(unknownCredentialSignalJson(
            relyingPartyId = "atenea.yudri.es",
            credentialId = "c3ludGhldGljLWlk",
            expectedRelyingPartyId = "atenea.yudri.es"
        ))
        assertEquals("c3ludGhldGljLWlk", json.getString("credentialId"))
        assertFailsWith<IllegalArgumentException> {
            unknownCredentialSignalJson(
                "preview.atenea.yudri.es",
                "c3ludGhldGljLWlk",
                "atenea.yudri.es"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            unknownCredentialSignalJson("atenea.yudri.es", "not padded=", "atenea.yudri.es")
        }
    }
}
