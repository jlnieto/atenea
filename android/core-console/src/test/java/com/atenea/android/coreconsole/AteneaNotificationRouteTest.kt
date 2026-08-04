package com.atenea.android.coreconsole

import kotlin.test.Test
import kotlin.test.assertEquals

class AteneaNotificationRouteTest {
    @Test
    fun mapsOnlyTheValidatedSessionAndRequestIdentityToConversationNavigation() {
        val route = AteneaNotificationRoute(
            sessionId = 17,
            requestKey = "00000000-0000-4000-8000-000000000017"
        )

        assertEquals(
            AteneaConversationNavigation(
                sessionId = 17,
                requestKey = "00000000-0000-4000-8000-000000000017"
            ),
            route.toConversationNavigation()
        )
    }
}
