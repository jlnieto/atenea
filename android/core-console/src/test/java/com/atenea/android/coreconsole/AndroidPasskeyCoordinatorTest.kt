package com.atenea.android.coreconsole

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidPasskeyCoordinatorTest {
    @Test
    fun `passkey is explicit on API 28 plus and unavailable on API 26 27`() {
        assertEquals(PasskeyAvailability.UNSUPPORTED_ANDROID_VERSION, passkeyAvailability(26))
        assertEquals(PasskeyAvailability.UNSUPPORTED_ANDROID_VERSION, passkeyAvailability(27))
        assertEquals(PasskeyAvailability.AVAILABLE, passkeyAvailability(28))
        assertEquals(PasskeyAvailability.AVAILABLE, passkeyAvailability(35))
    }
}
