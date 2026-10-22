package com.atenea.android.coreconsole

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AteneaNotificationStateTest {
    @Test fun `receiving successive events never creates navigation`() {
        val state = AteneaNotificationState()
        repeat(2) { index ->
            state.onReceived(AteneaNotificationRoute(29, "event-$index"))
            assertEquals("event-$index", state.receivedKey)
            assertNull(state.openedRoute)
        }
    }

    @Test fun `receipt does not replace the route explicitly opened before it`() {
        val state = AteneaNotificationState()
        val opened = AteneaNotificationRoute(29, "opened")
        state.onOpened(opened)
        state.onReceived(AteneaNotificationRoute(30, "received"))
        assertEquals(opened, state.openedRoute)
        assertEquals("received", state.receivedKey)
    }

    @Test fun `explicit opening selects only its requested route`() {
        val state = AteneaNotificationState()
        val opened = AteneaNotificationRoute(30, "opened")
        state.onOpened(opened)
        assertEquals(opened, state.openedRoute)
        assertNull(state.receivedKey)
    }

    @Test fun `new UI owner does not inherit another owners notification intent`() {
        val previous = AteneaNotificationState()
        previous.onOpened(AteneaNotificationRoute(29, "opened"))
        previous.onReceived(AteneaNotificationRoute(29, "received"))
        val next = AteneaNotificationState()
        assertNull(next.openedRoute)
        assertNull(next.receivedKey)
    }
}
