package com.atenea.android.push

import com.atenea.android.coreconsole.AteneaNotificationRoute
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

object AteneaNotificationRouteParser {
    private const val SCHEMA_VERSION = "atenea-notification-data-v1"
    private const val EVENT_TYPE = "AGENT_RUN_STATE"
    private const val DEEP_LINK_KIND = "WORK_SESSION_CONVERSATION"
    private val categories = setOf("RUN_COMPLETED", "RUN_FAILED", "ACTION_REQUIRED")

    fun parse(deepLink: String?, data: Map<String, String>): AteneaNotificationRoute? {
        val parsedUri = parseUri(deepLink ?: return null) ?: return null
        if (data.isEmpty()) {
            return AteneaNotificationRoute(parsedUri, "deep-link:$parsedUri")
        }
        if (data["schemaVersion"] != SCHEMA_VERSION ||
            data["eventType"] != EVENT_TYPE ||
            data["deepLinkKind"] != DEEP_LINK_KIND ||
            data["templateVersion"] != "agent-run-safe-v1" ||
            data["category"] !in categories ||
            data["type"] != data["category"] ||
            data["sessionId"]?.toLongOrNull() != parsedUri ||
            data["runId"]?.toLongOrNull()?.let { it > 0 } != true
        ) {
            return null
        }
        val eventId = runCatching { UUID.fromString(data["notificationEventId"]) }.getOrNull()
            ?: return null
        return AteneaNotificationRoute(parsedUri, eventId.toString())
    }

    private fun parseUri(value: String): Long? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "atenea" || uri.host != "work-sessions" || uri.query != null || uri.fragment != null) {
            return null
        }
        val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[1] != "conversation") {
            return null
        }
        return parts[0].toLongOrNull()?.takeIf { it > 0 }
    }
}

object AteneaForegroundNotificationRouter {
    private val consumer = AtomicReference<((AteneaNotificationRoute) -> Unit)?>(null)

    fun attach(routeConsumer: (AteneaNotificationRoute) -> Unit) {
        consumer.set(routeConsumer)
    }

    fun detach(routeConsumer: (AteneaNotificationRoute) -> Unit) {
        consumer.compareAndSet(routeConsumer, null)
    }

    fun deliver(route: AteneaNotificationRoute): Boolean {
        val activeConsumer = consumer.get() ?: return false
        activeConsumer(route)
        return true
    }

    internal fun clearForTest() {
        consumer.set(null)
    }
}
