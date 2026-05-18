package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileApiCostsOverview
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
internal fun CostsScreen(
    apiClient: AteneaApiClient
) {
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf<MobileApiCostsOverview?>(null) }
    var selectedRange by remember { mutableStateOf(CostRange.LAST_30_DAYS) }
    var customStart by remember { mutableStateOf(LocalDate.now().minusDays(29).toString()) }
    var customEnd by remember { mutableStateOf(LocalDate.now().toString()) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh(range: CostRange = selectedRange) {
        scope.launch {
            pending = true
            error = null
            try {
                overview = if (range == CostRange.CUSTOM) {
                    apiClient.fetchApiCostsOverview(customStart.trim(), customEnd.trim())
                } else {
                    val dates = range.resolveDates()
                    apiClient.fetchApiCostsOverview(dates.first.toString(), dates.second.toString())
                }
            } catch (refreshError: Exception) {
                error = refreshError.message ?: "No se pudieron cargar los costes."
            } finally {
                pending = false
            }
        }
    }

    fun selectRange(range: CostRange) {
        selectedRange = range
        if (range != CostRange.CUSTOM) {
            val dates = range.resolveDates()
            customStart = dates.first.toString()
            customEnd = dates.second.toString()
            refresh(range)
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
            MetricLine("Total", overview?.total?.formatMoney(overview?.currency ?: "usd") ?: "-")
            MetricLine("Periodo", overview?.let { "${it.startAt.shortDate()} - ${it.endAt.exclusiveEndDate()}" } ?: selectedRange.label)
            overview?.generatedAt?.let { MetricLine("Actualizado", it.formatDateTimeForDisplay()) }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CostRangeButton("Hoy", selectedRange == CostRange.TODAY, Modifier.weight(1f)) { selectRange(CostRange.TODAY) }
                    CostRangeButton("Ayer", selectedRange == CostRange.YESTERDAY, Modifier.weight(1f)) { selectRange(CostRange.YESTERDAY) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CostRangeButton("7 dias", selectedRange == CostRange.LAST_7_DAYS, Modifier.weight(1f)) { selectRange(CostRange.LAST_7_DAYS) }
                    CostRangeButton("30 dias", selectedRange == CostRange.LAST_30_DAYS, Modifier.weight(1f)) { selectRange(CostRange.LAST_30_DAYS) }
                }
            }

            Text("Rango manual", style = MaterialTheme.typography.labelLarge)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customStart,
                    onValueChange = {
                        customStart = it
                        selectedRange = CostRange.CUSTOM
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Desde") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = customEnd,
                    onValueChange = {
                        customEnd = it
                        selectedRange = CostRange.CUSTOM
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Hasta") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
            AteneaOutlinedButton(
                text = if (pending) "Actualizando..." else "Actualizar",
                enabled = !pending,
                onClick = { refresh() }
            )
        }

        overview?.providers?.let { providers ->
            AteneaPanel {
                Text("Facturacion real", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Coste reconciliable por proveedor. El detalle tecnico queda separado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                providers.forEach { provider ->
                    MetricLine(
                        label = provider.provider.uppercase(),
                        value = if (provider.configured) {
                            provider.total.formatMoney(provider.currency)
                        } else {
                            "Sin configurar"
                        }
                    )
                    provider.modelTotals
                        .filter { it.amount > 0.0 }
                        .take(6)
                        .forEach { model ->
                            MetricLine("  ${model.model}", model.amount.formatMoney(model.currency))
                        }
                }
            }
        }

        overview?.codexAuthStatuses?.takeIf { it.isNotEmpty() }?.let { statuses ->
            AteneaPanel {
                Text("Codex App Server", style = MaterialTheme.typography.titleMedium)
                statuses.forEach { status ->
                    MetricLine(
                        status.server.replaceFirstChar { it.titlecase() },
                        if (status.compliant) "ChatGPT activo" else "Bloqueado: ${status.status}"
                    )
                    MetricLine("Modo ${status.server}", status.authMode ?: "-")
                    if (status.apiKeyPresent) {
                        Text(
                            "Alerta: hay API key en auth de Codex.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        overview?.usageSummaries
            ?.filter { it.hasMeaningfulUsage() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { summaries ->
            AteneaPanel {
                Text("Uso tecnico OpenAI", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Solo se muestran categorias con actividad en el periodo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                summaries.forEach { summary ->
                    Text(summary.usageType.readableUsageType(), style = MaterialTheme.typography.labelLarge)
                    MetricLine("Estado", summary.status)
                    MetricLine("Requests", summary.requests.toString())
                    MetricLine("Input", summary.inputTokens.compactNumber())
                    MetricLine("Cache", summary.cachedInputTokens.compactNumber())
                    MetricLine("Output", summary.outputTokens.compactNumber())
                    if (summary.inputAudioTokens > 0 || summary.outputAudioTokens > 0) {
                        MetricLine("Audio tokens", "${summary.inputAudioTokens.compactNumber()} / ${summary.outputAudioTokens.compactNumber()}")
                    }
                    if (summary.characters > 0) {
                        MetricLine("Caracteres", summary.characters.compactNumber())
                    }
                    summary.lines.take(8).forEach { line ->
                        val owner = line.apiKeyName ?: line.projectName ?: line.projectId ?: "sin origen"
                        MetricLine(
                            "${line.model} · $owner",
                            "req ${line.requests}, in ${line.inputTokens.compactNumber()}, out ${line.outputTokens.compactNumber()}"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CostRangeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        AteneaButton(text = label, modifier = modifier, onClick = onClick)
    } else {
        AteneaOutlinedButton(text = label, modifier = modifier, onClick = onClick)
    }
}

private enum class CostRange(val label: String) {
    TODAY("Hoy"),
    YESTERDAY("Ayer"),
    LAST_7_DAYS("Ultimos 7 dias"),
    LAST_30_DAYS("Ultimos 30 dias"),
    CUSTOM("Rango manual");

    fun resolveDates(): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        return when (this) {
            TODAY -> today to today
            YESTERDAY -> today.minusDays(1) to today.minusDays(1)
            LAST_7_DAYS -> today.minusDays(6) to today
            LAST_30_DAYS -> today.minusDays(29) to today
            CUSTOM -> today.minusDays(29) to today
        }
    }
}

private fun Double.formatMoney(currency: String): String =
    "%.4f %s".format(this, currency.uppercase())

private fun Long.compactNumber(): String =
    "%,d".format(this)

private fun com.atenea.android.api.MobileApiUsageSummary.hasMeaningfulUsage(): Boolean =
    requests > 0 ||
        inputTokens > 0 ||
        cachedInputTokens > 0 ||
        outputTokens > 0 ||
        inputAudioTokens > 0 ||
        outputAudioTokens > 0 ||
        characters > 0 ||
        lines.any {
            it.requests > 0 ||
                it.inputTokens > 0 ||
                it.cachedInputTokens > 0 ||
                it.outputTokens > 0 ||
                it.inputAudioTokens > 0 ||
                it.outputAudioTokens > 0 ||
                it.characters > 0
        }

private fun String.readableUsageType(): String =
    when (this) {
        "completions" -> "Texto / Codex"
        "audio_transcriptions" -> "Transcripcion"
        "audio_speeches" -> "Voz TTS"
        else -> replace('_', ' ').replaceFirstChar { it.titlecase() }
    }

private fun String?.shortDate(): String =
    this?.substringBefore("T") ?: "-"

private fun String?.exclusiveEndDate(): String {
    val raw = this?.substringBefore("T") ?: return "-"
    return runCatching { LocalDate.parse(raw).minusDays(1).toString() }.getOrDefault(raw)
}
