package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.OperationsHostStatus
import kotlinx.coroutines.launch

@Composable
internal fun HealthScreen(
    apiClient: AteneaApiClient,
    onSnapshotChanged: (ShellHealthSnapshot) -> Unit
) {
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf<HealthOverview?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                val next = apiClient.fetchHealthOverview()
                overview = next
                onSnapshotChanged(next.snapshot)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val current = overview
        HealthSummaryPanel(current, loading)
        AteneaOutlinedButton(
            text = if (loading) "Actualizando..." else "Actualizar",
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            onClick = { refresh() }
        )

        current?.errors?.takeIf { it.isNotEmpty() }?.let { errors ->
            AteneaPanel {
                Text("Errores", style = MaterialTheme.typography.titleMedium)
                errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        current?.hosts?.forEach { host ->
            HealthHostPanel(host)
        }

        current?.incidents?.takeIf { it.isNotEmpty() }?.let { incidents ->
            AteneaPanel {
                Text("Incidencias abiertas", style = MaterialTheme.typography.titleMedium)
                incidents.forEach { incident ->
                    Text(incident.title, style = MaterialTheme.typography.bodyMedium)
                    incident.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun HealthSummaryPanel(overview: HealthOverview?, loading: Boolean) {
    val snapshot = overview?.snapshot ?: ShellHealthSnapshot.UNKNOWN
    AteneaPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Estado global", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        loading -> "Actualizando servidores y servicios."
                        snapshot.level == OperationalLevel.OK -> "${snapshot.totalHosts} servidores sin incidencias."
                        snapshot.level == OperationalLevel.CRITICAL -> "${snapshot.issueCount} puntos requieren revisión."
                        snapshot.level == OperationalLevel.WARNING -> "Hay datos pendientes de revisión."
                        else -> "Sin datos operativos."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            StatusPill(snapshot.level)
        }
    }
}

@Composable
private fun HealthHostPanel(status: OperationsHostStatus) {
    val hostStatusRun = status.hostStatusRun
    AteneaPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(status.host.name, style = MaterialTheme.typography.titleMedium)
                Text(status.oneLineSummary(), style = MaterialTheme.typography.bodySmall)
            }
            StatusPill(status.operationalLevel())
        }
        if (hostStatusRun?.status == "FAILED") {
            Text("Lectura de servidor incompleta", style = MaterialTheme.typography.labelLarge)
            Text(
                hostStatusRun.stderrSummary
                    ?: hostStatusRun.stdoutSummary
                    ?: "No se pudieron leer métricas internas del host en esta comprobación.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Las webs y las incidencias se muestran aparte; pulsa Actualizar para repetir la lectura.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (status.unhealthyWebsites > 0) {
            Text("Webs con problema", style = MaterialTheme.typography.labelLarge)
            status.websiteChecks.filter { !it.healthy }.forEach { website ->
                Text(
                    "${website.name}: ${website.statusCode ?: "-"} · ${website.error ?: "${website.durationMillis} ms"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (status.openIncidents.isNotEmpty()) {
            Text("Incidencias", style = MaterialTheme.typography.labelLarge)
            status.openIncidents.forEach { incident ->
                Text(incident.title, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
