package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileUpload
import com.atenea.android.voiceruntime.AteneaDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun DiagnosticsScreen(
    apiClient: AteneaApiClient
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(AteneaDiagnostics.runtimeSnapshot(context)) }
    var diagnosticUploading by remember { mutableStateOf(false) }
    var diagnosticMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticUpload by remember { mutableStateOf<MobileUpload?>(null) }

    fun refreshSnapshot() {
        snapshot = AteneaDiagnostics.runtimeSnapshot(context)
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshSnapshot()
            delay(3000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AteneaSpacing.medium)
    ) {
        AteneaPanel {
            Text("Recursos", style = MaterialTheme.typography.titleMedium)
            RuntimeMeter("Heap app", snapshot.heapUsageRatio, snapshot.heapUsedBytes, snapshot.heapMaxBytes)
            RuntimeMeter(
                label = "Memoria sistema",
                fraction = snapshot.systemUsageRatio,
                usedBytes = snapshot.systemTotalBytes - snapshot.systemAvailableBytes,
                totalBytes = snapshot.systemTotalBytes
            )
            MetricLine("Estado Android", if (snapshot.systemLowMemory) "Presion de memoria" else "Normal")
            MetricLine("Clase memoria app", "${snapshot.memoryClassMb} MB")
            MetricLine("Clase memoria grande", "${snapshot.largeMemoryClassMb} MB")
            MetricLine("Ultima lectura", snapshot.generatedAt.formatDateTimeForDisplay())
            AteneaOutlinedButton(text = "Actualizar metricas", onClick = ::refreshSnapshot)
        }

        AteneaPanel {
            Text("Proceso", style = MaterialTheme.typography.titleMedium)
            MetricLine("Heap reservado", snapshot.heapTotalBytes.formatBytes())
            MetricLine("Heap libre reservado", snapshot.heapFreeBytes.formatBytes())
            MetricLine("Heap nativo usado", snapshot.nativeHeapAllocatedBytes.formatBytes())
            MetricLine("Heap nativo reservado", snapshot.nativeHeapSizeBytes.formatBytes())
            MetricLine("Hilos activos", snapshot.activeThreads.toString())
            MetricLine("Procesadores", snapshot.availableProcessors.toString())
            MetricLine("CPU proceso", snapshot.processCpuTimeMs.formatDurationMs())
            MetricLine("Uptime dispositivo", snapshot.deviceUptimeMs.formatDurationMs())
        }

        AteneaPanel {
            Text("Almacenamiento", style = MaterialTheme.typography.titleMedium)
            MetricLine("Cache disponible", snapshot.cacheUsableBytes.formatBytes())
            MetricLine("Datos app disponibles", snapshot.filesUsableBytes.formatBytes())
        }

        CrashPanel()

        AteneaPanel {
            Text("Informe", style = MaterialTheme.typography.titleMedium)
            Text(
                "Incluye memoria, dispositivo, ultimo crash, razones de cierre del proceso y eventos recientes.",
                style = MaterialTheme.typography.bodySmall
            )
            AteneaButton(
                text = if (diagnosticUploading) "Enviando..." else "Enviar diagnostico",
                enabled = !diagnosticUploading,
                onClick = {
                    scope.launch {
                        diagnosticUploading = true
                        diagnosticMessage = null
                        diagnosticUpload = null
                        try {
                            AteneaDiagnostics.info("diagnostics", "manual_report_requested")
                            val report = AteneaDiagnostics.createReport("manual_diagnostics_upload")
                            diagnosticUpload = apiClient.uploadMobileFile(
                                fileName = report.fileName,
                                contentType = report.contentType,
                                bytes = report.bytes
                            )
                            diagnosticMessage = "Diagnostico subido."
                        } catch (error: Exception) {
                            diagnosticMessage = error.message ?: "No se pudo enviar el diagnostico."
                        } finally {
                            diagnosticUploading = false
                        }
                    }
                }
            )
            diagnosticMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            diagnosticUpload?.let { upload ->
                MetricLine("Fichero", upload.originalFilename)
                MetricLine("Ruta", upload.storedPath)
            }
        }
    }
}

@Composable
private fun RuntimeMeter(
    label: String,
    fraction: Float,
    usedBytes: Long,
    totalBytes: Long
) {
    val safeFraction = fraction.coerceIn(0f, 1f)
    Text(label, style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(
        progress = { safeFraction },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "${usedBytes.formatBytes()} / ${totalBytes.formatBytes()} (${(safeFraction * 100).toInt()}%)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CrashPanel() {
    val context = LocalContext.current
    val lastCrash = remember { AteneaDiagnostics.lastCrashSnapshot(context) }
    val latestExit = remember { AteneaDiagnostics.latestProcessExitSnapshot(context) }
    AteneaPanel {
        Text("Cierres", style = MaterialTheme.typography.titleMedium)
        if (lastCrash == null) {
            MetricLine("Ultimo crash capturado", "Sin datos")
        } else {
            MetricLine("Ultimo crash", lastCrash.time.formatDateTimeForDisplay())
            MetricLine("Excepcion", lastCrash.exception)
            MetricLine("Hilo", lastCrash.thread)
            lastCrash.message?.let { MetricLine("Mensaje", it.take(80)) }
        }
        if (latestExit == null) {
            MetricLine("Ultima salida Android", "Sin datos")
        } else {
            MetricLine("Ultima salida Android", latestExit.reason)
            MetricLine("Fecha", latestExit.timestamp.formatDateTimeForDisplay())
            latestExit.description?.let { MetricLine("Descripcion", it.take(80)) }
            if (latestExit.pssBytes > 0L) {
                MetricLine("PSS aprox.", (latestExit.pssBytes * 1024L).formatBytes())
            }
            if (latestExit.rssBytes > 0L) {
                MetricLine("RSS aprox.", (latestExit.rssBytes * 1024L).formatBytes())
            }
        }
    }
}

private fun Long.formatDurationMs(): String {
    val totalSeconds = this / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
