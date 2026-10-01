package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MobileDeliveryPanel(api: AteneaApiClient, sessionId: Long, validated: Boolean, runInProgress: Boolean) {
    if (api.currentOperatorRole() != "PLATFORM_ADMINISTRATOR") return
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var operations by remember(sessionId) { mutableStateOf(emptyList<MobileDeliveryOperation>()) }
    var available by remember(sessionId) { mutableStateOf(false) }
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var busy by remember(sessionId) { mutableStateOf(false) }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }
    var confirmation by remember(sessionId) { mutableStateOf<MobileDeliveryOperation?>(null) }
    var confirmMerge by remember(sessionId) { mutableStateOf(false) }
    // No factor, grant or production authority is saved to disk or in the conversation.
    var totp by remember(sessionId) { mutableStateOf("") }

    LaunchedEffect(api, sessionId) {
        while (true) {
            try {
                val state=api.fetchDelivery(sessionId); operations=state.operations; available=state.enabled; loaded=true
            }
            catch (failure: Exception) { error = "No se pudo consultar la publicación. ${failure.message.orEmpty()}" }
            delay(5_000)
        }
    }
    fun act(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try {
                action(); val state=api.fetchDelivery(sessionId); operations=state.operations; available=state.enabled; loaded=true
            }
            catch (failure: Exception) {
                error = "${failure.message.orEmpty()} Consulta el estado antes de repetir; Atenea conserva la misma operación."
            } finally { busy = false }
        }
    }
    val pr = operations.firstOrNull { it.kind == "PUBLISH_PR" }
    val integration = operations.firstOrNull { it.kind == "INTEGRATE" }
    val active = operations.any { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }
    val releasePlanned = operations.any { it.kind == "RELEASE" && (!it.terminal || it.state == "ROLLBACK_FAILED") }
    Column {
        Text("Entrega del cambio")
        Text("Validar → Crear PR → Integrar → Publicar. Terminar una conversación no publica nada.")
        if (!available) Text(if (loaded) "La publicación móvil aún no está habilitada en este servidor."
            else "Consultando las capacidades de publicación…")
        operations.take(5).forEach { operation ->
            Text("${deliveryKindLabel(operation)} · ${deliveryStateLabel(operation.state)}" +
                (operation.sourceCommit?.take(12)?.let { " · $it" } ?: "") +
                (operation.versionName?.let { " · $it (${operation.versionCode})" } ?: ""))
            operation.errorCode?.let { Text(deliveryErrorLabel(it)) }
            operation.effectiveSourceCommit?.let { Text("Commit efectivo: ${it.take(12)}") }
            operation.resultSha256?.let { Text("APK SHA-256: $it") }
        }
        pr?.pullRequestUrl?.takeIf { it.matches(Regex("https://github\\.com/jlnieto/atenea/pull/[1-9][0-9]*")) }?.let { url ->
            TextButton(onClick = { uriHandler.openUri(url) }) { Text("Revisar PR") }
        }
        error?.let { Text(it) }
        if (pr?.state != "SUCCEEDED") {
            AteneaButton("Crear PR", enabled = available && validated && !runInProgress && !busy && !active,
                onClick = { act { api.createDeliveryPullRequest(sessionId) } })
        } else if (integration?.state != "SUCCEEDED") {
            AteneaButton("Integrar cambio", enabled = available && validated && !runInProgress && !busy && !active,
                onClick = { confirmMerge = true })
        }
        if (integration?.state == "SUCCEEDED") {
            MobileDeliveryTarget.entries.forEach { target ->
                val plan = operations.firstOrNull { it.kind == "RELEASE" && it.target == target }
                if (plan?.canConfirm(System.currentTimeMillis() / 1000) == true) {
                    AteneaButton("Confirmar ${target.label}", enabled = available && !busy && !runInProgress,
                        onClick = { confirmation = plan; totp = "" })
                } else {
                    AteneaButton("Preparar ${target.label}", enabled = available && validated && !runInProgress && !busy && !active && !releasePlanned,
                        onClick = { act { api.prepareRelease(sessionId, target) } })
                }
            }
        }
    }
    if (confirmMerge) {
        AlertDialog(onDismissRequest = { confirmMerge = false }, title = { Text("Integrar este cambio") },
            text = { Text("Atenea comprobará el commit validado y las protecciones de GitHub. Integrar no despliega a PROD.") },
            confirmButton = { TextButton(onClick = {
                confirmMerge = false; act { api.integrateDelivery(sessionId) }
            }) { Text("Integrar") } },
            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text("Cancelar") } })
    }
    confirmation?.let { plan ->
        AlertDialog(onDismissRequest = { confirmation = null; totp = "" },
            title = { Text("Publicar ${plan.target.label}") }, text = {
                Column {
                    Text("Commit ${plan.sourceCommit}. " +
                        (plan.versionName?.let { "Versión $it (${plan.versionCode}). " } ?: "") +
                        "La operación continuará aunque App se reinicie. Introduce el código de tu autenticador para esta publicación.")
                    OutlinedTextField(value = totp, onValueChange = { value ->
                        totp = value.filter { it in '0'..'9' }.take(6)
                    }, label = { Text("Código de 6 cifras") }, singleLine = true)
                }
            }, confirmButton = {
                TextButton(enabled = totp.length == 6 && !busy && plan.canConfirm(System.currentTimeMillis() / 1000), onClick = {
                    val code = totp; confirmation = null; totp = ""
                    act {
                        val authorization = api.authorizeRelease(plan.id, code)
                        api.confirmRelease(plan.id, authorization)
                    }
                }) { Text("Publicar") }
            }, dismissButton = { TextButton(onClick = { confirmation = null; totp = "" }) { Text("Cancelar") } })
    }
}

