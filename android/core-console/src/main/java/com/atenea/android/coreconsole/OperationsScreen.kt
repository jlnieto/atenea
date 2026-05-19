package com.atenea.android.coreconsole

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.CoreCommandResponse
import com.atenea.android.api.CoreScope
import com.atenea.android.api.ManagedHost
import com.atenea.android.api.OperationsActionRun
import com.atenea.android.api.OperationsHostStatus
import com.atenea.android.api.OperationsIncident
import com.atenea.android.api.WebsiteCheck
import kotlinx.coroutines.launch

@Composable
internal fun OperationsScreen(
    apiClient: AteneaApiClient,
    onHealthSnapshotChanged: (ShellHealthSnapshot) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var hosts by remember { mutableStateOf<List<ManagedHost>>(emptyList()) }
    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    var hostStatus by remember { mutableStateOf<OperationsHostStatus?>(null) }
    var incidents by remember { mutableStateOf<List<OperationsIncident>>(emptyList()) }
    var loadingData by remember { mutableStateOf(false) }
    var commandPending by remember { mutableStateOf(false) }
    var pendingOperation by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var commandResponse by remember { mutableStateOf<CoreCommandResponse?>(null) }
    var lastRequest by remember { mutableStateOf<CoreCommandRequestState?>(null) }

    suspend fun loadOperationsData(preferredHostId: Long? = selectedHostId) {
        val nextHosts = apiClient.fetchOperationsHosts()
        hosts = nextHosts
        val nextHost = nextHosts.firstOrNull { it.id == preferredHostId }
            ?: nextHosts.firstOrNull { it.name.contains("dedicado", ignoreCase = true) }
            ?: nextHosts.firstOrNull()
        selectedHostId = nextHost?.id
        hostStatus = nextHost?.let { apiClient.fetchOperationsHostStatus(it.id) }
        incidents = apiClient.fetchOperationsIncidents()
        onHealthSnapshotChanged(
            HealthOverview(
                hosts = listOfNotNull(hostStatus),
                incidents = incidents
            ).snapshot
        )
    }

    fun refreshOperations(preferredHostId: Long? = selectedHostId) {
        scope.launch {
            loadingData = true
            pendingOperation = "Actualizando estado del dedicado, webs e incidencias."
            error = null
            commandResponse = null
            lastRequest = null
            hostStatus = null
            incidents = emptyList()
            try {
                loadOperationsData(preferredHostId)
            } catch (loadError: Exception) {
                error = loadError.message ?: "No se pudo cargar Operaciones."
            } finally {
                loadingData = false
                pendingOperation = null
            }
        }
    }

    fun runOperationCommand(commandInput: String, label: String) {
        scope.launch {
            commandPending = true
            pendingOperation = label
            error = null
            commandResponse = null
            hostStatus = null
            incidents = emptyList()
            try {
                val request = CoreCommandRequestState(commandInput, CoreScope.GLOBAL)
                commandResponse = apiClient.runCoreCommand(request.input, request.scope)
                lastRequest = request
                loadOperationsData()
            } catch (commandError: Exception) {
                error = commandError.message ?: "No se pudo ejecutar la acción."
            } finally {
                commandPending = false
                pendingOperation = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OperationHeader(status = hostStatus, loading = loadingData)

        if (hosts.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                hosts.forEach { host ->
                    FilterChip(
                        selected = selectedHostId == host.id,
                        onClick = {
                            selectedHostId = host.id
                            refreshOperations(host.id)
                        },
                        shape = RoundedCornerShape(2.dp),
                        label = { Text(host.name) }
                    )
                }
            }
        }

        OperationsActions(
            status = hostStatus,
            loading = loadingData,
            pending = commandPending,
            onRefresh = { refreshOperations() },
            onReview = { runOperationCommand("revisa el dedicado", "Ejecutando diagnóstico general del dedicado.") },
            onCheckApache = { runOperationCommand("comprueba apache en el dedicado", "Comprobando Apache en el dedicado.") },
            onRecoverApache = { runOperationCommand("recupera apache en el dedicado", "Preparando recuperación controlada de Apache.") }
        )

        pendingOperation?.let {
            AteneaPanel {
                Text("En curso", style = MaterialTheme.typography.titleMedium)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        error?.let { ErrorPanel(it) }

        commandResponse?.let { command ->
            if (command.isSuccessfulApacheCommand(lastRequest)) {
                ApacheOperationResultPanel(
                    command = command,
                    request = lastRequest,
                    status = hostStatus,
                    incidents = incidents
                )
            } else {
                CommandCard(
                    command = command,
                    pending = commandPending,
                    onConfirm = { token ->
                        scope.launch {
                            commandPending = true
                            error = null
                            try {
                                pendingOperation = "Confirmando y ejecutando la operación."
                                commandResponse = apiClient.confirmCoreCommand(command.commandId, token)
                                loadOperationsData()
                            } catch (confirmError: Exception) {
                                error = confirmError.message ?: "No se pudo confirmar la acción."
                            } finally {
                                commandPending = false
                                pendingOperation = null
                            }
                        }
                    },
                    onClarification = { option ->
                        val request = lastRequest
                        if (request == null) {
                            error = "No hay contexto de aclaración disponible."
                        } else {
                            scope.launch {
                                commandPending = true
                                error = null
                                try {
                                    val nextRequest = request.resolve(option)
                                    commandResponse = apiClient.runCoreCommand(
                                        input = nextRequest.input,
                                        scope = nextRequest.scope,
                                        projectId = nextRequest.projectId,
                                        workSessionId = nextRequest.workSessionId
                                    )
                                    lastRequest = nextRequest
                                    loadOperationsData()
                                } catch (clarificationError: Exception) {
                                    error = clarificationError.message ?: "No se pudo aplicar la aclaración."
                                } finally {
                                    commandPending = false
                                }
                            }
                        }
                    }
                )
            }
        }

        hostStatus?.let { status ->
            HostStatusPanel(status)
            WebsitePanel(status)
        }

        if (incidents.isNotEmpty()) {
            Text("Incidencias", style = MaterialTheme.typography.titleMedium)
            incidents.forEach { incident -> IncidentPanel(incident) }
        }
    }
}

@Composable
private fun ApacheOperationResultPanel(
    command: CoreCommandResponse,
    request: CoreCommandRequestState?,
    status: OperationsHostStatus?,
    incidents: List<OperationsIncident>
) {
    val input = request?.input.orEmpty().lowercase()
    val message = command.operatorMessage ?: command.speakableMessage.orEmpty()
    val isRecovery = input.contains("recuper")
    val apacheService = status?.services?.firstOrNull { it.name.contains("apache", ignoreCase = true) }
    val apacheIncidents = incidents.filter {
        it.serviceName?.contains("apache", ignoreCase = true) == true ||
            it.title.contains("apache", ignoreCase = true)
    }
    val websitesOk = status?.websiteChecks?.let { checks ->
        checks.isNotEmpty() && checks.all { it.healthy }
    }
    val allClear = apacheService?.active == true && websitesOk == true && apacheIncidents.isEmpty()
    val beforeProcesses = message.metricInt("apacheProcessesBefore")
        ?: Regex("estado inicial \\w+ con (\\d+) procesos").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val afterProcesses = message.metricInt("apacheProcessesAfter")
        ?: Regex("estado final \\w+ con (\\d+) procesos").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val leftoversAfterStop = message.metricInt("leftoverAfterStop")
        ?: Regex("(\\d+) procesos segu").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val killed = message.metricInt("leftoverKilled")
    val slowestWebsite = status?.websiteChecks?.maxByOrNull { it.durationMillis }
    var showTechnicalDetail by remember(command.commandId) { mutableStateOf(false) }

    AteneaPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        isRecovery && allClear -> "Apache recuperado"
                        isRecovery -> "Recuperación incompleta"
                        allClear -> "Apache comprobado"
                        else -> "Apache requiere revisión"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    when {
                        isRecovery && allClear ->
                            recoveryConclusion(beforeProcesses, afterProcesses, leftoversAfterStop, killed)
                        allClear ->
                            "Apache está activo, la configuración valida y las webs responden correctamente."
                        apacheService?.active == true && websitesOk == true ->
                            "Apache está activo y las webs responden, pero queda una incidencia abierta."
                        apacheService?.active == true ->
                            "Apache está activo. Revisa abajo webs o incidencias para ver qué queda pendiente."
                        else -> "No tengo todavía una lectura completa del servicio Apache."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            StatusPill(if (allClear) OperationalLevel.OK else OperationalLevel.WARNING)
        }

        MetricLine("Apache", if (apacheService?.active == true) "activo" else "sin confirmar", serviceLevel(apacheService?.active))
        MetricLine("Webs", websiteResultLabel(status), status?.websiteLevel() ?: OperationalLevel.UNKNOWN)
        if (isRecovery || leftoversAfterStop != null) {
            MetricLine(
                "Procesos colgados",
                leftoversAfterStop?.toString() ?: "sin dato",
                if (leftoversAfterStop == 0) OperationalLevel.OK else OperationalLevel.WARNING
            )
        }
        MetricLine("Incidencias", apacheIncidents.size.toString(), if (apacheIncidents.isEmpty()) OperationalLevel.OK else OperationalLevel.CRITICAL)

        Text("Validación", style = MaterialTheme.typography.labelLarge)
        ValidationLine("Servicio apache2 activo", apacheService?.active == true)
        ValidationLine("Configtest OK", message.contains("configtest=OK", ignoreCase = true) || message.contains("configtest = OK", ignoreCase = true))
        ValidationLine("Puertos 80/443 detectados", message.contains(":443") && message.contains(":80"))
        ValidationLine("Webs ${status?.healthyWebsites ?: 0}/${status?.websiteChecks?.size ?: 0} OK", websitesOk == true)
        slowestWebsite?.let {
            Text(
                "Web más lenta: ${it.name} · ${it.durationMillis} ms.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (apacheIncidents.isNotEmpty()) {
            Text("Incidencias abiertas", style = MaterialTheme.typography.labelLarge)
            apacheIncidents.forEach { incident ->
                Text(
                    "#${incident.id} · ${incident.title}: ${incident.summary ?: "sin detalle"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        AteneaTextButton(
            text = if (showTechnicalDetail) "Ocultar detalle técnico" else "Ver detalle técnico",
            onClick = { showTechnicalDetail = !showTechnicalDetail }
        )
        if (showTechnicalDetail) {
            Text("Comando #${command.commandId} · ${command.status}", style = MaterialTheme.typography.labelLarge)
            FormattedCommandMessage(message.ifBlank { "Sin detalle técnico disponible." })
        }
    }
}

@Composable
private fun OperationHeader(status: OperationsHostStatus?, loading: Boolean) {
    val level = status.operationalLevel(loading)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(status?.host?.name ?: "Sin servidor", style = MaterialTheme.typography.titleMedium)
                Text(status?.oneLineSummary() ?: "Actualiza para cargar el estado.", style = MaterialTheme.typography.bodySmall)
            }
            StatusPill(level)
        }
    }
}

@Composable
private fun OperationsActions(
    status: OperationsHostStatus?,
    loading: Boolean,
    pending: Boolean,
    onRefresh: () -> Unit,
    onReview: () -> Unit,
    onCheckApache: () -> Unit,
    onRecoverApache: () -> Unit
) {
    val showRecover = status.operationalLevel(loading) == OperationalLevel.CRITICAL
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showRecover) {
            AteneaButton(
                text = if (pending) "Ejecutando..." else "Recuperar Apache",
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && !pending,
                onClick = onRecoverApache
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AteneaOutlinedButton(
                text = if (loading) "..." else "Actualizar",
                modifier = Modifier.weight(1f),
                enabled = !loading && !pending,
                onClick = onRefresh
            )
            AteneaOutlinedButton(
                text = "Apache",
                modifier = Modifier.weight(1f),
                enabled = !pending,
                onClick = onCheckApache
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AteneaOutlinedButton(
                text = "Diagnóstico",
                modifier = Modifier.weight(1f),
                enabled = !pending,
                onClick = onReview
            )
            AteneaOutlinedButton(
                text = "Recuperar Apache",
                modifier = Modifier.weight(1f),
                enabled = !pending,
                onClick = onRecoverApache
            )
        }
    }
}

@Composable
private fun HostStatusPanel(status: OperationsHostStatus) {
    var showDetails by remember(status.hostStatusRun?.id) {
        mutableStateOf(status.hostStatusRun?.status == "FAILED")
    }
    AteneaPanel {
        Text("Servidor", style = MaterialTheme.typography.titleMedium)
        MetricLine("Servicios", "${status.services.count { it.active }} activos", OperationalLevel.OK)
        MetricLine("Webs", "${status.healthyWebsites}/${status.websiteChecks.size} OK", status.websiteLevel())
        MetricLine("Incidencias", status.openIncidents.size.toString(), status.incidentLevel())
        status.hostStatusRun?.let { run ->
            Text(
                "Lectura interna: ${run.status} · ${run.finishedAt?.shortDateTime() ?: run.startedAt?.shortDateTime() ?: "sin fecha"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AteneaTextButton(
                text = if (showDetails) "Ocultar detalle servidor" else "Ver detalle servidor",
                onClick = { showDetails = !showDetails }
            )
            if (showDetails) {
                OperationRunSummary(run)
            }
        }
    }
}

@Composable
private fun WebsitePanel(status: OperationsHostStatus) {
    if (status.websiteChecks.isEmpty()) {
        return
    }
    var showDetails by remember(status.websiteChecks.joinToString { "${it.websiteId}:${it.durationMillis}:${it.healthy}" }) {
        mutableStateOf(status.unhealthyWebsites > 0)
    }
    val slowestWebsite = status.websiteChecks.maxByOrNull { it.durationMillis }
    AteneaPanel {
        Text("Webs", style = MaterialTheme.typography.titleMedium)
        Text(
            if (status.unhealthyWebsites == 0) {
                "Todas responden correctamente. Más lenta: ${slowestWebsite?.name ?: "-"} · ${slowestWebsite?.durationMillis ?: 0} ms."
            } else if (status.downWebsites == 0) {
                "${status.degradedWebsites} webs responden lentas."
            } else {
                "${status.downWebsites} webs caídas y ${status.degradedWebsites} lentas."
            },
            style = MaterialTheme.typography.bodySmall
        )
        AteneaTextButton(
            text = if (showDetails) "Ocultar webs" else "Ver webs",
            onClick = { showDetails = !showDetails }
        )
        if (!showDetails) {
            return@AteneaPanel
        }
        status.websiteChecks
            .sortedWith(compareBy<WebsiteCheck> { it.healthy }.thenByDescending { it.durationMillis })
            .forEach { check ->
            val level = when {
                check.healthy -> OperationalLevel.OK
                check.state.equals("DEGRADED", ignoreCase = true) -> OperationalLevel.WARNING
                else -> OperationalLevel.CRITICAL
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(check.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${check.statusCode ?: "-"} · ${check.durationMillis} ms · ${check.state}",
                    color = if (check.healthy) {
                        MaterialTheme.colorScheme.onSurface
                    } else if (level == OperationalLevel.WARNING) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            check.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OperationRunSummary(run: OperationsActionRun) {
    Spacer(modifier = Modifier.height(2.dp))
    Text("Última revisión: ${run.status} · ${run.finishedAt ?: run.startedAt ?: "sin fecha"}", style = MaterialTheme.typography.bodySmall)
    val report = run.report
    if (report != null) {
        report.summary?.let {
            Text("Resultado: $it", style = MaterialTheme.typography.bodySmall)
        }
        if (report.summary == null && report.status != null) {
            Text("Script: ${report.status}", style = MaterialTheme.typography.bodySmall)
        }
        if (report.steps.isNotEmpty()) {
            Text("Qué ha hecho", style = MaterialTheme.typography.labelLarge)
            report.steps.forEach { step ->
                Text(
                    "${step.name.displayLabel()}: ${step.detail ?: step.status ?: "sin detalle"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (report.metrics.isNotEmpty()) {
            Text("Datos", style = MaterialTheme.typography.labelLarge)
            report.metrics.entries.take(8).forEach { entry ->
                Text("${entry.key.displayLabel()}: ${entry.value}", style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        run.stdoutSummary
            ?.takeUnless { it.looksLikeJson() }
            ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    run.stderrSummary?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun IncidentPanel(incident: OperationsIncident) {
    AteneaPanel {
        Text("#${incident.id} · ${incident.severity} · ${incident.status}", style = MaterialTheme.typography.labelLarge)
        Text(incident.title)
        incident.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            listOfNotNull(incident.hostName, incident.serviceName, incident.lastActivityAt).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

internal fun OperationsHostStatus?.operationalLevel(loading: Boolean = false): OperationalLevel = when {
    loading -> OperationalLevel.UNKNOWN
    this == null -> OperationalLevel.UNKNOWN
    openIncidents.any { it.severity == "CRITICAL" } || downWebsites > 0 -> OperationalLevel.CRITICAL
    openIncidents.isNotEmpty() || degradedWebsites > 0 -> OperationalLevel.WARNING
    websiteChecks.isEmpty() || hostStatusRun?.status == "FAILED" -> OperationalLevel.WARNING
    else -> OperationalLevel.OK
}

internal fun OperationsHostStatus.oneLineSummary(): String =
    "${services.count { it.active }} servicios · ${healthyWebsites}/${websiteChecks.size} webs OK · ${degradedWebsites} lentas · ${openIncidents.size} incidencias"

private fun OperationsHostStatus.websiteLevel(): OperationalLevel = when {
    websiteChecks.isEmpty() -> OperationalLevel.WARNING
    downWebsites > 0 -> OperationalLevel.CRITICAL
    degradedWebsites > 0 -> OperationalLevel.WARNING
    else -> OperationalLevel.OK
}

private fun OperationsHostStatus.incidentLevel(): OperationalLevel =
    if (openIncidents.isEmpty()) OperationalLevel.OK else OperationalLevel.CRITICAL

private fun CoreCommandResponse.isSuccessfulApacheCommand(request: CoreCommandRequestState?): Boolean {
    val input = request?.input.orEmpty().lowercase()
    return status == "SUCCEEDED" && input.contains("apache")
}

@Composable
private fun ValidationLine(label: String, ok: Boolean) {
    MetricLine(label, if (ok) "OK" else "pendiente", if (ok) OperationalLevel.OK else OperationalLevel.WARNING)
}

private fun serviceLevel(active: Boolean?): OperationalLevel = when (active) {
    true -> OperationalLevel.OK
    false -> OperationalLevel.CRITICAL
    null -> OperationalLevel.UNKNOWN
}

private fun websiteResultLabel(status: OperationsHostStatus?): String {
    if (status == null || status.websiteChecks.isEmpty()) {
        return "sin datos"
    }
    return if (status.degradedWebsites > 0 || status.downWebsites > 0) {
        "${status.healthyWebsites}/${status.websiteChecks.size} OK · ${status.degradedWebsites} lentas · ${status.downWebsites} caídas"
    } else {
        "${status.healthyWebsites}/${status.websiteChecks.size} OK"
    }
}

private fun recoveryConclusion(
    beforeProcesses: Int?,
    afterProcesses: Int?,
    leftoversAfterStop: Int?,
    killed: Int?
): String {
    val base = if (beforeProcesses != null && afterProcesses != null) {
        "Se reinició apache2 correctamente. Antes había $beforeProcesses procesos; después quedaron $afterProcesses activos."
    } else {
        "Se reinició apache2 correctamente y la validación posterior no detecta incidencias."
    }
    val leftovers = when {
        leftoversAfterStop == 0 && (killed == null || killed == 0) -> " No fue necesario matar procesos manualmente."
        killed != null && killed > 0 -> " Se mataron $killed procesos que seguían colgados."
        leftoversAfterStop != null && leftoversAfterStop > 0 -> " Quedaban $leftoversAfterStop procesos tras parar el servicio."
        else -> ""
    }
    return base + leftovers
}

private fun String.metricInt(key: String): Int? {
    val labels = listOf(key, key.displayLabel())
    return labels.firstNotNullOfOrNull { label ->
        Regex("${Regex.escape(label)}\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }
}

private fun String.shortDateTime(): String =
    replace('T', ' ')
        .removeSuffix("Z")
        .take(16)
