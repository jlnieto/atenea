package com.atenea.android.coreconsole

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.atenea.android.api.MobileUploadTransferPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal fun FilesScreen(apiClient: AteneaApiClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastUpload by remember { mutableStateOf<MobileUpload?>(null) }
    var uploadProgress by remember { mutableStateOf<FileUploadProgress?>(null) }
    var uploadTrace by remember { mutableStateOf<FileUploadTrace?>(null) }
    var progressClockMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(uploading, uploadProgress?.phase) {
        while (uploading || uploadProgress?.phase == FileUploadPhase.WAITING_FOR_SERVER) {
            progressClockMs = SystemClock.elapsedRealtime()
            delay(250)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            uploading = true
            error = null
            lastUpload = null
            val operationStartedAt = SystemClock.elapsedRealtime()
            uploadTrace = FileUploadTrace(operationStartedAtMs = operationStartedAt)
            uploadProgress = FileUploadProgress(
                phase = FileUploadPhase.PREPARING,
                sentBytes = 0L,
                totalBytes = 0L,
                label = "Preparando fichero...",
                phaseStartedAtMs = operationStartedAt,
                updatedAtMs = operationStartedAt
            )
            try {
                val readStartedAt = SystemClock.elapsedRealtime()
                uploadTrace = uploadTrace?.copy(readStartedAtMs = readStartedAt)
                val selected = withContext(Dispatchers.IO) {
                    context.readUploadFile(uri) { readBytes, totalBytes ->
                        mainHandler.post {
                            val now = SystemClock.elapsedRealtime()
                            uploadProgress = FileUploadProgress(
                                phase = FileUploadPhase.READING,
                                sentBytes = readBytes,
                                totalBytes = totalBytes,
                                label = "Leyendo fichero local...",
                                phaseStartedAtMs = readStartedAt,
                                updatedAtMs = now
                            )
                            uploadTrace = uploadTrace?.copy(
                                fileSizeBytes = totalBytes.takeIf { it > 0L } ?: readBytes,
                                readBytes = readBytes
                            )
                        }
                    }
                }
                val readFinishedAt = SystemClock.elapsedRealtime()
                val uploadStartedAt = SystemClock.elapsedRealtime()
                uploadTrace = uploadTrace?.copy(
                    fileName = selected.fileName,
                    fileSizeBytes = selected.bytes.size.toLong(),
                    readFinishedAtMs = readFinishedAt,
                    httpStartedAtMs = uploadStartedAt
                )
                uploadProgress = FileUploadProgress(
                    phase = FileUploadPhase.UPLOADING,
                    sentBytes = 0L,
                    totalBytes = selected.bytes.size.toLong(),
                    label = "Enviando ${selected.fileName}",
                    phaseStartedAtMs = uploadStartedAt,
                    updatedAtMs = uploadStartedAt
                )
                lastUpload = apiClient.uploadMobileFile(
                    fileName = selected.fileName,
                    contentType = selected.contentType,
                    bytes = selected.bytes,
                    onProgress = { transfer ->
                        mainHandler.post {
                            val now = SystemClock.elapsedRealtime()
                            when (transfer.phase) {
                                MobileUploadTransferPhase.PREPARING_REQUEST -> {
                                    uploadTrace = uploadTrace?.copy(httpPreparedAtMs = now)
                                }
                                MobileUploadTransferPhase.WRITING_REQUEST -> {
                                    uploadTrace = uploadTrace?.copy(
                                        httpWriteStartedAtMs = uploadTrace?.httpWriteStartedAtMs ?: now,
                                        sentBytes = transfer.sentBytes
                                    )
                                    uploadProgress = FileUploadProgress(
                                        phase = FileUploadPhase.UPLOADING,
                                        sentBytes = transfer.sentBytes,
                                        totalBytes = transfer.totalBytes,
                                        label = "Enviando ${selected.fileName}",
                                        phaseStartedAtMs = uploadTrace?.httpWriteStartedAtMs ?: uploadStartedAt,
                                        updatedAtMs = now
                                    )
                                }
                                MobileUploadTransferPhase.WAITING_RESPONSE -> {
                                    uploadTrace = uploadTrace?.copy(
                                        httpBodySentAtMs = uploadTrace?.httpBodySentAtMs ?: now,
                                        responseWaitStartedAtMs = now,
                                        sentBytes = transfer.sentBytes
                                    )
                                    uploadProgress = FileUploadProgress(
                                        phase = FileUploadPhase.WAITING_FOR_SERVER,
                                        sentBytes = transfer.sentBytes,
                                        totalBytes = transfer.totalBytes,
                                        label = "Fichero enviado. Esperando confirmación de Atenea...",
                                        phaseStartedAtMs = now,
                                        updatedAtMs = now
                                    )
                                }
                                MobileUploadTransferPhase.RESPONSE_RECEIVED -> {
                                    uploadTrace = uploadTrace?.copy(responseReceivedAtMs = now)
                                }
                            }
                        }
                    }
                )
                val finishedAt = SystemClock.elapsedRealtime()
                uploadTrace = uploadTrace?.copy(
                    finishedAtMs = finishedAt,
                    backendTotalMs = lastUpload?.telemetry?.backendTotalMs,
                    backendEnsureDirectoryMs = lastUpload?.telemetry?.backendEnsureDirectoryMs,
                    backendCopyMs = lastUpload?.telemetry?.backendCopyMs,
                    backendPermissionsMs = lastUpload?.telemetry?.backendPermissionsMs,
                    backendMetadataMs = lastUpload?.telemetry?.backendMetadataMs
                )
                uploadProgress = uploadProgress?.copy(
                    phase = FileUploadPhase.COMPLETED,
                    sentBytes = uploadProgress?.totalBytes ?: selected.bytes.size.toLong(),
                    label = "Subida completada.",
                    updatedAtMs = SystemClock.elapsedRealtime()
                )
            } catch (uploadError: Exception) {
                error = uploadError.message ?: "No se pudo subir el fichero."
            } finally {
                uploading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AteneaSpacing.medium)
    ) {
        AteneaPanel {
            Text("Subidas", style = MaterialTheme.typography.titleMedium)
            Text(
                "Los ficheros quedan en /srv/atenea/workspace/repos/internal/atenea/operator-uploads.",
                style = MaterialTheme.typography.bodySmall
            )
            AteneaButton(
                text = if (uploading) "Subiendo..." else "Seleccionar fichero",
                enabled = !uploading,
                onClick = { picker.launch(arrayOf("*/*")) }
            )
            uploadProgress?.let { progress ->
                Text(progress.label, style = MaterialTheme.typography.bodySmall)
                if (progress.phase.showsDeterminateProgress) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    progress.detail(progressClockMs),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            uploadTrace?.let { trace ->
                Text("Resumen de subida", style = MaterialTheme.typography.titleSmall)
                trace.summaryLines(progressClockMs).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        error?.let { ErrorPanel(it) }

        lastUpload?.let { upload ->
            AteneaPanel {
                Text("Último fichero", style = MaterialTheme.typography.titleMedium)
                MetricLine("Nombre", upload.originalFilename)
                MetricLine("Tipo", upload.contentType)
                MetricLine("Tamaño", "${upload.sizeBytes} bytes")
                MetricLine("Ruta", upload.storedPath)
                MetricLine("Índice", upload.latestMetadataPath)
            }
        }
    }
}

private data class SelectedUploadFile(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray
)

private data class FileUploadTrace(
    val operationStartedAtMs: Long,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0L,
    val readBytes: Long = 0L,
    val sentBytes: Long = 0L,
    val readStartedAtMs: Long? = null,
    val readFinishedAtMs: Long? = null,
    val httpStartedAtMs: Long? = null,
    val httpPreparedAtMs: Long? = null,
    val httpWriteStartedAtMs: Long? = null,
    val httpBodySentAtMs: Long? = null,
    val responseWaitStartedAtMs: Long? = null,
    val responseReceivedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val backendTotalMs: Long? = null,
    val backendEnsureDirectoryMs: Long? = null,
    val backendCopyMs: Long? = null,
    val backendPermissionsMs: Long? = null,
    val backendMetadataMs: Long? = null
) {
    fun summaryLines(nowMs: Long): List<String> {
        val end = finishedAtMs ?: nowMs
        val readMs = elapsed(readStartedAtMs, readFinishedAtMs)
        val requestPrepMs = elapsed(httpStartedAtMs, httpWriteStartedAtMs ?: httpPreparedAtMs)
        val writeMs = elapsed(httpWriteStartedAtMs, httpBodySentAtMs)
        val waitMs = elapsed(responseWaitStartedAtMs ?: httpBodySentAtMs, responseReceivedAtMs ?: finishedAtMs ?: nowMs)
        val totalMs = end - operationStartedAtMs
        val writeSpeed = if (writeMs != null && writeMs > 0L && sentBytes > 0L) {
            " · ${formatUploadBytes((sentBytes * 1000L) / writeMs)}/s"
        } else {
            ""
        }
        return buildList {
            fileName?.let { add("Fichero: $it · ${formatUploadBytes(fileSizeBytes)}") }
            add("Total móvil: ${formatDurationMs(totalMs)}")
            add("Lectura local: ${formatStageDuration(readMs)} · ${formatUploadBytes(readBytes)} leídos")
            add("Preparación HTTP: ${formatStageDuration(requestPrepMs)}")
            add("Envío cuerpo HTTP: ${formatStageDuration(writeMs)} · ${formatUploadBytes(sentBytes)} enviados$writeSpeed")
            add("Espera respuesta servidor: ${formatStageDuration(waitMs)}")
            backendTotalMs?.let { add("Backend total: ${formatDurationMs(it)}") }
            backendEnsureDirectoryMs?.let { add("Backend directorios: ${formatDurationMs(it)}") }
            backendCopyMs?.let { add("Backend copia fichero: ${formatDurationMs(it)}") }
            backendPermissionsMs?.let { add("Backend permisos: ${formatDurationMs(it)}") }
            backendMetadataMs?.let { add("Backend metadatos: ${formatDurationMs(it)}") }
            if (waitMs != null && backendTotalMs != null) {
                val unobserved = (waitMs - backendTotalMs).coerceAtLeast(0L)
                add("Red/proxy/Spring multipart no instrumentado: ${formatDurationMs(unobserved)}")
            }
        }
    }

    private fun elapsed(start: Long?, end: Long?): Long? =
        if (start == null || end == null) null else (end - start).coerceAtLeast(0L)
}

private enum class FileUploadPhase(val showsDeterminateProgress: Boolean) {
    PREPARING(false),
    READING(true),
    UPLOADING(true),
    WAITING_FOR_SERVER(false),
    COMPLETED(true)
}

private data class FileUploadProgress(
    val phase: FileUploadPhase,
    val sentBytes: Long,
    val totalBytes: Long,
    val label: String,
    val phaseStartedAtMs: Long,
    val updatedAtMs: Long
) {
    val fraction: Float = if (totalBytes > 0L) (sentBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val percent: Int = (fraction * 100).toInt()
    fun detail(nowMs: Long): String {
        val elapsedMs = (nowMs - phaseStartedAtMs).coerceAtLeast(0L)
        val speed = if (elapsedMs > 0L && sentBytes > 0L) {
            " · ${formatUploadBytes((sentBytes * 1000L) / elapsedMs)}/s"
        } else {
            ""
        }
        return when (phase) {
            FileUploadPhase.PREPARING -> "Preparando selector y metadatos."
            FileUploadPhase.READING ->
                "${formatUploadBytes(sentBytes)} / ${formatUploadBytes(totalBytes)} · $percent% · lectura local · ${formatDurationMs(elapsedMs)}$speed"
            FileUploadPhase.UPLOADING ->
                "${formatUploadBytes(sentBytes)} / ${formatUploadBytes(totalBytes)} · $percent% · envío HTTP · ${formatDurationMs(elapsedMs)}$speed"
            FileUploadPhase.WAITING_FOR_SERVER ->
                "${formatUploadBytes(totalBytes)} enviados · esperando respuesta del servidor · ${formatDurationMs(elapsedMs)}"
            FileUploadPhase.COMPLETED ->
                "${formatUploadBytes(sentBytes)} / ${formatUploadBytes(totalBytes)} · 100% · confirmado"
        }
    }
}

private fun Context.readUploadFile(
    uri: Uri,
    onProgress: (readBytes: Long, totalBytes: Long) -> Unit
): SelectedUploadFile {
    val contentType = contentResolver.getType(uri) ?: "application/octet-stream"
    val fileName = resolveDisplayName(uri) ?: "upload.bin"
    val totalBytes = resolveSize(uri)
    val bytes = contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var readBytes = 0L
        onProgress(0L, totalBytes)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
            readBytes += read
            onProgress(readBytes, totalBytes.takeIf { it > 0L } ?: readBytes)
        }
        output.toByteArray()
    } ?: throw IllegalStateException("No se pudo leer el fichero seleccionado.")
    return SelectedUploadFile(fileName, contentType, bytes)
}

private fun formatUploadBytes(bytes: Long): String {
    if (bytes <= 0L) {
        return "0 B"
    }
    val kib = 1024.0
    val mib = kib * 1024.0
    return when {
        bytes >= mib -> String.format("%.1f MB", bytes / mib)
        bytes >= kib -> String.format("%.1f KB", bytes / kib)
        else -> "$bytes B"
    }
}

private fun formatDurationMs(ms: Long): String {
    val seconds = ms / 1000
    val tenths = (ms % 1000) / 100
    return "${seconds}.${tenths}s"
}

private fun formatStageDuration(ms: Long?): String =
    ms?.let(::formatDurationMs) ?: "pendiente"

private fun Context.resolveDisplayName(uri: Uri): String? {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                return cursor.getString(index)
            }
        }
    }
    return uri.lastPathSegment
}

private fun Context.resolveSize(uri: Uri): Long {
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0) {
                return cursor.getLong(index).coerceAtLeast(0L)
            }
        }
    }
    return 0L
}
