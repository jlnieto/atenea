package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileDevelopmentChange
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID

internal fun MobileDevelopmentChange.sessionForReentry(expectedProjectId: Long): Long? =
    if (projectId != expectedProjectId) null else when (status) {
        "OPEN" -> (activeSessionId ?: retirementSessionId)?.takeIf { it > 0 }
        "COMPLETED" -> completedSessionId?.takeIf { it > 0 && activeSessionId == null }
        "ABANDONED" -> archivedSessionId?.takeIf { it > 0 && activeSessionId == null }
        else -> null
    }

internal fun orderedDevelopmentChanges(changes: List<MobileDevelopmentChange>): List<MobileDevelopmentChange> =
    changes.filter { it.status == "OPEN" } + changes.filter { it.status != "OPEN" }

internal fun developmentChangeHistory(change: MobileDevelopmentChange): Boolean =
    change.status in setOf("COMPLETED", "ABANDONED")

internal fun closedSessionHistoryOnly(status: String, completionAvailable: Boolean): Boolean =
    status == "CLOSED" && !completionAvailable

internal fun canContinueDevelopmentChange(change: MobileDevelopmentChange, expectedProjectId: Long): Boolean =
    change.projectId == expectedProjectId && change.status == "OPEN" && change.activeSessionId == null
        && change.retirementState == null && change.primaryActionEnabled
        && change.primaryActionKind in setOf("PROVISION_WORKSPACE", "BIND_SESSION", "INSPECT_WORKSPACE", "RECONCILE_WORKSPACE")

internal fun changeConversationLabel(change: MobileDevelopmentChange): String = when {
    developmentChangeHistory(change) -> "Ver conversación"
    change.retirementState != null -> "Ver conversación · retirada pendiente"
    change.activeSessionStatus == "CLOSING" -> "Ver conversación · cierre pendiente"
    else -> "Continuar cambio"
}

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
    var showHistory by remember(projectId) { mutableStateOf(false) }
    var acting by remember(projectId) { mutableStateOf(false) }
    var continuingChangeKey by remember(projectId) { mutableStateOf<UUID?>(null) }
    var pendingAbandon by remember(projectId) { mutableStateOf<MobileDevelopmentChange?>(null) }
    val scope = rememberCoroutineScope()
    val keys = remember(projectId) { mutableMapOf<UUID, UUID>() }

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

    LaunchedEffect(changes) {
        if (changes.any { it.retirementState == "APPLYING" }) {
            delay(3000)
            refreshRevision++
        }
    }

    pendingAbandon?.let { change ->
        AlertDialog(onDismissRequest = { if (!acting) pendingAbandon = null },
            title = { Text("Abandonar cambio") },
            text = { Text("Se retirará «${change.title}» del trabajo activo. No se integrará ni publicará. El código, la conversación y los recibos se conservarán en Historial.") },
            confirmButton = { AteneaTextButton("Abandonar", enabled = !acting, onClick = {
                scope.launch {
                    acting = true
                    try {
                        val key = keys.getOrPut(change.changeKey) { UUID.randomUUID() }
                        val sessionId = change.activeSessionId ?: change.retirementSessionId
                        if (sessionId == null) apiClient.abandonDevelopmentChange(change.projectId, change.changeKey, key)
                        else if (change.retirementState == "BLOCKED") {
                            apiClient.retireWorkSession(sessionId, apiClient.fetchRetirementKey(sessionId), reconcile = true)
                        } else apiClient.retireWorkSession(sessionId, key)
                        pendingAbandon = null
                        refreshRevision++
                    } catch (failure: Exception) { error = failure.message ?: "No se pudo retirar el cambio." }
                    finally { acting = false }
                }
            }) },
            dismissButton = { AteneaTextButton("Cancelar", enabled = !acting, onClick = { pendingAbandon = null }) })
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
            Row(horizontalArrangement = Arrangement.spacedBy(AteneaSpacing.medium)) {
                AteneaTextButton("Activos (${changes.count { !developmentChangeHistory(it) }})", onClick = { showHistory = false })
                AteneaTextButton("Historial (${changes.count { developmentChangeHistory(it) }})", onClick = { showHistory = true })
            }
            Text(if (showHistory) "Historial · completados y abandonados" else "Trabajo activo")
            error?.let { ErrorPanel(it) }
            if (loaded && changes.none { developmentChangeHistory(it) == showHistory }) {
                Text(if (showHistory) "No hay cambios en el historial." else "No hay cambios pendientes.")
            }
        }
        items(changes.filter { developmentChangeHistory(it) == showHistory }, key = { it.changeKey }) { change ->
            DevelopmentChangeCard(
                change = change,
                projectId = projectId,
                onOpenConversation = { sessionId -> onOpenConversation(projectId, sessionId) },
                onAbandon = if (!developmentChangeHistory(change) && apiClient.currentOperatorRole() == "PLATFORM_ADMINISTRATOR"
                    && change.retirementState != "APPLYING") ({ pendingAbandon = change }) else null,
                onPrepare = if (canContinueDevelopmentChange(change, projectId)) ({
                    if (!acting) {
                        acting = true
                        continuingChangeKey = change.changeKey
                        scope.launch {
                            try {
                                val key = keys.getOrPut(change.changeKey) { UUID.randomUUID() }
                                val sessionKey = UUID.nameUUIDFromBytes(("open-session:$key").toByteArray())
                                val sid = apiClient.prepareExistingDevelopmentChange(projectId, change.changeKey, key, sessionKey)
                                onOpenConversation(projectId, sid)
                            } catch (failure: Exception) { error = failure.message ?: "No se pudo continuar el cambio." }
                            finally { acting = false; continuingChangeKey = null; refreshRevision++ }
                        }
                    }
                    }) else null,
                continuing = continuingChangeKey == change.changeKey,
                enabled = !acting
            )
        }
    }
}

