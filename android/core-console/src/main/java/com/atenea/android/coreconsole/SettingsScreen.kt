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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.OperatorSessionInventory
import com.atenea.android.api.OperatorSessionState
import com.atenea.android.api.TotpEnrollment
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(
    apiClient: AteneaApiClient,
    updateState: UpdateCheckResult?,
    updateMessage: String?,
    updateProgress: UpdateDownloadProgress?,
    checkingUpdate: Boolean,
    currentVersionCode: Int,
    currentVersionName: String,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AteneaUpdateManifest) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val passkey = remember(context) { AndroidPasskeyCoordinator(context) }
    var sessions by remember { mutableStateOf<List<OperatorSessionInventory>>(emptyList()) }
    var sessionLoading by remember { mutableStateOf(true) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var factorMessage by remember { mutableStateOf<String?>(null) }
    var totpEnrollment by remember { mutableStateOf<TotpEnrollment?>(null) }
    var totpCode by remember { mutableStateOf("") }
    var recoveryCodes by remember { mutableStateOf<List<String>>(emptyList()) }

    fun loadSessions() {
        scope.launch {
            sessionLoading = true
            sessionError = null
            try {
                sessions = apiClient.fetchOperatorSessions()
            } catch (error: Exception) {
                sessionError = error.message ?: "No se pudo cargar el inventario."
            } finally {
                sessionLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadSessions() }
    val latestManifest = when (updateState) {
        is UpdateCheckResult.Available -> updateState.update
        is UpdateCheckResult.UpToDate -> updateState.latest
        else -> null
    }
    val previousRelease = latestManifest?.previousRelease

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AteneaSpacing.medium)
    ) {
        AteneaPanel {
            Text("Seguridad", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sesión familiar con refresh coordinado y revocación remota.",
                style = MaterialTheme.typography.bodyMedium
            )
            MetricLine(
                "Passkey",
                if (passkey.availability == PasskeyAvailability.AVAILABLE) {
                    "Disponible"
                } else {
                    "No disponible en Android 8"
                }
            )
            MetricLine("TOTP", "Respaldo opcional; alta real requiere H11")
            factorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (totpEnrollment == null) {
                AteneaOutlinedButton(
                    text = "Preparar TOTP",
                    onClick = {
                        scope.launch {
                            runCatching { apiClient.beginTotpEnrollment() }
                                .onSuccess {
                                    totpEnrollment = it
                                    factorMessage = "Alta pendiente: verifica un código de 6 dígitos."
                                }
                                .onFailure { factorMessage = it.message }
                        }
                    }
                )
            } else {
                Text("Secreto de alta (una sola fase pendiente)", style = MaterialTheme.typography.bodySmall)
                Text(totpEnrollment!!.secret, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = totpCode,
                    onValueChange = { totpCode = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Código TOTP") },
                    singleLine = true
                )
                AteneaButton(
                    text = "Activar TOTP",
                    enabled = totpCode.length == 6,
                    onClick = {
                        val enrollment = totpEnrollment ?: return@AteneaButton
                        scope.launch {
                            runCatching { apiClient.activateTotpEnrollment(enrollment.enrollmentId, totpCode) }
                                .onSuccess {
                                    recoveryCodes = it
                                    totpEnrollment = null
                                    totpCode = ""
                                    factorMessage = "TOTP activo. Guarda los códigos; se muestran una sola vez."
                                }
                                .onFailure { factorMessage = it.message }
                        }
                    }
                )
                AteneaOutlinedButton(
                    text = "Cancelar alta",
                    onClick = {
                        val enrollment = totpEnrollment ?: return@AteneaOutlinedButton
                        scope.launch {
                            runCatching { apiClient.cancelTotpEnrollment(enrollment.enrollmentId) }
                            totpEnrollment = null
                            totpCode = ""
                        }
                    }
                )
            }
            if (recoveryCodes.isNotEmpty()) {
                Text("Códigos de recuperación", style = MaterialTheme.typography.titleSmall)
                recoveryCodes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                AteneaButton(text = "Ya los he guardado", onClick = { recoveryCodes = emptyList() })
            }
        }

        AteneaPanel {
            Text("Dispositivos con acceso", style = MaterialTheme.typography.titleMedium)
            when {
                sessionLoading -> Text("Cargando inventario seguro…")
                sessionError != null -> Text(sessionError.orEmpty(), color = MaterialTheme.colorScheme.error)
                sessions.isEmpty() -> Text("No hay sesiones familiares activas.")
                else -> sessions.forEach { item ->
                    Text(
                        if (item.current) "${item.deviceLabel} · Esta sesión" else item.deviceLabel,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "${item.clientType} · ${item.state} · ${item.lastUsedAt.formatDateTimeForDisplay()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!item.current && item.state == OperatorSessionState.ACTIVE) {
                        AteneaOutlinedButton(
                            text = "Revocar",
                            onClick = {
                                scope.launch {
                                    runCatching { apiClient.revokeOperatorSession(item.familyId) }
                                        .onFailure { sessionError = it.message }
                                    loadSessions()
                                }
                            }
                        )
                    }
                }
            }
            if (sessions.any { !it.current && it.state == OperatorSessionState.ACTIVE }) {
                AteneaOutlinedButton(
                    text = "Cerrar las demás sesiones",
                    onClick = {
                        scope.launch {
                            runCatching { apiClient.revokeOtherOperatorSessions() }
                                .onFailure { sessionError = it.message }
                            loadSessions()
                        }
                    }
                )
            }
        }

        AteneaPanel {
            Text("App", style = MaterialTheme.typography.titleMedium)
            MetricLine("Version name", currentVersionName)
            MetricLine("Version code", currentVersionCode.toString())
        }

        AteneaPanel {
            Text("Actualizaciones", style = MaterialTheme.typography.titleMedium)
            when (updateState) {
                is UpdateCheckResult.Available -> {
                    Text("Disponible ${updateState.update.versionName}.", style = MaterialTheme.typography.bodyMedium)
                    MetricLine("Version code", updateState.update.versionCode.toString())
                    updateState.update.sizeBytes?.let { MetricLine("Tamaño", it.formatBytes()) }
                    updateState.update.createdAt?.let { MetricLine("Publicada", it.formatDateTimeForDisplay()) }
                    updateState.update.sha256?.let { MetricLine("SHA-256", it.take(12) + "…") }
                    updateProgress?.let { progress ->
                        val fraction = progress.fraction
                        if (fraction == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { fraction })
                        }
                        Text(
                            "${progress.downloadedBytes.formatBytes()} / ${progress.totalBytes?.formatBytes() ?: "desconocido"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    AteneaButton(
                        text = if (checkingUpdate) "Preparando..." else "Instalar",
                        enabled = !checkingUpdate,
                        onClick = { onInstallUpdate(updateState.update) }
                    )
                }
                is UpdateCheckResult.UpToDate -> {
                    Text("La app está al día.")
                    MetricLine("Última versión", updateState.latest.versionName)
                    updateState.latest.sizeBytes?.let { MetricLine("Tamaño APK", it.formatBytes()) }
                    updateState.latest.createdAt?.let { MetricLine("Publicada", it.formatDateTimeForDisplay()) }
                }
                is UpdateCheckResult.Unavailable -> Text(updateState.reason)
                null -> Text("Sin comprobación reciente.")
            }
            updateMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            AteneaOutlinedButton(
                text = if (checkingUpdate) "Comprobando..." else "Comprobar",
                enabled = !checkingUpdate,
                onClick = onCheckUpdate
            )
            previousRelease?.let { release ->
                Text("Version anterior archivada ${release.versionName}.", style = MaterialTheme.typography.bodySmall)
                release.sizeBytes?.let { MetricLine("Tamaño anterior", it.formatBytes()) }
                release.createdAt?.let { MetricLine("Publicada anterior", it.formatDateTimeForDisplay()) }
                if (release.versionCode > currentVersionCode) {
                    AteneaOutlinedButton(
                        text = if (checkingUpdate) "Preparando..." else "Instalar recuperacion",
                        enabled = !checkingUpdate,
                        onClick = { onInstallUpdate(release.asUpdateManifest()) }
                    )
                } else {
                    Text(
                        "Android no permite instalar una versionCode inferior encima. Para volver, Atenea debe publicar una build de recuperacion con versionCode nuevo.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

internal object AteneaSpacing {
    val medium = 10.dp
}

internal fun Long.formatBytes(): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    return if (index == 0) "${value.toLong()} ${units[index]}" else "%.1f %s".format(value, units[index])
}

internal fun String.formatDateTimeForDisplay(): String =
    replace("T", " ").substringBefore(".").removeSuffix("+00:00")
