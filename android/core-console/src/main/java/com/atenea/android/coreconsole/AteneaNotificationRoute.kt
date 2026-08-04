package com.atenea.android.coreconsole

data class AteneaNotificationRoute(
    val sessionId: Long,
    val requestKey: String
)

internal data class AteneaConversationNavigation(
    val sessionId: Long,
    val requestKey: String
)

internal fun AteneaNotificationRoute.toConversationNavigation() = AteneaConversationNavigation(
    sessionId = sessionId,
    requestKey = requestKey
)
