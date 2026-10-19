package com.atenea.android.coreconsole

data class AteneaNotificationRoute(
    val sessionId: Long?,
    val requestKey: String,
    val destination: AteneaNotificationDestination = AteneaNotificationDestination.CONVERSATION,
    val hostId: Long? = null
)

enum class AteneaNotificationDestination { CONVERSATION, OPERATIONS, UPDATES }

internal data class AteneaConversationNavigation(
    val sessionId: Long,
    val requestKey: String
)

internal fun AteneaNotificationRoute.toConversationNavigation(): AteneaConversationNavigation {
    require(destination==AteneaNotificationDestination.CONVERSATION)
    return AteneaConversationNavigation(requireNotNull(sessionId).also { require(it>0) },requestKey)
}
