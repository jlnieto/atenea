package com.atenea.android.coreconsole

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasskeyDiscoveryPolicyTest {

    @Test
    fun `factor mutations stay hidden until a writable inventory is known`() {
        assertFalse(passkeyMutationsAllowed(null))
        assertFalse(passkeyMutationsAllowed(true))
        assertTrue(passkeyMutationsAllowed(false))
    }
}