internal fun deliveryKindLabel(operation: MobileDeliveryOperation): String = when (operation.kind) {
    "PUBLISH_PR" -> "PR"; "INTEGRATE" -> "Integración"; else -> operation.target.label
}
internal fun deliveryStateLabel(state: String): String = when (state) {
    "QUEUED" -> "En cola"; "WAITING_CI" -> "Esperando validaciones GitHub"
    "PLANNING", "PREPARING" -> "Preparando artefacto"; "READY" -> "Preparado, pendiente de confirmar"
    "CONFIRMED", "ACCEPTED", "APPLYING" -> "Publicando"; "SUCCEEDED" -> "Completado"
    "ROLLING_BACK" -> "Restaurando versión anterior"; "ROLLED_BACK" -> "Versión anterior restaurada"
    "QUARANTINED" -> "Necesita atención: evidencia contradictoria"
    "ROLLBACK_FAILED" -> "Necesita atención: restauración fallida"; else -> "Bloqueado"
}

internal fun deliveryErrorLabel(code: String): String = when (code) {
    "CI_PENDING", "RELEASE_BUILD_PENDING" -> "GitHub está ejecutando las comprobaciones. Atenea seguirá esperando; no necesitas repetir la acción."
    "PLAN_EXPIRED" -> "El plan ha caducado sin publicar. Prepara uno nuevo."
    "CANONICAL_MAIN_MOVED" -> "Main ha cambiado desde este plan. Atenea ha detenido la publicación para no elegir otro commit."
    "RELEASE_TRANSPORT_UNAVAILABLE", "GITHUB_UNAVAILABLE" -> "No se pudo confirmar el estado del servidor. Atenea conserva la misma operación y vuelve a consultarlo."
    "GITHUB_REJECTED", "RELEASE_BUILD_FAILED" -> "GitHub ha rechazado una comprobación o la integración. No se ha saltado la validación; consulta la PR."
    "ACTIVE_AGENT_RUN", "ACTIVE_WORKLOAD", "WORKER_NOT_IDLE_OR_HEALTHY" -> "Hay trabajo activo o el worker no está preparado. No se ha iniciado otra publicación."
    "VALIDATED_OWNERSHIP_REQUIRED" -> "El código actual necesita una validación válida antes de continuar."
    "RELEASE_EVIDENCE_MISMATCH" -> "La evidencia del servidor no coincide con esta operación. No repitas la publicación."
    else -> "Necesita atención: $code"
}
