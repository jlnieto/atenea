package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileProjectOverview
import kotlinx.coroutines.launch

@Composable
internal fun ProjectsScreen(
    apiClient: AteneaApiClient,
    onOpenSession: (Long, Long) -> Unit,
    onOpenRescue: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<MobileProjectOverview>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draftTitleByProject by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var pendingProjectId by remember { mutableStateOf<Long?>(null) }

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

    fun openSession(project: MobileProjectOverview) {
        scope.launch {
            pendingProjectId = project.projectId
            error = null
            try {
                val session = project.session
                if (session?.recoveryPending == true) {
                    onOpenSession(project.projectId, session.sessionId)
                    return@launch
                }
                val hasActiveSession = session?.status in setOf("OPEN", "CLOSING")
                val title = draftTitleByProject[project.projectId]?.trim()?.takeIf { it.isNotBlank() }
                if (!hasActiveSession && title == null) {
                    error = "Indica un titulo para abrir una nueva sesion."
                    return@launch
                }
                val result = apiClient.resolveMobileWorkSession(project.projectId, title)
                onOpenSession(project.projectId, result.view.session.id)
            } catch (openError: Exception) {
                error = openError.message ?: "No se pudo abrir la sesión."
            } finally {
                pendingProjectId = null
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
                draftTitle = draftTitleByProject[project.projectId].orEmpty(),
                pending = pendingProjectId == project.projectId,
                actionsEnabled = pendingProjectId == null,
                onDraftTitleChange = { value ->
                    draftTitleByProject = draftTitleByProject + (project.projectId to value)
                },
                onOpenSession = { openSession(project) },
                onOpenRescue = { onOpenRescue(project.projectId) }
            )
        }
    }
}

@Composable
internal fun ProjectOverviewCard(
    project: MobileProjectOverview,
    draftTitle: String,
    pending: Boolean,
    actionsEnabled: Boolean,
    onDraftTitleChange: (String) -> Unit,
    onOpenSession: () -> Unit,
    onOpenRescue: () -> Unit
) {
    val recoveryPending = project.session?.recoveryPending == true
    val hasActiveSession = project.session?.status in setOf("OPEN", "CLOSING")
    AteneaPanel {
        Text(project.projectName, style = MaterialTheme.typography.titleSmall)
        project.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        project.defaultBaseBranch?.let { MetricLine("Base", it) }
        if (recoveryPending) {
            Text(
                "Nueva sesión pendiente",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "La sesión anterior ya está cerrada. Continúa la creación de su única sucesora vacía.",
                style = MaterialTheme.typography.bodySmall
            )
            MetricLine("WorkSession", project.session!!.sessionId.toString())
        } else {
            project.session?.let { session ->
                MetricLine("Sesión", session.title)
                MetricLine("Estado", if (session.runInProgress) "Codex trabajando" else session.status)
                session.pullRequestStatus?.let { MetricLine("Pull request", it.displayLabel()) }
                session.lastActivityAt?.let { MetricLine("Actividad", it.formatDateTimeForDisplay()) }
            } ?: Text("Sin WorkSession abierta.", style = MaterialTheme.typography.bodySmall)
        }

        if (!hasActiveSession && !recoveryPending) {
            OutlinedTextField(
                value = draftTitle,
                onValueChange = onDraftTitleChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Titulo para nueva sesion") }
            )
        }

        if (recoveryPending) {
            AteneaButton(
                text = if (pending) "Abriendo..." else "Continuar recuperación",
                modifier = Modifier.fillMaxWidth().testTag("project-recovery-action"),
                enabled = actionsEnabled,
                onClick = onOpenSession
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AteneaButton(
                    text = when {
                        pending -> "Abriendo..."
                        hasActiveSession -> "Abrir sesion"
                        else -> "Nueva sesion"
                    },
                    modifier = Modifier.weight(1f),
                    enabled = actionsEnabled && (hasActiveSession || draftTitle.isNotBlank()),
                    onClick = onOpenSession
                )
                AteneaOutlinedButton(
                    text = "Rescate",
                    modifier = Modifier.weight(1f),
                    enabled = actionsEnabled,
                    onClick = onOpenRescue
                )
            }
        }
    }
}
