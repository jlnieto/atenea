package com.atenea.android.api

import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class MobileNotification(
    val id: UUID,
    val category: String,
    val title: String,
    val body: String,
    val destination: String,
    val targetId: Long?,
    val createdAt: String,
    val readAt: String?
)

data class MobileNotificationInbox(
    val items: List<MobileNotification>,
    val unreadCount: Long,
    val nextPage: Int?
)

data class MobileNotificationsReadAllResult(
    val readAt: String,
    val unreadCount: Long
)

internal fun parseMobileNotificationsReadAllResult(json: JSONObject): MobileNotificationsReadAllResult =
    MobileNotificationsReadAllResult(
        readAt = json.getString("readAt").also { Instant.parse(it) },
        unreadCount = json.getLong("unreadCount").also { require(it >= 0) }
    )

internal fun parseMobileNotification(json: JSONObject): MobileNotification {
    val destination = json.getString("destination")
    require(destination in setOf("CONVERSATION", "OPERATIONS", "UPDATES"))
    val targetId = if (json.isNull("targetId")) null else json.getLong("targetId").also { require(it > 0) }
    require((destination == "UPDATES") == (targetId == null))
    return MobileNotification(
        id = UUID.fromString(json.getString("id")),
        category = json.getString("category"),
        title = json.getString("title"),
        body = json.getString("body"),
        destination = destination,
        targetId = targetId,
        createdAt = json.getString("createdAt"),
        readAt = if (json.isNull("readAt")) null else json.getString("readAt")
    )
}

internal fun parseMobileNotificationInbox(json: JSONObject): MobileNotificationInbox {
    require(json.getString("protocolVersion") == "atenea-mobile-notification-inbox/v1")
    val values = json.getJSONArray("items")
    require(values.length() <= 30)
    val items = List(values.length()) { parseMobileNotification(values.getJSONObject(it)) }
    val unread = json.getLong("unreadCount").also { require(it >= 0) }
    val next = if (json.isNull("nextPage")) null else json.getInt("nextPage").also { require(it in 1..99) }
    return MobileNotificationInbox(items, unread, next)
}
