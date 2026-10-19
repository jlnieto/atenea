package com.atenea.android.coreconsole

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileProjectOverview
import kotlinx.coroutines.launch

@Composable
internal fun ProjectsScreen(
    apiClient: AteneaApiClient,
    onOpenConversation: (Long, Long) -> Unit,
    onOpenChanges: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val newChangeViewModel = remember(apiClient) {
        NewDevelopmentChangeViewModel(apiClient::startNewDevelopmentChange)
    }
    val newChangeState by newChangeViewModel.state.collectAsStateWithLifecycle()
    var projects by remember { mutableStateOf<List<MobileProjectOverview>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                projects = apiClient.fetchMobileProjectsOverview()
            } catch (loadError: Exception) {
                error = loadError.message ?: "No se pudieron cargar los proyectos."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AteneaSpacing.medium)
    ) {
        error?.let { ErrorPanel(it) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (loading && projects.isEmpty()) "Cargando proyectos…"
                else "${projects.size} ${if (projects.size == 1) "proyecto" else "proyectos"}",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AteneaTextButton(text = if (loading) "Cargando..." else "Actualizar", enabled = !loading, onClick = ::refresh)
        }
        if (projects.isEmpty() && !loading && error == null) {
            Text("No hay proyectos disponibles.", style = MaterialTheme.typography.bodyMedium)
        }
        projects.forEach { project ->
            ProjectOverviewCard(
                project = project,
                actionsEnabled = !newChangeState.submitting,
                newChangePending = newChangeState.submitting && newChangeState.projectId == project.projectId,
                onContinue = { sessionId -> onOpenConversation(project.projectId, sessionId) },
                onOpenChanges = { onOpenChanges(project.projectId) },
                onNewDevelopmentChange = { newChangeViewModel.open(project.projectId) }
            )
        }
    }

    if (newChangeState.visible) {
        AlertDialog(
            modifier = Modifier.testTag("new-development-change-dialog"),
            onDismissRequest = newChangeViewModel::dismiss,
            title = { Text("Nuevo cambio") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newChangeState.title,
                        onValueChange = newChangeViewModel::updateTitle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new-development-change-title"),
                        enabled = !newChangeState.submitting && !newChangeState.attemptStarted,
                        singleLine = true,
                        label = { Text("Titulo") }
                    )
                    if (newChangeState.submitting) {
                        Text(
                            "Creando cambio y preparando workspace...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    newChangeState.error?.let { ErrorPanel(it) }
                }
            },
            confirmButton = {
                AteneaButton(
                    text = when {
                        newChangeState.submitting -> "Preparando..."
                        newChangeState.attemptStarted -> "Reintentar"
                        else -> "Crear y abrir"
                    },
                    enabled = newChangeState.canSubmit,
                    onClick = {
                        scope.launch {
                            newChangeViewModel.submit()?.let { navigation ->
                                onOpenConversation(navigation.projectId, navigation.sessionId)
                            }
                        }
                    }
                )
            },
            dismissButton = {
                AteneaTextButton(
                    text = "Cancelar",
                    enabled = !newChangeState.submitting,
                    onClick = newChangeViewModel::dismiss
                )
            }
        )
    }
}

@Composable
internal fun ProjectOverviewCard(
    project: MobileProjectOverview,
    newChangePending: Boolean,
    actionsEnabled: Boolean,
    onContinue: (Long) -> Unit,
    onOpenChanges: () -> Unit,
    onNewDevelopmentChange: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    project.projectName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                project.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            project.session?.let { session ->
                val sessionStatus = when {
                    session.runInProgress -> "Codex trabajando"
                    session.status == "CLOSING" -> "Finalización pendiente"
                    else -> "Cambio en curso"
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                sessionStatus,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (session.status == "CLOSING") MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                session.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        AteneaTextButton(
                            text = "Continuar",
                            modifier = Modifier.testTag("project-continue-change-action"),
                            enabled = actionsEnabled,
                            onClick = { onContinue(session.sessionId) }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AteneaButton(
                    text = if (newChangePending) "Preparando..." else "Nuevo cambio",
                    modifier = Modifier.weight(1f).testTag("project-new-development-change-action"),
                    enabled = actionsEnabled,
                    onClick = onNewDevelopmentChange
                )
                AteneaOutlinedButton(
                    text = "Ver cambios",
                    modifier = Modifier.weight(1f).testTag("project-development-changes-action"),
                    enabled = actionsEnabled,
                    onClick = onOpenChanges
                )
            }
        }
    }
}
