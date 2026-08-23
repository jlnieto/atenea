package com.atenea.android.push

import com.atenea.android.coreconsole.AteneaNotificationRoute
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AteneaNotificationRouteParserTest {
    private val eventId = "adad1f5a-18b8-43d2-8e9c-c5f92037f693"
    private val validData = mapOf(
        "schemaVersion" to "atenea-notification-data-v1",
        "eventType" to "AGENT_RUN_STATE",
        "category" to "RUN_COMPLETED",
        "type" to "RUN_COMPLETED",
        "templateVersion" to "agent-run-safe-v1",
        "notificationEventId" to eventId,
        "runId" to "34",
        "sessionId" to "12",
        "deepLinkKind" to "WORK_SESSION_CONVERSATION",
        "deepLink" to "atenea://work-sessions/12/conversation"
    )

    @AfterTest
    fun detachConsumer() {
        AteneaForegroundNotificationRouter.clearForTest()
    }

    @Test
    fun `accepts the exact versioned payload`() {
        assertEquals(
            AteneaNotificationRoute(12, eventId),
            AteneaNotificationRouteParser.parse(validData["deepLink"], validData)
        )
    }

    @Test
    fun `accepts a direct exact deep link without push data`() {
        assertEquals(
            AteneaNotificationRoute(12, "deep-link:12"),
            AteneaNotificationRouteParser.parse("atenea://work-sessions/12/conversation", emptyMap())
        )
    }

    @Test
    fun `rejects malformed mismatched or unknown payloads`() {
        val invalidValues = listOf(
            validData + ("sessionId" to "13"),
            validData + ("schemaVersion" to "future-version"),
            validData + ("category" to "UNKNOWN"),
            validData + ("type" to "RUN_FAILED"),
            validData + ("deepLink" to "atenea://work-sessions/13/conversation"),
            validData + ("runId" to "0"),
            validData + ("notificationEventId" to "not-a-uuid")
        )
        invalidValues.forEach { data ->
            assertNull(AteneaNotificationRouteParser.parse(data["deepLink"], data))
        }
        listOf(
            "https://work-sessions/12/conversation",
            "atenea://other/12/conversation",
            "atenea://work-sessions/12/other",
            "atenea://work-sessions/12/conversation?next=unsafe",
            "atenea://work-sessions/-1/conversation"
        ).forEach { deepLink ->
            assertNull(AteneaNotificationRouteParser.parse(deepLink, emptyMap()))
        }
    }

    @Test
    fun `foreground delivery consumes once only while attached`() {
        val delivered = mutableListOf<AteneaNotificationRoute>()
        val consumer: (AteneaNotificationRoute) -> Unit = { delivered += it }
        val route = AteneaNotificationRoute(12, eventId)

        assertFalse(AteneaForegroundNotificationRouter.deliver(route))
        AteneaForegroundNotificationRouter.attach(consumer)
        assertTrue(AteneaForegroundNotificationRouter.deliver(route))
        assertEquals(listOf(route), delivered)
        AteneaForegroundNotificationRouter.detach(consumer)
        assertFalse(AteneaForegroundNotificationRouter.deliver(route))
    }
}
