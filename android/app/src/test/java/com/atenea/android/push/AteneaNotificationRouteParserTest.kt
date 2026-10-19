package com.atenea.android.push

import com.atenea.android.coreconsole.AteneaNotificationRoute
import com.atenea.android.coreconsole.AteneaNotificationDestination
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

    @Test fun `operations alert opens its host and rejects foreign data`() {
        val link = "atenea://operations/7"
        val data = mapOf("schemaVersion" to "atenea-operations-notification-v1",
            "eventType" to "OPERATIONS_ALERT", "type" to "OPERATIONS_DEGRADATION",
            "notificationEventId" to eventId, "hostId" to "7",
            "deepLinkKind" to "OPERATIONS_HOST", "deepLink" to link)
        assertEquals(AteneaNotificationRoute(null, eventId, AteneaNotificationDestination.OPERATIONS, 7),
            AteneaNotificationRouteParser.parse(link, data))
        assertNull(AteneaNotificationRouteParser.parse(link, data + ("hostId" to "8")))
        assertNull(AteneaNotificationRouteParser.parse(link, data + ("command" to "anything")))
        assertNull(AteneaNotificationRouteParser.parse("atenea://operations/7?x=1", emptyMap()))
    }

    @Test fun `legacy session push now opens the conversation`() {
        val link = "atenea://work-sessions/12/conversation"
        val data = mapOf("schemaVersion" to "atenea-legacy-session-notification-v1",
            "eventType" to "CLOSE_BLOCKED", "type" to "CLOSE_BLOCKED",
            "notificationEventId" to eventId, "sessionId" to "12",
            "deepLinkKind" to "WORK_SESSION_CONVERSATION", "deepLink" to link)
        assertEquals(AteneaNotificationRoute(12, eventId), AteneaNotificationRouteParser.parse(link, data))
        assertNull(AteneaNotificationRouteParser.parse(link, data + ("sessionId" to "13")))
    }

    @Test fun `product ready push opens global news without inventing a session`() {
        val data=mapOf("schemaVersion" to "atenea-product-update-data-v1","eventType" to "PRODUCT_UPDATE",
            "type" to "PRODUCT_UPDATE","templateVersion" to "product-update-safe-v1","notificationEventId" to eventId,
            "deepLinkKind" to "ATENEA_UPDATES","deepLink" to "atenea://updates")
        assertEquals(AteneaNotificationRoute(null,eventId,AteneaNotificationDestination.UPDATES),
            AteneaNotificationRouteParser.parse("atenea://updates",data))
        assertEquals(AteneaNotificationRoute(null,eventId,AteneaNotificationDestination.UPDATES),
            AteneaNotificationRouteParser.parseIntent("atenea://updates",data+mapOf("google.message_id" to "transport",
                "gcm.n.title" to "Atenea","collapse_key" to "com.atenea.android","from" to "synthetic")))
        assertNull(AteneaNotificationRouteParser.parseIntent("atenea://updates",data+("command" to "foreign")))
        for(invalid in listOf(data+("command" to "foreign"),data+("sessionId" to "21"),data+("notificationEventId" to "foreign"),
            data+("deepLinkKind" to "WORK_SESSION_CONVERSATION"))) {
            assertNull(AteneaNotificationRouteParser.parse("atenea://updates",invalid))
        }
        assertNull(AteneaNotificationRouteParser.parse("atenea://updates?execute=anything",emptyMap()))
    }

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
