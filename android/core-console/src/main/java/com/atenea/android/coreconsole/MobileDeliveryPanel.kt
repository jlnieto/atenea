package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryIntegration
import com.atenea.android.api.MobileDeliveryState
import com.atenea.android.api.MobileDeliveryTarget
import com.atenea.android.api.MobileSourceUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** Owned by the WorkSession screen, not by the currently visible pane. */
internal class MobileDeliveryUiState(
    private val sessionId: Long,
    private val scope: CoroutineScope,
    private val load: suspend () -> MobileDeliveryState
) {
    constructor(api: AteneaApiClient, sessionId: Long, scope: CoroutineScope) :
        this(sessionId, scope, { api.fetchDelivery(sessionId) })
    var operations by mutableStateOf(emptyList<MobileDeliveryOperation>())
        private set
    var integration by mutableStateOf<MobileDeliveryIntegration?>(null)
        private set
    var sourceUpdate by mutableStateOf<MobileSourceUpdate?>(null)
        private set
    var sourceUpdateEnabled by mutableStateOf(false)
        private set
    var available by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var actionError by mutableStateOf<String?>(null)
        private set

    fun canResolveConflicts(validated: Boolean, runInProgress: Boolean): Boolean =
        available && sourceUpdateEnabled
            && ((sourceUpdate == null && integration?.mergeState == "CONFLICTS")
                || sourceUpdate?.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE", "PUBLISHED"))
            && validated && !runInProgress && !busy
            && operations.none { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }

    fun canUpdatePullRequest(validated: Boolean, runInProgress: Boolean): Boolean =
        available && sourceUpdateEnabled && sourceUpdate?.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE")
            && validated && !runInProgress && !busy
            && operations.none { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }

    fun canRetryResolver(runInProgress: Boolean): Boolean =
        available && sourceUpdateEnabled && sourceUpdate?.state == "FAILED" && sourceUpdate?.resolverRunId != null
            && !runInProgress && !busy
            && operations.none { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }

    fun canRecoverSource(validated: Boolean, runInProgress: Boolean): Boolean =
        available && sourceUpdateEnabled && sourceUpdate?.recoveryAvailable == true && !runInProgress && !busy
            && ((sourceUpdate?.state in setOf("ATTENTION", "BLOCKED", "UNCERTAIN") && sourceUpdate?.resolverRunId == null)
                || (validated && sourceUpdate?.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE")
                    && operations.any { it.kind == "PUBLISH_PR" && it.state in setOf("BLOCKED", "FAILED") }))
            && operations.none { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }

    suspend fun refresh() {
        try {
            val state = load()
            require(state.operations.all { it.sessionId == sessionId })
            require(state.integration?.sessionId?.let { it == sessionId } ?: true)
            require(state.sourceUpdate?.sessionId?.let { it == sessionId } ?: true)
            operations = state.operations
            integration = state.integration
            sourceUpdate = state.sourceUpdate
            sourceUpdateEnabled = state.sourceUpdateEnabled
            available = state.enabled
            loaded = true
            loadError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Keep durable IDs visible, but never authorize actions from a stale capability response.
            available = false
            integration = null
            sourceUpdateEnabled = false
            loadError = "No se pudo consultar la publicación. ${failure.message.orEmpty()}"
        }
    }

    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        scope.launch {
            try {
                action()
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                actionError = "${failure.message.orEmpty()} Consulta el estado antes de repetir; Atenea conserva la misma operación."
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun rememberMobileDeliveryUiState(api: AteneaApiClient, sessionId: Long, enabled: Boolean): MobileDeliveryUiState {
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val state = remember(api, sessionId) { MobileDeliveryUiState(api, sessionId, scope) }
    LaunchedEffect(state, owner, enabled) {
        if (enabled) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                state.refresh()
                delay(5_000)
            }
        }
    }
    return state
}

@Composable
internal fun MobileDeliveryPanel(api: AteneaApiClient, sessionId: Long, validated: Boolean,
    runInProgress: Boolean, state: MobileDeliveryUiState) {
    if (api.currentOperatorRole() != "PLATFORM_ADMINISTRATOR") return
    val uriHandler = LocalUriHandler.current
    val operations = state.operations
    val available = state.available
    val loaded = state.loaded
    val busy = state.busy
    var confirmation by remember(sessionId) { mutableStateOf<MobileDeliveryOperation?>(null) }
    var confirmMerge by remember(sessionId) { mutableStateOf(false) }
    // No factor, grant or production authority is saved to disk or in the conversation.
    var totp by remember(sessionId) { mutableStateOf("") }

    fun act(action: suspend () -> Unit) = state.act(action)
    val pr = operations.firstOrNull { it.kind == "PUBLISH_PR" }
    val integration = operations.firstOrNull { it.kind == "INTEGRATE" }
    val active = operations.any { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }
    val releasePlanned = operations.any { it.kind == "RELEASE" && (!it.terminal || it.state == "ROLLBACK_FAILED") }
    Column {
        Text("PR → Integración → Publicación")
        if (!validated) Text("Primero completa la validación de esta revisión. Integrar y publicar son pasos separados.")
        if (!available && state.loadError == null) Text(if (loaded) "La publicación móvil aún no está habilitada en este servidor."
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
        if (pr?.state == "SUCCEEDED" && integration?.state != "SUCCEEDED") {
            Text(deliveryIntegrationLabel(state.integration?.mergeState))
        }
        state.sourceUpdate?.let { update ->
            Text(sourceUpdateLabel(update.state))
            update.errorCode?.let { Text("Recuperación detenida: $it. La misma operación y conversación se conservan.") }
            if (state.canRecoverSource(validated,runInProgress)) {
                Text("Recuperar termina la operación retenida; no cambia su main ni descarta archivos.")
                AteneaButton("Recuperar operación", enabled = true,
                    onClick = { act { api.recoverDeliverySource(sessionId,update.id) } })
            }
            val resolverRunId = update.resolverRunId
            if (update.state == "FAILED" && resolverRunId != null) {
                Text("Reintentar comprueba la misma fuente y conserva el intento fallido. Consultar no ejecuta otro resolver.")
                AteneaButton("Reintentar resolución", enabled = state.canRetryResolver(runInProgress),
                    onClick = { act { api.retryDeliveryResolver(sessionId, update.id, resolverRunId) } })
            }
        }
        if ((state.integration?.mergeState == "CONFLICTS" && state.sourceUpdate == null)
            || state.sourceUpdate?.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE", "PUBLISHED")) {
            AteneaButton(if (state.sourceUpdate == null) "Resolver conflictos" else "Actualizar base con main", enabled = state.canResolveConflicts(validated, runInProgress),
                onClick = { act { api.resolveDeliveryConflicts(sessionId) } })
        }
        state.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.sourceUpdate?.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE")) {
            AteneaButton("Actualizar la misma PR", enabled = state.canUpdatePullRequest(validated, runInProgress),
                onClick = { act { api.createDeliveryPullRequest(sessionId) } })
        } else if (pr?.state != "SUCCEEDED") {
            AteneaButton("Crear PR", enabled = available && validated && !runInProgress && !busy && !active,
                onClick = { act { api.createDeliveryPullRequest(sessionId) } })
        } else if (integration?.state != "SUCCEEDED") {
            AteneaButton("Integrar cambio", enabled = available && validated && !runInProgress && !busy && !active
                    && state.integration?.allowsRequest == true,
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
            confirmButton = { TextButton(enabled = available && validated && !runInProgress && !busy && !active
                    && state.integration?.allowsRequest == true, onClick = {
                confirmMerge = false; act { api.integrateDelivery(sessionId) }
            }) { Text("Integrar") } },
            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text("Cancelar") } })
    }
    confirmation?.let { plan ->
        val currentPlan = operations.firstOrNull { it.id == plan.id }
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
                TextButton(enabled = available && validated && !runInProgress && totp.length == 6 && !busy
                    && currentPlan?.canConfirm(System.currentTimeMillis() / 1000) == true, onClick = {
                    val code = totp; confirmation = null; totp = ""
                    act {
                        val authorization = api.authorizeRelease(plan.id, code)
                        api.confirmRelease(plan.id, authorization)
                    }
                }) { Text("Publicar") }
            }, dismissButton = { TextButton(onClick = { confirmation = null; totp = "" }) { Text("Cancelar") } })
    }
}

