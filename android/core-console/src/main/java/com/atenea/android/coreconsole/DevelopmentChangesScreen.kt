package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileDevelopmentChange

internal fun MobileDevelopmentChange.sessionForReentry(expectedProjectId: Long): Long? =
    activeSessionId?.takeIf { projectId == expectedProjectId && status == "OPEN" && it > 0 }

internal fun orderedDevelopmentChanges(changes: List<MobileDevelopmentChange>): List<MobileDevelopmentChange> =
    changes.filter { it.status == "OPEN" } + changes.filter { it.status != "OPEN" }

@Composable
internal fun DevelopmentChangesScreen(
    apiClient: AteneaApiClient,
    projectId: Long?,
    onOpenConversation: (Long, Long) -> Unit,
    onBackToProjects: () -> Unit
) {
    var refreshRevision by remember { mutableIntStateOf(0) }
    var changes by remember(projectId) { mutableStateOf<List<MobileDevelopmentChange>>(emptyList()) }
    var loading by remember(projectId) { mutableStateOf(false) }
    var loaded by remember(projectId) { mutableStateOf(false) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId, refreshRevision) {
        val id = projectId ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            changes = orderedDevelopmentChanges(apiClient.fetchDevelopmentChanges(id))
            loaded = true
        } catch (loadError: Exception) {
            error = loadError.message ?: "No se pudieron cargar los cambios."
        } finally {
            loading = false
        }
    }

    if (projectId == null) {
        AteneaPanel {
            Text("Selecciona un proyecto para ver sus cambios.")
            AteneaButton(text = "Volver a proyectos", onClick = onBackToProjects)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AteneaSpacing.medium)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cambios · Proyecto $projectId", style = MaterialTheme.typography.titleMedium)
                AteneaTextButton(text = "Volver", onClick = onBackToProjects)
            }
            AteneaTextButton(
                text = if (loading) "Cargando..." else "Actualizar",
                enabled = !loading,
                onClick = { refreshRevision++ }
            )
            error?.let { ErrorPanel(it) }
            if (loaded && changes.isEmpty()) {
                Text("Este proyecto todavía no tiene cambios.")
            }
        }
        items(changes, key = { it.changeKey }) { change ->
            DevelopmentChangeCard(
                change = change,
                projectId = projectId,
                onOpenConversation = { sessionId -> onOpenConversation(projectId, sessionId) }
            )
        }
    }
}

@Composable
internal fun DevelopmentChangeCard(
    change: MobileDevelopmentChange,
    projectId: Long,
    onOpenConversation: (Long) -> Unit
) {
    val sessionId = change.sessionForReentry(projectId)
    AteneaPanel {
        Text(change.title, style = MaterialTheme.typography.titleSmall)
        MetricLine("Estado", change.status.toChangeStatusLabel())
        MetricLine("Workspace", change.workspaceState.toWorkspaceStateLabel())
        change.updatedAt?.let { MetricLine("Actividad", it.formatDateTimeForDisplay()) }
        if (sessionId != null) {
            AteneaButton(
                text = "Continuar conversación",
                modifier = Modifier.fillMaxWidth().testTag("development-change-continue-${change.changeKey}"),
                onClick = { onOpenConversation(sessionId) }
            )
        } else {
            Text(
                if (change.activeSessionId == null) "Sin WorkSession activa."
                else "Este cambio no está abierto para continuar.",
                style = MaterialTheme.typography.bodySmall
            )
            change.primaryActionLabel?.let { action ->
                Text("Siguiente acción: $action", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun String.toChangeStatusLabel(): String = when (this) {
    "OPEN" -> "Abierto"
    "PAUSED" -> "Pausado"
    "COMPLETED" -> "Completado"
    "ABANDONED" -> "Abandonado"
    else -> this
}

private fun String.toWorkspaceStateLabel(): String = when (this) {
    "READY" -> "Preparado"
    "NOT_PROVISIONED" -> "Sin preparar"
    "BLOCKED" -> "Bloqueado"
    "UNCERTAIN" -> "Pendiente de comprobación"
    else -> this
}
