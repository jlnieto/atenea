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
import com.atenea.android.api.MobileApiCostsOverview
import kotlinx.coroutines.launch

@Composable
internal fun CostsScreen(
    apiClient: AteneaApiClient
) {
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf<MobileApiCostsOverview?>(null) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            pending = true
            error = null
            try {
                overview = apiClient.fetchApiCostsOverview(days = 30)
            } catch (refreshError: Exception) {
                error = refreshError.message ?: "No se pudieron cargar los costes."
            } finally {
                pending = false
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
        error?.let { ErrorPanel(it) }

        AteneaPanel {
            Text("Costes API", style = MaterialTheme.typography.titleMedium)
            MetricLine("Periodo", overview?.let { "${it.startAt.shortDate()} - ${it.endAt.shortDate()}" } ?: "Últimos 30 días")
            overview?.generatedAt?.let { MetricLine("Actualizado", it.formatDateTimeForDisplay()) }
            AteneaOutlinedButton(
                text = if (pending) "Actualizando..." else "Actualizar",
                enabled = !pending,
                onClick = { refresh() }
            )
        }

        overview?.providers?.forEach { provider ->
            AteneaPanel {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(provider.provider.uppercase(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        provider.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MetricLine("Configurado", if (provider.configured) "Sí" else "No")
                MetricLine("Total", provider.total.formatMoney(provider.currency))
                if (provider.lines.isEmpty()) {
                    Text(
                        if (provider.configured) "Sin coste registrado en el periodo." else "Falta configurar la clave admin del proveedor.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    provider.lines.take(12).forEach { line ->
                        MetricLine(
                            label = line.projectId?.let { "${line.label} · $it" } ?: line.label,
                            value = line.amount.formatMoney(line.currency)
                        )
                    }
                }
            }
        }
    }
}

private fun Double.formatMoney(currency: String): String =
    "%.4f %s".format(this, currency.uppercase())

private fun String?.shortDate(): String =
    this?.substringBefore("T") ?: "-"
