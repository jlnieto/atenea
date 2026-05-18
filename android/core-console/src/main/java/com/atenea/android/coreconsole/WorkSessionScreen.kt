package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.atenea.android.api.ApprovedPriceEstimateSummary
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileSessionActions
import com.atenea.android.api.MobileSessionEvent
import com.atenea.android.api.MobileSessionSummary
import com.atenea.android.api.SessionDeliverableSummary
import com.atenea.android.api.SessionDeliverablesView

@Composable
internal fun WorkSessionScreen(
    apiClient: AteneaApiClient,
    projectId: Long?,
    sessionId: Long?,
    onOpenConversation: () -> Unit,
    onOpenCore: () -> Unit,
    onBackToProjects: () -> Unit
) {
    val scope = rememberCoroutineScope()
    if (sessionId == null) {
        AteneaPanel {
            Text("No hay WorkSession seleccionada.")
            AteneaButton(text = "Abrir proyectos", onClick = onBackToProjects)
        }
        return
    }

    val repository = remember(apiClient, projectId, sessionId) {
        WorkSessionRepository(apiClient, projectId, sessionId, scope)
    }
    DisposableEffect(repository) {
        repository.start()
        onDispose { repository.stop() }
    }

    val state by repository.state.collectAsState()
    val summary = state.summary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AteneaSpacing.medium)
    ) {
        WorkSessionHeader(
            state = state,
            summary = summary,
            onRefresh = repository::refreshNow,
            onOpenConversation = onOpenConversation,
            onOpenCore = onOpenCore,
            onBackToProjects = onBackToProjects
        )

        state.error?.let { ErrorPanel(it) }

        state.activeCommand?.let { command ->
            CommandCard(
                command = command,
                pending = state.commandPending,
                onConfirm = repository::confirmActiveCommand,
                onClarification = repository::resolveClarification
            )
            AteneaTextButton(text = "Ocultar resultado", onClick = repository::clearCommand)
        }

        summary?.let {
            WorkSessionInsights(summary = it)
            WorkSessionActionsPanel(
                actions = it.actions,
                pendingAction = state.pendingAction,
                onOpenConversation = onOpenConversation,
                onPublish = { repository.runCoreAction("Publicando", "publica la pr") },
                onSyncPullRequest = { repository.runCoreAction("Sincronizando PR", "sincroniza la pr") },
                onClose = { repository.runCoreAction("Cerrando sesion", "cierra la sesion") }
            )
            WorkSessionDeliverablesPanel(
                deliverables = state.deliverables,
                approvedPriceEstimate = it.approvedPriceEstimate,
                actions = it.actions,
                pendingAction = state.pendingAction,
                onGenerate = { label, input -> repository.runCoreAction(label, input) },
                onApprove = { deliverableId ->
                    repository.runCoreAction("Aprobando entregable", "aprueba el deliverable $deliverableId")
                },
                onMarkBilled = { deliverableId, reference ->
                    repository.runCoreAction(
                        "Marcando facturacion",
                        "marca el deliverable $deliverableId como facturado con referencia $reference"
                    )
                }
            )
        }

        WorkSessionEventsPanel(events = state.events)
    }
}