@Composable
internal fun DevelopmentChangeCard(
    change: MobileDevelopmentChange,
    projectId: Long,
    onOpenConversation: (Long) -> Unit,
    onAbandon: (() -> Unit)? = null,
    onPrepare: (() -> Unit)? = null,
    continuing: Boolean = false,
    enabled: Boolean = true
) {
    val sessionId = change.sessionForReentry(projectId)
    AteneaPanel {
        Text(change.title, style = MaterialTheme.typography.titleSmall)
        MetricLine("Estado", change.status.toChangeStatusLabel())
        MetricLine("Workspace", change.workspaceState.toWorkspaceStateLabel())
        change.updatedAt?.let { MetricLine("Actividad", it.formatDateTimeForDisplay()) }
        if (sessionId != null) {
            AteneaButton(
                text = changeConversationLabel(change),
                modifier = Modifier.fillMaxWidth().testTag("development-change-continue-${change.changeKey}"),
                onClick = { onOpenConversation(sessionId) }
            )
        } else {
            Text(
                if (developmentChangeHistory(change)) "Historial conservado."
                else if (onPrepare != null) "Atenea terminará la preparación y abrirá la conversación de este cambio."
                else if (change.activeSessionId == null) "La conversación aún no está disponible."
                else "Este cambio no está abierto para continuar.",
                style = MaterialTheme.typography.bodySmall
            )
            change.primaryActionLabel?.let { action ->
                Text("Siguiente acción: ${if (onPrepare != null) "Continuar cambio" else action}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (change.retirementState != null) Text("Retirada: ${change.retirementState}")
        onPrepare?.let { AteneaButton(if (continuing) "Abriendo conversación…" else "Continuar cambio",
            modifier = Modifier.testTag("development-change-prepare-${change.changeKey}"),
            enabled = enabled && !continuing, onClick = it) }
        onAbandon?.let { AteneaTextButton(if (change.retirementState == "BLOCKED") "Recuperar retirada" else "Abandonar cambio", enabled = enabled, onClick = it) }
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
