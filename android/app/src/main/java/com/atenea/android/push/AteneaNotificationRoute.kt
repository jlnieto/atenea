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

    // Firebase adds transport/analytics extras to background notification intents.
    // They are not application payload fields or navigation authority.
    fun parseIntent(deepLink: String?, extras: Map<String,String>): AteneaNotificationRoute? = parse(deepLink,
        extras.filterKeys { !it.startsWith("google.") && !it.startsWith("gcm.") && it !in setOf("from","collapse_key") })

    fun parse(deepLink: String?, data: Map<String, String>): AteneaNotificationRoute? {
        val operationsId = parseOperationsUri(deepLink)
        if (operationsId != null) {
            if (data.isEmpty()) return AteneaNotificationRoute(null, "deep-link:operations:$operationsId",
                com.atenea.android.coreconsole.AteneaNotificationDestination.OPERATIONS, operationsId)
            if (data.keys != setOf("schemaVersion", "eventType", "type", "notificationEventId", "hostId", "deepLinkKind", "deepLink") ||
                data["schemaVersion"] != "atenea-operations-notification-v1" ||
                data["eventType"] != "OPERATIONS_ALERT" ||
                data["type"] !in setOf("OPERATIONS_DEGRADATION", "OPERATIONS_VERIFICATION_FAILURE") ||
                data["deepLinkKind"] != "OPERATIONS_HOST" || data["deepLink"] != deepLink ||
                data["hostId"]?.toLongOrNull() != operationsId) return null
            val id = runCatching { UUID.fromString(data["notificationEventId"]) }.getOrNull() ?: return null
            if (id.toString() != data["notificationEventId"]) return null
            return AteneaNotificationRoute(null, id.toString(),
                com.atenea.android.coreconsole.AteneaNotificationDestination.OPERATIONS, operationsId)
        }
        if (deepLink=="atenea://updates") {
            if (data.isEmpty()) return AteneaNotificationRoute(null,"deep-link:updates",com.atenea.android.coreconsole.AteneaNotificationDestination.UPDATES)
            if (data.keys!=setOf("schemaVersion","eventType","type","templateVersion","notificationEventId","deepLinkKind","deepLink")
                || data["schemaVersion"]!="atenea-product-update-data-v1" || data["eventType"]!="PRODUCT_UPDATE"
                || data["type"]!="PRODUCT_UPDATE" || data["templateVersion"]!="product-update-safe-v1"
                || data["deepLinkKind"]!="ATENEA_UPDATES" || data["deepLink"]!=deepLink) return null
            val id=runCatching { UUID.fromString(data["notificationEventId"]) }.getOrNull() ?: return null
            if(id.toString()!=data["notificationEventId"])return null
            return AteneaNotificationRoute(null,id.toString(),com.atenea.android.coreconsole.AteneaNotificationDestination.UPDATES)
        }
        val parsedUri = parseUri(deepLink ?: return null) ?: return null
        if (data.isEmpty()) {
            return AteneaNotificationRoute(parsedUri, "deep-link:$parsedUri")
        }
        if (data["schemaVersion"] == "atenea-legacy-session-notification-v1") {
            if (data.keys != setOf("schemaVersion", "eventType", "type", "notificationEventId", "sessionId", "deepLinkKind", "deepLink") ||
                data["eventType"] !in setOf("RUN_SUCCEEDED", "CLOSE_BLOCKED", "PULL_REQUEST_MERGED", "BILLING_READY") ||
                data["type"] != data["eventType"] ||
                data["sessionId"]?.toLongOrNull() != parsedUri ||
                data["deepLinkKind"] != DEEP_LINK_KIND || data["deepLink"] != deepLink) return null
            val id = runCatching { UUID.fromString(data["notificationEventId"]) }.getOrNull() ?: return null
            if (id.toString() != data["notificationEventId"]) return null
            return AteneaNotificationRoute(parsedUri, id.toString())
        }
        if (data["schemaVersion"] != SCHEMA_VERSION ||
            data["eventType"] != EVENT_TYPE ||
            data["deepLinkKind"] != DEEP_LINK_KIND ||
            data["templateVersion"] != "agent-run-safe-v1" ||
            data["category"] !in categories ||
            data["type"] != data["category"] ||
            data["deepLink"] != deepLink ||
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

    private fun parseOperationsUri(value: String?): Long? {
        val uri = runCatching { URI(value ?: return null) }.getOrNull() ?: return null
        if (uri.scheme != "atenea" || uri.host != "operations" || uri.query != null || uri.fragment != null) return null
        val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        return if (parts.size == 1) parts[0].toLongOrNull()?.takeIf { it > 0 } else null
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