@Composable
private fun WorkSessionHeader(
    state: WorkSessionUiState,
    summary: MobileSessionSummary?,
    onRefresh: () -> Unit,
    onOpenConversation: () -> Unit,
    onOpenCore: () -> Unit,
    onBackToProjects: () -> Unit
) {
    val session = summary?.conversation?.session
    AteneaPanel {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    session?.title ?: "WorkSession ${state.sessionId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state.syncLabel + if (state.refreshing) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                level = when {
                    state.stale -> OperationalLevel.WARNING
                    state.error != null -> OperationalLevel.WARNING
                    summary?.conversation?.runInProgress == true -> OperationalLevel.WARNING
                    summary != null -> OperationalLevel.OK
                    else -> OperationalLevel.UNKNOWN
                }
            )
        }
        session?.let {
            MetricLine("Estado", if (summary.conversation.runInProgress) "Codex trabajando" else it.status.displayLabel())
            MetricLine("Operacion", it.operationalState.displayLabel())
            it.workspaceBranch?.let { branch -> MetricLine("Branch", branch) }
            it.pullRequestStatus?.let { status -> MetricLine("Pull request", status.displayLabel()) }
            it.pullRequestUrl?.let { url -> MetricLine("URL PR", url) }
            it.lastActivityAt?.let { last -> MetricLine("Actividad", last.formatDateTimeForDisplay()) }
            if (!it.closeBlockedState.isNullOrBlank()) {
                MetricLine("Cierre bloqueado", it.closeBlockedState.displayLabel(), OperationalLevel.WARNING)
                it.closeBlockedReason?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                it.closeBlockedAction?.let { action ->
                    Text(action, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AteneaOutlinedButton(
                text = if (state.loading || state.refreshing) "Actualizando..." else "Actualizar",
                modifier = Modifier.weight(1f),
                enabled = !state.loading && !state.refreshing,
                onClick = onRefresh
            )
            AteneaOutlinedButton(
                text = "Conversacion",
                modifier = Modifier.weight(1f),
                enabled = summary != null,
                onClick = onOpenConversation
            )
        }
        AteneaTextButton(text = "Abrir Core", onClick = onOpenCore)
        AteneaTextButton(text = "Volver a proyectos", onClick = onBackToProjects)
    }
}

@Composable
private fun WorkSessionInsights(summary: MobileSessionSummary) {
    AteneaPanel {
        Text("Lectura operativa", style = MaterialTheme.typography.titleSmall)
        summary.insights.latestProgress?.let { MetricText("Progreso", it) }
        summary.insights.currentBlocker?.summary?.let { blocker ->
            MetricText("Bloqueo", blocker)
        }
        summary.insights.nextStepRecommended?.let { MetricText("Siguiente paso", it) }
        summary.conversation.lastAgentResponse?.let {
            Text("Ultima respuesta de Codex", style = MaterialTheme.typography.labelMedium)
            Text(it.take(420), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WorkSessionActionsPanel(
    actions: MobileSessionActions,
    pendingAction: String?,
    onOpenConversation: () -> Unit,
    onPublish: () -> Unit,
    onSyncPullRequest: () -> Unit,
    onClose: () -> Unit
) {
    AteneaPanel {
        Text("Acciones Core", style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AteneaButton(
                text = "Instruir Codex",
                modifier = Modifier.weight(1f),
                enabled = actions.canCreateTurn && pendingAction == null,
                onClick = onOpenConversation
            )
            AteneaButton(
                text = pendingAction ?: "Publicar PR",
                modifier = Modifier.weight(1f),
                enabled = actions.canPublish && pendingAction == null,
                onClick = onPublish
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AteneaOutlinedButton(
                text = "Sync PR",
                modifier = Modifier.weight(1f),
                enabled = actions.canSyncPullRequest && pendingAction == null,
                onClick = onSyncPullRequest
            )
            AteneaOutlinedButton(
                text = "Cerrar",
                modifier = Modifier.weight(1f),
                enabled = actions.canClose && pendingAction == null,
                onClick = onClose
            )
        }
    }
}

@Composable
private fun WorkSessionDeliverablesPanel(
    deliverables: SessionDeliverablesView?,
    approvedPriceEstimate: ApprovedPriceEstimateSummary?,
    actions: MobileSessionActions,
    pendingAction: String?,
    onGenerate: (String, String) -> Unit,
    onApprove: (Long) -> Unit,
    onMarkBilled: (Long, String) -> Unit
) {
    var billingReference by remember { mutableStateOf("") }
    AteneaPanel {
        Text("Entregables", style = MaterialTheme.typography.titleSmall)
        deliverables?.let {
            MetricLine("Core presentes", if (it.allCoreDeliverablesPresent) "Si" else "Pendientes")
            MetricLine("Core aprobados", if (it.allCoreDeliverablesApproved) "Si" else "Pendientes")
            it.lastGeneratedAt?.let { generated -> MetricLine("Ultima generacion", generated.formatDateTimeForDisplay()) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AteneaOutlinedButton(
                text = "Ticket",
                modifier = Modifier.weight(1f),
                enabled = actions.canGenerateDeliverables && pendingAction == null,
                onClick = { onGenerate("Generando ticket", "genera el ticket de trabajo") }
            )
            AteneaOutlinedButton(
                text = "Desglose",
                modifier = Modifier.weight(1f),
                enabled = actions.canGenerateDeliverables && pendingAction == null,
                onClick = { onGenerate("Generando desglose", "genera el desglose de trabajo") }
            )
            AteneaOutlinedButton(
                text = "Presupuesto",
                modifier = Modifier.weight(1f),
                enabled = actions.canGenerateDeliverables && pendingAction == null,
                onClick = { onGenerate("Generando presupuesto", "genera el presupuesto") }
            )
        }
        deliverables?.deliverables.orEmpty().forEach { deliverable ->
            DeliverableSummaryRow(
                deliverable = deliverable,
                canApprove = actions.canApproveDeliverables && pendingAction == null,
                onApprove = onApprove
            )
        }
        approvedPriceEstimate?.let { estimate ->
            ApprovedPriceEstimatePanel(
                estimate = estimate,
                billingReference = billingReference,
                onBillingReferenceChange = { billingReference = it },
                enabled = actions.canMarkApprovedPriceEstimateBilled && pendingAction == null,
                onMarkBilled = { onMarkBilled(estimate.deliverableId, billingReference.trim()) }
            )
        }
    }
}

@Composable
private fun DeliverableSummaryRow(
    deliverable: SessionDeliverableSummary,
    canApprove: Boolean,
    onApprove: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${deliverable.type.displayLabel()} v${deliverable.version}",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                if (deliverable.approved) "Aprobado" else deliverable.status.displayLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(deliverable.title, style = MaterialTheme.typography.bodyMedium)
        deliverable.preview?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!deliverable.approved) {
            AteneaTextButton(
                text = "Aprobar entregable ${deliverable.id}",
                enabled = canApprove,
                onClick = { onApprove(deliverable.id) }
            )
        }
    }
}

@Composable
private fun ApprovedPriceEstimatePanel(
    estimate: ApprovedPriceEstimateSummary,
    billingReference: String,
    onBillingReferenceChange: (String) -> Unit,
    enabled: Boolean,
    onMarkBilled: () -> Unit
) {
    Text("Presupuesto aprobado", style = MaterialTheme.typography.labelLarge)
    MetricLine("Recomendado", "${estimate.recommendedPrice} ${estimate.currency.orEmpty()}".trim())
    MetricLine("Rango", "${estimate.minimumPrice} - ${estimate.maximumPrice}")
    estimate.billingStatus?.let { MetricLine("Facturacion", it.displayLabel()) }
    if (enabled) {
        OutlinedTextField(
            value = billingReference,
            onValueChange = onBillingReferenceChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Referencia de factura") }
        )
        AteneaButton(
            text = "Marcar facturado",
            modifier = Modifier.fillMaxWidth(),
            enabled = billingReference.isNotBlank(),
            onClick = onMarkBilled
        )
    }
}

@Composable
private fun WorkSessionEventsPanel(events: List<MobileSessionEvent>) {
    AteneaPanel {
        Text("Timeline", style = MaterialTheme.typography.titleSmall)
        if (events.isEmpty()) {
            Text("Aun no hay eventos sincronizados.", style = MaterialTheme.typography.bodySmall)
        }
        events.take(12).forEach { event ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(event.title.ifBlank { event.type.displayLabel() }, style = MaterialTheme.typography.labelMedium)
                event.at?.let { Text(it.formatDateTimeForDisplay(), style = MaterialTheme.typography.bodySmall) }
                event.details?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MetricText(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
