package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
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
import com.atenea.android.api.PasskeyCredentialState
import com.atenea.android.api.PasskeyInventory
import com.atenea.android.api.PasskeyInventoryItem
import com.atenea.android.api.TotpEnrollment
import kotlinx.coroutines.launch

internal enum class SettingsSection { HOME, SECURITY, DEVICES }

@Composable
internal fun SettingsScreen(
    apiClient: AteneaApiClient,
    updateState: UpdateCheckResult?,
    updateMessage: String?,
    updateProgress: UpdateDownloadProgress?,
    checkingUpdate: Boolean,
    operatorName: String,
    currentVersionCode: Int,
    currentVersionName: String,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AteneaUpdateManifest) -> Unit,
    section: SettingsSection,
    onOpenSecurity: () -> Unit,
    onOpenDevices: () -> Unit,
    onBack: () -> Unit
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
    var passkeyInventory by remember { mutableStateOf<PasskeyInventory?>(null) }
    var passkeyLoading by remember { mutableStateOf(true) }
    var passkeyError by remember { mutableStateOf<String?>(null) }
    var passkeyMessage by remember { mutableStateOf<String?>(null) }
    var pendingPasskeyRevoke by remember { mutableStateOf<PasskeyInventoryItem?>(null) }
    var pendingSessionRevoke by remember { mutableStateOf<OperatorSessionInventory?>(null) }
    var pendingOthersRevoke by remember { mutableStateOf(false) }
    var sessionActionPending by remember { mutableStateOf(false) }
    var passkeyActionPending by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }
    var historyVisibleCount by remember { mutableStateOf(20) }
    var passkeyHistoryExpanded by remember { mutableStateOf(false) }
    var passkeyHistoryVisibleCount by remember { mutableStateOf(20) }

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

    fun loadPasskeys() {
        scope.launch {
            passkeyLoading = true
            passkeyError = null
            try {
                passkeyInventory = apiClient.fetchPasskeyInventory()
            } catch (error: Exception) {
                passkeyError = error.message ?: "No se pudo cargar el inventario de passkeys."
            } finally {
                passkeyLoading = false
            }
        }
    }

    LaunchedEffect(section) {
        when (section) {
            SettingsSection.SECURITY -> loadPasskeys()
            SettingsSection.DEVICES -> loadSessions()
            SettingsSection.HOME -> Unit
        }
    }
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
        if (section == SettingsSection.HOME) {
            AteneaPanel {
                Text("Cuenta", style = MaterialTheme.typography.titleMedium)
                MetricLine("Sesión actual", operatorName)
                Text("Gestiona cómo accedes a Atenea y dónde tienes sesiones abiertas.")
                AteneaOutlinedButton(text = "Seguridad y acceso", onClick = onOpenSecurity)
                AteneaOutlinedButton(text = "Dispositivos con acceso", onClick = onOpenDevices)
            }
        }
        if (section == SettingsSection.SECURITY) {
        AteneaPanel {
            AteneaOutlinedButton(text = "Volver a Ajustes", onClick = onBack)
            Text("Seguridad y acceso", style = MaterialTheme.typography.titleMedium)
            Text("Gestiona las passkeys y el respaldo TOTP de tu cuenta.")
            MetricLine(
                "Passkey",
                if (passkey.availability == PasskeyAvailability.AVAILABLE) {
                    "Disponible"
                } else {
                    "No disponible en Android 8"
                }
            )
            when {
                passkeyLoading -> Text("Cargando passkeys sanitizadas…")
                passkeyError != null -> Text(
                    passkeyError.orEmpty(),
                    color = MaterialTheme.colorScheme.error
                )
                passkeyInventory?.state == "DISABLED" -> {
                    Text("Inventario correctivo desactivado", style = MaterialTheme.typography.titleSmall)
                    Text(passkeyInventory?.nextAction.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
                passkeyInventory != null -> {
                    val inventory = passkeyInventory!!
                    Text(
                        if (inventory.readOnly) {
                            "Discovery de passkeys: solo lectura"
                        } else if (inventory.independentDomainsReady) {
                            "Passkeys independientes verificadas"
                        } else {
                            "Falta independencia entre proveedores"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(inventory.nextAction, style = MaterialTheme.typography.bodySmall)
                    if (inventory.credentials.none { it.state == PasskeyCredentialState.ACTIVE }) {
                        Text("No hay passkeys activas.", style = MaterialTheme.typography.bodySmall)
                    }
                    inventory.credentials.filter { it.state == PasskeyCredentialState.ACTIVE }.forEach { credential ->
                        Text(credential.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            buildString {
                                append("Activa")
                                append(if (credential.backupEligible) " · Sincronizable" else " · No sincronizable")
                                append(if (credential.lastVerifiedAt != null) " · Verificada" else " · Sin verificar")
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (passkeyMutationsAllowed(inventory.readOnly)) {
                            AteneaOutlinedButton(
                                text = "Revocar en Atenea",
                                enabled = !passkeyActionPending,
                                onClick = { pendingPasskeyRevoke = credential }
                            )
                        }
                    }
                    val revoked = inventory.credentials.filter { it.state == PasskeyCredentialState.REVOKED }
                    if (revoked.isNotEmpty()) {
                        AteneaOutlinedButton(
                            text = if (passkeyHistoryExpanded) "Ocultar passkeys revocadas" else "Passkeys revocadas (${revoked.size})",
                            onClick = { passkeyHistoryExpanded = !passkeyHistoryExpanded }
                        )
                        if (passkeyHistoryExpanded) revoked.take(passkeyHistoryVisibleCount).forEach { credential ->
                            Text("${credential.label} · Revocada", style = MaterialTheme.typography.bodySmall)
                        }
                        if (passkeyHistoryExpanded && revoked.size > passkeyHistoryVisibleCount) {
                            AteneaOutlinedButton(text = "Mostrar más passkeys", onClick = { passkeyHistoryVisibleCount += 20 })
                        }
                    }
                    if (passkeyMutationsAllowed(inventory.readOnly)
                        && inventory.signallingEnabled) {
                        AteneaOutlinedButton(
                            text = "Sincronizar inventario activo",
                            enabled = inventory.credentials.any { it.state == PasskeyCredentialState.ACTIVE },
                            onClick = {
                                scope.launch {
                                    if (passkey.signalAvailability != PasskeySignalAvailability.AVAILABLE) {
                                        passkeyMessage = "Signal API requiere Android 15. Revisa las passkeys manualmente en el proveedor."
                                        return@launch
                                    }
                                    runCatching {
                                        val snapshot = apiClient.fetchPasskeySignalSnapshot()
                                        passkey.signalAllAcceptedCredentials(
                                            snapshot,
                                            expectedRelyingPartyId = "atenea.yudri.es"
                                        )
                                    }.onSuccess {
                                        passkeyMessage = "El proveedor recibió el inventario activo completo."
                                    }.onFailure {
                                        passkeyMessage = "No se pudo señalizar. Revisa las passkeys manualmente en el proveedor."
                                    }
                                }
                            }
                        )
                    }
                }
            }
            passkeyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            MetricLine("TOTP", "Respaldo opcional")
            factorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (passkeyMutationsAllowed(passkeyInventory?.readOnly)
                && totpEnrollment == null) {
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
            } else if (passkeyMutationsAllowed(passkeyInventory?.readOnly)
                && totpEnrollment != null) {
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
        }

        if (section == SettingsSection.DEVICES) {
        AteneaPanel {
            AteneaOutlinedButton(text = "Volver a Ajustes", onClick = onBack)
            Text("Dispositivos con acceso", style = MaterialTheme.typography.titleMedium)
            Text("Revisa dónde está abierta tu cuenta y cierra los accesos que no reconozcas.")
            sessionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                sessionLoading -> Text("Cargando inventario seguro…")
                sessionError != null && sessions.isEmpty() -> Unit
                sessions.none { it.state == OperatorSessionState.ACTIVE } -> Text("No hay sesiones activas.")
                else -> sessions.filter { it.state == OperatorSessionState.ACTIVE }.sortedByDescending { it.current }.forEach { item ->
                    Text(
                        if (item.current) "${item.deviceLabel} · Esta sesión" else item.deviceLabel,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "${item.clientType} · Último uso ${item.lastUsedAt.formatDateTimeForDisplay()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!item.current) {
                        AteneaOutlinedButton(
                            text = "Cerrar acceso",
                            enabled = !sessionActionPending,
                            onClick = { pendingSessionRevoke = item }
                        )
                    }
                }
            }
            if (sessions.any { !it.current && it.state == OperatorSessionState.ACTIVE }) {
                AteneaOutlinedButton(
                    text = "Cerrar las demás sesiones",
                    enabled = !sessionActionPending,
                    onClick = { pendingOthersRevoke = true }
                )
            }
            if (sessionError != null) AteneaOutlinedButton(text = "Reintentar", onClick = { loadSessions() })
            val history = sessions.filter { it.state != OperatorSessionState.ACTIVE }
            if (!sessionLoading && history.isNotEmpty()) {
                AteneaOutlinedButton(
                    text = if (historyExpanded) "Ocultar historial" else "Historial de sesiones (${history.size})",
                    onClick = { historyExpanded = !historyExpanded }
                )
                if (historyExpanded) history.take(historyVisibleCount).forEach { item ->
                    Text(item.deviceLabel, style = MaterialTheme.typography.bodyLarge)
                    Text("${item.clientType} · ${if (item.state == OperatorSessionState.REVOKED) "Revocada" else "Caducada"} · ${item.lastUsedAt.formatDateTimeForDisplay()}", style = MaterialTheme.typography.bodySmall)
                }
                if (historyExpanded && history.size > historyVisibleCount) {
                    AteneaOutlinedButton(text = "Mostrar más sesiones", onClick = { historyVisibleCount += 20 })
                }
            }
        }
        }

        if (section == SettingsSection.HOME) {
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

    pendingPasskeyRevoke?.let { credential ->
        AlertDialog(
            onDismissRequest = { if (!passkeyActionPending) pendingPasskeyRevoke = null },
            title = { Text("¿Revocar ${credential.label}?") },
            text = { Text("Dejarás de poder usar esta passkey para entrar en Atenea.") },
            confirmButton = { AteneaOutlinedButton(text = "Confirmar revocación", enabled = !passkeyActionPending, onClick = {
                pendingPasskeyRevoke = null
                scope.launch {
                    passkeyActionPending = true
                    try {
                        runCatching { apiClient.revokePasskey(credential.recordId) }
                            .onSuccess {
                                passkeyMessage = "Revocada en Atenea. Bórrala manualmente del proveedor si ya no la necesitas."
                                loadPasskeys()
                            }
                            .onFailure { passkeyError = it.message }
                    } finally {
                        passkeyActionPending = false
                    }
                }
            }) },
            dismissButton = { AteneaOutlinedButton(text = "Cancelar", onClick = { pendingPasskeyRevoke = null }) }
        )
    }
    val sessionToClose = pendingSessionRevoke
    if (sessionToClose != null || pendingOthersRevoke) {
        AlertDialog(
            onDismissRequest = { if (!sessionActionPending) { pendingSessionRevoke = null; pendingOthersRevoke = false } },
            title = { Text(if (pendingOthersRevoke) "¿Cerrar todas las demás sesiones?" else "¿Cerrar el acceso de ${sessionToClose?.deviceLabel}?") },
            text = { Text("Los dispositivos afectados tendrán que iniciar sesión de nuevo.") },
            confirmButton = { AteneaOutlinedButton(text = "Confirmar cierre", enabled = !sessionActionPending, onClick = {
                val closeOthers = pendingOthersRevoke
                pendingSessionRevoke = null
                pendingOthersRevoke = false
                scope.launch {
                    sessionActionPending = true
                    try {
                        runCatching {
                            if (closeOthers) apiClient.revokeOtherOperatorSessions()
                            else apiClient.revokeOperatorSession(sessionToClose!!.familyId)
                        }.onSuccess { loadSessions() }
                            .onFailure { sessionError = it.message }
                    } finally {
                        sessionActionPending = false
                    }
                }
            }) },
            dismissButton = { AteneaOutlinedButton(text = "Cancelar", onClick = {
                pendingSessionRevoke = null
                pendingOthersRevoke = false
            }) }
        )
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