internal fun sourceUpdateLabel(state: String): String = when (state) {
    "QUEUED", "PREPARE_CLAIMED" -> "Preparando conflictos en esta WorkSession…"
    "UNCERTAIN" -> "Comprobando la misma preparación; no repitas la acción."
    "READY_TO_RESOLVE" -> "Preparación lista; Atenea está iniciando el resolver."
    "RETRY_REQUESTED" -> "Reintento autorizado de la misma resolución."
    "RESOLVING" -> "Codex está resolviendo los conflictos en esta conversación."
    "RESOLVER_COMPLETED" -> "Codex terminó. Falta validar la nueva revisión y actualizar la misma PR."
    "READY_TO_FINALIZE" -> "Preparación terminada. Falta validar la nueva revisión y actualizar la misma PR."
    "PUBLISHED" -> "La misma PR contiene la revisión nueva. GitHub debe validar este head antes de integrar."
    else -> "La recuperación necesita atención. No se ha integrado ni publicado el cambio."
}

internal fun deliveryIntegrationLabel(mergeState: String?): String = when (mergeState) {
    "CONFLICTS" -> "La PR tiene conflictos con main. Hay que resolverlos antes de integrar."
    "UNKNOWN" -> "GitHub está calculando si la PR puede integrarse; no significa que esté ejecutando pruebas."
    "PROTECTED" -> "Las protecciones de GitHub todavía no permiten integrar esta PR."
    "STALE_SOURCE" -> "El código cambió después de publicar. Esta revisión necesita validación y actualización de la PR."
    "MERGEABLE" -> "La rama no tiene conflictos. Al integrar, Atenea volverá a comprobar la revisión y las pruebas."
    "MERGED" -> "GitHub observa la PR integrada. Atenea debe reconciliar el resultado antes de publicar."
    "CLOSED" -> "La PR está cerrada sin integrar."
    else -> "No se pudo confirmar el estado de integración. Consulta de nuevo antes de continuar."
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
    "PR_MERGE_CONFLICTS" -> "La PR tiene conflictos con main. No se ha integrado; hay que resolverlos."
    "PR_MERGEABILITY_PENDING" -> "GitHub está calculando la integración de la PR; no está ejecutando pruebas por esta consulta."
    "PR_PROTECTED" -> "Las protecciones de GitHub impiden integrar esta PR."
    "GITHUB_CHECKS_FAILED" -> "Las comprobaciones de este commit han fallado. Hay que corregir el código antes de continuar."
    "UFD_QUEUED" -> "GitHub ha registrado las comprobaciones y están en cola. Atenea seguirá esperando; no necesitas repetir la acción."
    "UFD_REQUESTED" -> "Atenea ha solicitado las comprobaciones del commit a GitHub. Está esperando que arranquen; no necesitas repetir la acción."
    "UFD_DISPATCH_UNCONFIRMED" -> "Atenea no ha podido confirmar el inicio. Consulta la misma operación; no se enviará otra solicitud automáticamente."
    "UFD_NOT_STARTED" -> "GitHub no ha confirmado el inicio de las comprobaciones de este commit. No se ha creado otra PR ni se ha omitido la validación."
    "UFD_CONTROLLER_UNAVAILABLE" -> "El workflow autorizado de main no está disponible. La publicación se ha detenido antes de solicitar las comprobaciones."
    "UFD_IDENTITY_REJECTED", "UFD_EVIDENCE_INCOMPLETE" -> "La evidencia GitHub no coincide con el commit publicado. La publicación está detenida; no repitas la acción."
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
