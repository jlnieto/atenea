package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    var loading by remember { mutableStateOf(false) }
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
            Text("Proyectos", style = MaterialTheme.typography.titleMedium)
            AteneaTextButton(text = if (loading) "Cargando..." else "Actualizar", enabled = !loading, onClick = ::refresh)
        }
        if (projects.isEmpty() && !loading) {
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
    AteneaPanel {
        Text(project.projectName, style = MaterialTheme.typography.titleSmall)
        project.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        AteneaButton(
            text = if (newChangePending) "Preparando cambio..." else "Nuevo cambio",
            modifier = Modifier.fillMaxWidth().testTag("project-new-development-change-action"),
            enabled = actionsEnabled,
            onClick = onNewDevelopmentChange
        )
        AteneaOutlinedButton(
            text = "Ver cambios",
            modifier = Modifier.fillMaxWidth().testTag("project-development-changes-action"),
            enabled = actionsEnabled,
            onClick = onOpenChanges
        )
        project.session?.let { session ->
            Text(session.title, style = MaterialTheme.typography.bodyMedium)
            Text(if (session.runInProgress) "Codex trabajando" else
                if (session.status == "CLOSING") "Finalización pendiente" else "Cambio en curso",
                style = MaterialTheme.typography.bodySmall)
            AteneaTextButton(
                text = "Continuar",
                modifier = Modifier.fillMaxWidth().testTag("project-continue-change-action"),
                enabled = actionsEnabled,
                onClick = { onContinue(session.sessionId) }
            )
        }
    }
}
