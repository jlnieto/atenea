package com.atenea.android.coreconsole

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Keeps delivery hints separate from explicit navigation, including for the same WorkSession. */
class AteneaNotificationState {
    var receivedKey by mutableStateOf<String?>(null)
        private set
    var openedRoute by mutableStateOf<AteneaNotificationRoute?>(null)
        private set

    fun onReceived(route: AteneaNotificationRoute) {
        receivedKey = route.requestKey
    }

    fun onOpened(route: AteneaNotificationRoute) {
        openedRoute = route
    }
}
