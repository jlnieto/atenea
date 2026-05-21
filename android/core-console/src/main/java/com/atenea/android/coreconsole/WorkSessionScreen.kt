package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.atenea.android.api.SessionDeliverable
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
                onPublish = { repository.runCoreAction("Publicando", "publica la pr") },
                onSyncPullRequest = { repository.runCoreAction("Sincronizando PR", "sincroniza la pr") },
                onClose = { repository.runCoreAction("Cerrando sesion", "cierra la sesion") }
            )
            WorkSessionDeliverablesPanel(
                deliverables = state.deliverables,
                openDeliverableId = state.openDeliverableId,
                deliverableDetails = state.deliverableDetails,
                deliverableDetailLoadingId = state.deliverableDetailLoadingId,
                deliverableDetailError = state.deliverableDetailError,
                approvedPriceEstimate = it.approvedPriceEstimate,
                actions = it.actions,
                pendingAction = state.pendingAction,
                onToggleDeliverable = repository::toggleDeliverableDetail,
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
    onPublish: () -> Unit,
    onSyncPullRequest: () -> Unit,
    onClose: () -> Unit
) {
    AteneaPanel {
        Text("Entrega", style = MaterialTheme.typography.titleSmall)
        Text(deliveryStatusText(actions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            pendingAction != null -> AteneaButton(
                text = pendingAction,
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
                onClick = {}
            )
            actions.canClose -> AteneaButton(
                text = "Cerrar sesion",
                modifier = Modifier.fillMaxWidth(),
                onClick = onClose
            )
            actions.canPublish -> AteneaButton(
                text = "Publicar PR",
                modifier = Modifier.fillMaxWidth(),
                onClick = onPublish
            )
            actions.canSyncPullRequest -> AteneaOutlinedButton(
                text = "Sincronizar PR",
                modifier = Modifier.fillMaxWidth(),
                onClick = onSyncPullRequest
            )
        }
    }
}

@Composable
private fun WorkSessionDeliverablesPanel(
    deliverables: SessionDeliverablesView?,
    openDeliverableId: Long?,
    deliverableDetails: Map<Long, SessionDeliverable>,
    deliverableDetailLoadingId: Long?,
    deliverableDetailError: String?,
    approvedPriceEstimate: ApprovedPriceEstimateSummary?,
    actions: MobileSessionActions,
    pendingAction: String?,
    onToggleDeliverable: (Long) -> Unit,
    onGenerate: (String, String) -> Unit,
    onApprove: (Long) -> Unit,
    onMarkBilled: (Long, String) -> Unit
) {
    var billingReference by remember { mutableStateOf("") }
    AteneaPanel {
        Text("Entregables", style = MaterialTheme.typography.titleSmall)
        deliverables?.let {
            MetricLine("Generados", if (it.allCoreDeliverablesPresent) "Completos" else "Pendientes")
            MetricLine("Aprobados", if (it.allCoreDeliverablesApproved) "Completos" else "Pendientes")
            it.lastGeneratedAt?.let { generated -> MetricLine("Ultima generacion", generated.formatDateTimeForDisplay()) }
        }
        if (actions.canGenerateDeliverables) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AteneaOutlinedButton(
                    text = "Ticket",
                    modifier = Modifier.weight(1f),
                    enabled = pendingAction == null,
                    onClick = { onGenerate("Generando ticket", "genera el ticket de trabajo") }
                )
                AteneaOutlinedButton(
                    text = "Desglose",
                    modifier = Modifier.weight(1f),
                    enabled = pendingAction == null,
                    onClick = { onGenerate("Generando desglose", "genera el desglose de trabajo") }
                )
                AteneaOutlinedButton(
                    text = "Presupuesto",
                    modifier = Modifier.weight(1f),
                    enabled = pendingAction == null,
                    onClick = { onGenerate("Generando presupuesto", "genera el presupuesto") }
                )
            }
        }
        deliverables?.deliverables.orEmpty().forEach { deliverable ->
            DeliverableSummaryRow(
                deliverable = deliverable,
                detail = deliverableDetails[deliverable.id],
                expanded = openDeliverableId == deliverable.id,
                loading = deliverableDetailLoadingId == deliverable.id,
                detailError = if (openDeliverableId == deliverable.id) deliverableDetailError else null,
                canApprove = actions.canApproveDeliverables && pendingAction == null,
                onToggle = { onToggleDeliverable(deliverable.id) },
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
    detail: SessionDeliverable?,
    expanded: Boolean,
    loading: Boolean,
    detailError: String?,
    canApprove: Boolean,
    onToggle: () -> Unit,
    onApprove: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                deliverableTitle(deliverable),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                if (deliverable.approved) "Aprobado" else deliverable.status.displayLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        cleanDeliverablePreview(deliverable)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AteneaTextButton(
                text = if (expanded) "Ocultar" else "Leer",
                onClick = onToggle
            )
            if (!deliverable.approved) {
                AteneaTextButton(
                    text = "Aprobar",
                    enabled = canApprove,
                    onClick = { onApprove(deliverable.id) }
                )
            }
        }
        if (expanded) {
            DeliverableDetail(
                deliverable = detail,
                loading = loading,
                error = detailError
            )
        }
        if (!deliverable.approved) {
            Text("Entregable ${deliverable.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeliverableDetail(
    deliverable: SessionDeliverable?,
    loading: Boolean,
    error: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when {
            loading -> Text("Cargando entregable completo...", style = MaterialTheme.typography.bodySmall)
            error != null -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            deliverable == null -> Text("Sin contenido cargado.", style = MaterialTheme.typography.bodySmall)
            else -> {
                OpenDeliverableDocument(deliverable)
            }
        }
    }
}

@Composable
private fun OpenDeliverableDocument(deliverable: SessionDeliverable) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        openDeliverableLabel(deliverable.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        deliverable.title.ifBlank { "${deliverable.type.displayLabel()} v${deliverable.version}" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    if (deliverable.approved) "Aprobado" else deliverable.status.displayLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${deliverable.type.displayLabel()} v${deliverable.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                deliverable.updatedAt?.let {
                    Text(
                        it.formatDateTimeForDisplay(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            deliverable.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            deliverable.contentMarkdown?.takeIf { it.isNotBlank() }?.let {
                DeliverableMarkdown(it)
            }
            deliverable.generationNotes?.takeIf { it.isNotBlank() }?.let {
                Text("Notas de generacion", style = MaterialTheme.typography.labelMedium)
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    val visibleEvents = events
        .filterNot { it.title.contains("turn", ignoreCase = true) }
        .take(6)
    AteneaPanel {
        Text("Actividad reciente", style = MaterialTheme.typography.titleSmall)
        if (visibleEvents.isEmpty()) {
            Text("Aun no hay actividad sincronizada.", style = MaterialTheme.typography.bodySmall)
        }
        visibleEvents.forEach { event ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(eventTitle(event), style = MaterialTheme.typography.labelMedium)
                event.at?.let { Text(it.formatDateTimeForDisplay(), style = MaterialTheme.typography.bodySmall) }
                cleanEventDetails(event.details)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

private fun deliveryStatusText(actions: MobileSessionActions): String = when {
    actions.canClose -> "La pull request esta fusionada. Ya puedes cerrar y reconciliar el repositorio."
    actions.canPublish -> "La sesion esta lista para abrir pull request."
    actions.canSyncPullRequest -> "Hay una pull request publicada. Sincroniza su estado cuando se revise o se fusione."
    else -> "No hay accion de entrega disponible ahora mismo."
}

private fun deliverableTitle(deliverable: SessionDeliverableSummary): String = when (deliverable.type) {
    "WORK_TICKET" -> "Ticket de trabajo v${deliverable.version}"
    "WORK_BREAKDOWN" -> "Desglose v${deliverable.version}"
    "PRICE_ESTIMATE" -> "Presupuesto v${deliverable.version}"
    else -> "${deliverable.type.displayLabel()} v${deliverable.version}"
}

private fun openDeliverableLabel(type: String): String = when (type) {
    "PRICE_ESTIMATE" -> "Presupuesto abierto"
    "WORK_TICKET" -> "Ticket abierto"
    "WORK_BREAKDOWN" -> "Desglose abierto"
    else -> "Contenido abierto"
}

private fun cleanDeliverablePreview(deliverable: SessionDeliverableSummary): String? {
    return firstReadableLine(deliverable.preview)
        ?: firstReadableLine(deliverable.title)
}

private fun eventTitle(event: MobileSessionEvent): String {
    val title = event.title.ifBlank { event.type.displayLabel() }
    return title
        .replace("WORK_TICKET", "Ticket")
        .replace("WORK_BREAKDOWN", "Desglose")
        .replace("PRICE_ESTIMATE", "Presupuesto")
        .replace("generated", "generado")
        .replace("approved", "aprobado")
        .replace("Run succeeded", "Ejecucion completada")
        .replace("Run started", "Ejecucion iniciada")
}

private fun cleanEventDetails(value: String?): String? = firstReadableLine(value)

private fun firstReadableLine(value: String?): String? {
    if (value.isNullOrBlank()) {
        return null
    }
    val cleaned = value
        .replace("```", " ")
        .replace("#", " ")
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.takeIf { it.isNotBlank() }
}
