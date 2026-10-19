package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileNotification
import kotlinx.coroutines.launch

@Composable
internal fun NotificationsScreen(
    apiClient: AteneaApiClient,
    onUnreadChanged: (Long) -> Unit,
    onOpen: (MobileNotification) -> Unit
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MobileNotification>>(emptyList()) }
    var nextPage by remember { mutableStateOf<Int?>(null) }
    var unreadCount by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(page: Int = 0) {
        scope.launch {
            loading = true
            error = null
            try {
                val result = apiClient.fetchNotifications(page)
                items = if (page == 0) result.items else items + result.items
                nextPage = result.nextPage
                unreadCount = result.unreadCount
                onUnreadChanged(unreadCount)
            } catch (failure: Exception) {
                error = failure.message ?: "No se pudieron cargar las notificaciones."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(apiClient) { load() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Tus notificaciones", style = MaterialTheme.typography.titleLarge)
        Text("Las alertas siguen aquí aunque las descartes de Android.",
            style = MaterialTheme.typography.bodyMedium)
        AteneaButton(text = "Actualizar", enabled = !loading, onClick = { load() })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading && items.isEmpty()) Text("Cargando…")
        if (!loading && items.isEmpty() && error == null) Text("No hay notificaciones todavía.")
        items.forEach { item ->
            AteneaPanel {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.readAt == null) Text("●", color = MaterialTheme.colorScheme.primary)
                        Text(item.title, style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (item.readAt == null) FontWeight.Bold else FontWeight.Normal)
                    }
                    Text(item.createdAt.replace('T', ' ').take(16), style = MaterialTheme.typography.labelSmall)
                    Text(item.body, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    AteneaButton(
                        text = when (item.destination) {
                            "CONVERSATION" -> "Abrir conversación"
                            "OPERATIONS" -> "Abrir Operaciones"
                            else -> "Ver novedades"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                try {
                                    val read = apiClient.markNotificationRead(item.id)
                                    items = items.map { if (it.id == read.id) read else it }
                                    if (item.readAt == null) unreadCount = (unreadCount - 1).coerceAtLeast(0)
                                    onUnreadChanged(unreadCount)
                                    onOpen(read)
                                } catch (failure: Exception) {
                                    error = failure.message ?: "No se pudo abrir la notificación."
                                }
                            }
                        }
                    )
                }
            }
        }
        nextPage?.let { page ->
            AteneaButton(text = "Ver anteriores", enabled = !loading, onClick = { load(page) })
        }
    }
}
