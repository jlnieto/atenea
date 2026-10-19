package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryTarget
import com.atenea.android.api.MobileSourceUpdate
import com.atenea.android.api.MobileDeploymentObservation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


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
    runInProgress: Boolean, state: MobileDeliveryUiState, sessionOpen: Boolean = true,
    onOpenUpdates: (() -> Unit)? = null, validationInProgress: Boolean = false) {
    val checksCurrent = validated && state.validationCurrent
    val scope = rememberCoroutineScope()
    if (api.currentOperatorRole() != "PLATFORM_ADMINISTRATOR") {
        DeliveryStepCard(DeliveryStepUi(DeliveryStepStatus.ATTENTION, "Permiso de entrega requerido",
            "Tu rol no permite integrar o publicar. Ayuda con este cambio explica qué autorización falta.")) { }
        return
    }
    val step = state.currentStep(checksCurrent, runInProgress, validationInProgress = validationInProgress)
    val uriHandler = LocalUriHandler.current
    val operations = state.operations
    val available = state.available
    val busy = state.busy
    var confirmation by remember(sessionId) { mutableStateOf<MobileDeliveryOperation?>(null) }
    var confirmMerge by remember(sessionId) { mutableStateOf(false) }
    var confirmFinalize by remember(sessionId) { mutableStateOf(false) }
    var showHistory by remember(sessionId) { mutableStateOf(false) }
    var versionToIntegrate by remember(sessionId) { mutableStateOf<MobileDeliveryOperation?>(null) }
    var showVersions by remember(sessionId) { mutableStateOf(false) }
    val context = LocalContext.current
    val passkey = remember(context) { AndroidPasskeyCoordinator(context) }

    fun deliver(kind: DeliveryStepAction,
        target: MobileDeliveryTarget = if (kind in setOf(DeliveryStepAction.PREPARE_ANDROID, DeliveryStepAction.PREPARE_ANDROID_VERSION,
            DeliveryStepAction.INTEGRATE_ANDROID_VERSION)) MobileDeliveryTarget.ANDROID_STABLE else MobileDeliveryTarget.APP_PROD,
        action: suspend () -> MobileDeliveryOperation) = state.submitDelivery(kind, target, action)
    fun source(kind: DeliveryStepAction, action: suspend () -> MobileSourceUpdate) = state.submitSource(kind, action)
    val pr = operations.firstOrNull { it.kind == "PUBLISH_PR" }
    val active = operations.any { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }
    val releasePlanned = operations.any { it.kind == "RELEASE" && (!it.terminal || it.state == "ROLLBACK_FAILED") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The current decision and its button precede receipts and secondary targets.
        if (sessionOpen && (checksCurrent || state.sourcePhaseComplete || runInProgress || state.deliveryBeforeValidation(runInProgress || validationInProgress)) || state.completed) DeliveryStepCard(step) {
            when (step.action) {
                DeliveryStepAction.CREATE_PR -> AteneaButton("Crear PR", enabled = available && checksCurrent && !runInProgress && !busy && !active,
                    onClick = { deliver(DeliveryStepAction.CREATE_PR) { api.createDeliveryPullRequest(sessionId) } })
                DeliveryStepAction.INTEGRATE -> AteneaButton("Integrar cambio", enabled = available && checksCurrent && !runInProgress && !busy && !active
                    && state.integration?.allowsRequest == true, onClick = { confirmMerge = true })
                DeliveryStepAction.CONFIRM_RELEASE -> step.operation?.let { plan ->
                    AteneaButton("Confirmar ${plan.target.label}", enabled = available && checksCurrent && !busy && !runInProgress
                        && plan.canConfirm(System.currentTimeMillis() / 1000)
                        && (plan.target != MobileDeliveryTarget.APP_PROD || state.canPrepareBackend),
                        onClick = { confirmation = plan })
                }
                DeliveryStepAction.PREPARE_BACKEND -> AteneaButton("Preparar Backend PROD",
                    enabled = if (state.releaseRecoveryEnabled) state.canPrepareReleaseRecovery(checksCurrent, runInProgress)
                        else available && checksCurrent && !runInProgress && !busy && !active && !releasePlanned && state.canPrepareBackend,
                    onClick = { deliver(DeliveryStepAction.PREPARE_BACKEND) {
                        if (state.releaseRecoveryEnabled) api.prepareReleaseRecovery(sessionId)
                        else api.prepareRelease(sessionId, MobileDeliveryTarget.APP_PROD)
                    } })
                DeliveryStepAction.PREPARE_ANDROID -> AteneaButton("Preparar Android estable",
                    enabled = available && checksCurrent && !runInProgress && !busy && !active && !releasePlanned,
                    onClick = { deliver(DeliveryStepAction.PREPARE_ANDROID) { api.prepareAndroidRecovery(sessionId) } })
                DeliveryStepAction.PREPARE_ANDROID_VERSION -> AteneaButton("Preparar versión Android nueva",
                    enabled = available && state.androidVersionPreparationEnabled && checksCurrent && !runInProgress && !busy && !active && !releasePlanned,
                    onClick = { deliver(DeliveryStepAction.PREPARE_ANDROID_VERSION) { api.prepareAndroidVersion(sessionId) } })
                DeliveryStepAction.INTEGRATE_ANDROID_VERSION -> step.operation?.let { version ->
                    AteneaButton("Integrar versión Android", enabled = available && checksCurrent && !runInProgress && !busy && !active,
                        onClick = { versionToIntegrate = version })
                    version.pullRequestUrl?.takeIf { it.matches(Regex("https://github\\.com/jlnieto/atenea/pull/[1-9][0-9]*")) }?.let { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) { Text("Revisar PR de versión") }
                    }
                }
                DeliveryStepAction.FINALIZE -> AteneaButton("Finalizar cambio", enabled = state.canFinalize(checksCurrent, runInProgress),
                    onClick = { confirmFinalize = true })
                DeliveryStepAction.RECONCILE_FINALIZE -> AteneaButton("Reconciliar finalización", enabled = state.canReconcileCompletion(runInProgress),
                    onClick = { deliver(DeliveryStepAction.RECONCILE_FINALIZE) { api.finalizeDelivery(sessionId, reconcile = true) } })
                DeliveryStepAction.RESOLVE -> AteneaButton("Resolver conflictos", enabled = state.canResolveConflicts(checksCurrent, runInProgress),
                    onClick = { source(DeliveryStepAction.RESOLVE) { api.resolveDeliveryConflicts(sessionId) } })
                DeliveryStepAction.UPDATE_PR -> AteneaButton(if (state.repairingBuild) "Crear PR de reparación" else "Actualizar la misma PR", enabled = state.canUpdatePullRequest(checksCurrent, runInProgress),
                    onClick = { deliver(DeliveryStepAction.UPDATE_PR) { api.createDeliveryPullRequest(sessionId) } })
                DeliveryStepAction.UPDATE_BASE -> AteneaButton("Actualizar base con main", enabled = state.canUpdateBase(runInProgress || validationInProgress),
                    onClick = { source(DeliveryStepAction.UPDATE_BASE) { api.resolveDeliveryConflicts(sessionId) } })
                DeliveryStepAction.RECOVER_SOURCE -> state.sourceUpdate?.let { update ->
                    AteneaButton("Recuperar operación", enabled = state.canRecoverSource(checksCurrent, runInProgress),
                        onClick = { source(DeliveryStepAction.RECOVER_SOURCE) { api.recoverDeliverySource(sessionId, update.id) } })
                }
                DeliveryStepAction.REPAIR_BUILD -> AteneaButton("Investigar y reparar construcción", enabled = state.canRepairBuild(runInProgress),
                    onClick = { source(DeliveryStepAction.REPAIR_BUILD) { api.repairReleaseBuild(sessionId) } })
                DeliveryStepAction.RETRY_RESOLVER -> state.sourceUpdate?.let { update -> update.resolverRunId?.let { run ->
                    AteneaButton("Reintentar resolución", enabled = state.canRetryResolver(runInProgress),
                        onClick = { source(DeliveryStepAction.RETRY_RESOLVER) { api.retryDeliveryResolver(sessionId, update.id, run) } })
                } }
                DeliveryStepAction.REFRESH -> AteneaOutlinedButton(if (state.manualRefreshing) "Consultando…" else "Consultar estado", enabled = state.canConsult,
                    onClick = { scope.launch { state.consult() } })
                DeliveryStepAction.NONE -> Unit
            }
            OperationProgressPanel(state, step)
        }
        state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.build?.let { build ->
            if (build.state == "FAILED") {
                Text("Fase: ${buildPhaseLabel(build.phase)}. Fuente: ${build.sourceCommit?.take(12).orEmpty()}.",style = MaterialTheme.typography.bodySmall)
                if (build.failureLocations.isEmpty()) Text("Esta ejecución no conservó el detalle estructurado. No se inventa su causa; la investigación comprobará el código.", style = MaterialTheme.typography.bodySmall)
                build.failureLocations.take(5).forEach { Text(it,style = MaterialTheme.typography.bodySmall) }
                build.runUrl?.let { url -> TextButton(onClick = { uriHandler.openUri(url) }) { Text("Ver construcción GitHub") } }
            }
        }
        state.consultationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("delivery-consultation-result")) }
        if (state.integrated || state.completed) Text("✓ PR integrada", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.appDeployed) Text(deploymentLabel(state.deployment, true), style = MaterialTheme.typography.bodySmall)
        pr?.pullRequestUrl?.takeIf { it.matches(Regex("https://github\\.com/jlnieto/atenea/pull/[1-9][0-9]*")) }?.let { url ->
            TextButton(onClick = { uriHandler.openUri(url) }) { Text("Revisar PR") }
        }
        TextButton(onClick = { showVersions = !showVersions }) {
            Text(if (showVersions) "Ocultar versiones y publicaciones" else "Versiones y publicaciones")
        }
        if (showVersions) {
            state.readiness?.let { readiness ->
                Text("Publicaciones", style = MaterialTheme.typography.titleSmall)
                if (readiness.requirementsStatus == "CONFIRMED") {
                    Text("Este cambio requiere: " + readiness.requiredTargets.joinToString { it.label }.ifBlank { "ninguna publicación de runtime" })
                } else Text("Los destinos del cambio se confirmarán con el diff de su PR integrada.")
                readiness.android?.let { apk ->
                    val installed = context.packageManager.getPackageInfo(context.packageName, 0)
                    @Suppress("DEPRECATION") val installedCode = installed.versionCode
                    Text("APK publicada: ${apk.versionName} / ${apk.versionCode} · fuente ${apk.sourceCommit.take(12)}")
                    Text("Esta app instalada: ${installed.versionName} / $installedCode")
                    Text(when (readiness.androidStatus) {
                        "INCLUDED" -> if (installedCode==apk.versionCode) "La APK publicada contiene este cambio y coincide con la instalada."
                            else "La APK publicada contiene este cambio. La versión instalada se comprueba por separado."
                        "NOT_INCLUDED" -> "La APK publicada todavía no contiene este cambio."
                        "NOT_INTEGRATED" -> "La APK del canal se puede consultar sin validar ni integrar esta conversación."
                        else -> "No se pudo confirmar si esta APK contiene el cambio."
                    })
                    if (onOpenUpdates != null) AteneaOutlinedButton(if (installedCode<apk.versionCode) "Actualizar Atenea" else "Comprobar actualizaciones",
                        onClick = onOpenUpdates)
                } ?: Text("No se ha podido confirmar la APK publicada. Consultar no publica nada.")
            }
        }
        if (state.integrated && state.readiness?.requirementsStatus == "CONFIRMED") {
            Text("Entregas necesarias", style = MaterialTheme.typography.titleSmall)
            state.readiness?.requiredTargets.orEmpty().forEach { target ->
                Text("${target.label}: " + if (state.targetDelivered(target)) "confirmada" else "pendiente",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("El siguiente paso se muestra arriba. No necesitas preparar componentes ajenos a este cambio.", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        TextButton(onClick = { showHistory = !showHistory }) {
            Text(if (showHistory) "Ocultar historial y detalles" else "Ver historial y detalles")
        }
        if (showHistory) {
            Text("Historial · no es trabajo pendiente", style = MaterialTheme.typography.titleSmall)
            state.deployment?.sourceCommit?.let { Text("Versión PROD: ${it.take(12)}") }
            operations.take(10).forEach { operation ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${deliveryKindLabel(operation)} · ${deliveryStateLabel(operation.state)}", style = MaterialTheme.typography.titleSmall)
                    operation.sourceCommit?.let { Text("Fuente: ${it.take(12)}", style = MaterialTheme.typography.bodySmall) }
                    operation.errorCode?.let { Text(deliveryErrorLabel(it)) }
                    operation.effectiveSourceCommit?.let { Text("Commit efectivo: ${it.take(12)}") }
                    operation.resultSha256?.let { Text("APK SHA-256: $it") }
                    operation.versionName?.let { Text("Versión: $it / ${operation.versionCode}") }
                    operation.releaseRecovery?.let { proof ->
                        Text("Contiene el merge ${proof.integratedMergeCommit.take(12)} del ticket.")
                        TextButton(onClick = { uriHandler.openUri("https://github.com/jlnieto/atenea/compare/" +
                            "${proof.integratedMergeCommit}...${proof.selectedMainCommit}") }) { Text("Revisar versión a publicar") }
                    }
                    Text("Operación: ${operation.operationId}", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            state.sourceUpdate?.let { update ->
                Text("Actualización de base · ${update.state}")
                update.errorCode?.let { Text("Código guardado: $it") }
            }
        }
    }
    if (confirmFinalize) {
        AlertDialog(onDismissRequest = { confirmFinalize = false }, title = { Text("Finalizar este cambio") },
            text = { Text("Atenea comprobará la validación, la integración y el despliegue sano. Cerrará esta misma WorkSession " +
                "y marcará el ticket como completado, conservando la conversación y todos los recibos. No volverá a publicar. " +
                "Para modificaciones posteriores abre otro cambio.") },
            confirmButton = { TextButton(enabled = state.canFinalize(checksCurrent, runInProgress), onClick = {
                confirmFinalize = false; deliver(DeliveryStepAction.FINALIZE) { api.finalizeDelivery(sessionId) }
            }) { Text("Finalizar cambio") } },
            dismissButton = { TextButton(onClick = { confirmFinalize = false }) { Text("Cancelar") } })
    }
    if (confirmMerge) {
        AlertDialog(onDismissRequest = { confirmMerge = false }, title = { Text("Integrar este cambio") },
            text = { Text("Atenea comprobará el commit validado y las protecciones de GitHub. Integrar no despliega a PROD.") },
            confirmButton = { TextButton(enabled = available && checksCurrent && !runInProgress && !busy && !active
                    && state.integration?.allowsRequest == true, onClick = {
                confirmMerge = false; deliver(DeliveryStepAction.INTEGRATE) { api.integrateDelivery(sessionId) }
            }) { Text("Integrar") } },
            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text("Cancelar") } })
    }
    versionToIntegrate?.let { version ->
        AlertDialog(onDismissRequest = { versionToIntegrate = null }, title = { Text("Integrar sólo la versión Android") },
            text = { Text("Se integrará la PR técnica de versión ${version.versionName} / ${version.versionCode}, sin recrear tu cambio. Atenea comprobará el diff exacto y CI. No publica la APK: después tendrás que prepararla y confirmar su publicación.") },
            confirmButton = { TextButton(enabled = available && checksCurrent && !runInProgress && !busy && !active
                && operations.any { it.id == version.id && it.kind == "ANDROID_VERSION" && it.state == "READY" }, onClick = {
                    versionToIntegrate = null
                    deliver(DeliveryStepAction.INTEGRATE_ANDROID_VERSION) { api.integrateAndroidVersion(sessionId, version.id) }
                }) { Text("Integrar versión") } },
            dismissButton = { TextButton(onClick = { versionToIntegrate = null }) { Text("Cancelar") } })
    }
    confirmation?.let { plan ->
        ReleaseAuthorizationDialog(plan,
            canPublish = {
                state.available && checksCurrent && !runInProgress && !state.busy && !active
                    && (plan.target != MobileDeliveryTarget.APP_PROD || state.canPrepareBackend)
                    && sameConfirmableRelease(plan, state.operations.firstOrNull { it.id == plan.id }, System.currentTimeMillis() / 1000)
            },
            passkeyAvailable = passkey.availability == PasskeyAvailability.AVAILABLE,
            authorizePasskey = {
                val origin = passkey.signingOrigin()
                val challenge = try { api.beginReleasePasskey(plan.id, origin) }
                catch (failure: AteneaApiException) {
                    if (failure.status == 409 && failure.message.contains("PUBLICATION_PASSKEY_SETUP_REQUIRED")) throw ReleasePasskeySetupRequired()
                    throw failure
                }
                val assertion = try { passkey.getCredential(challenge.requestJson) }
                catch (_: GetCredentialCancellationException) { throw ReleaseAuthorizationCancelled() }
                catch (_: NoCredentialException) { throw ReleasePasskeyUnavailable() }
                api.authorizeReleasePasskey(plan.id, origin, challenge, assertion)
            },
            authorizeTotp = { code -> api.authorizeRelease(plan.id, code) },
            configurePasskey = { code ->
                val origin = passkey.signingOrigin()
                val challenge = api.beginReleasePasskeySetup(origin)
                val assertion = try { passkey.getCredential(challenge.requestJson) }
                catch (_: GetCredentialCancellationException) { throw ReleaseAuthorizationCancelled() }
                catch (_: NoCredentialException) { throw ReleasePasskeyUnavailable() }
                api.configureReleasePasskey(origin, challenge, assertion, code)
            },
            confirm = { grant ->
                state.confirmRelease(plan.id) {
                    api.confirmRelease(plan.id, grant).also { result ->
                        check(result.id == plan.id && result.sessionId == sessionId
                            && result.state in setOf("CONFIRMED", "ACCEPTED", "APPLYING", "SUCCEEDED"))
                    }
                }
            },
            refresh = { state.refresh() },
            onAccepted = { confirmation = null },
            onDismiss = { confirmation = null; scope.launch { state.refresh() } },
            loadPasskeyStatus = { api.fetchReleasePasskeyStatus(passkey.signingOrigin()) })
    }
}

internal fun deploymentLabel(observation: MobileDeploymentObservation?, deployed: Boolean): String = when {
    deployed && observation?.origin == "OPERATOR" -> "Backend PROD desplegado por operador. No fue una publicación desde el móvil."
    deployed && observation?.origin == "MOBILE" -> "Backend PROD publicado desde Atenea."
    observation?.status == "UNHEALTHY" -> "La versión contiene el cambio, pero PROD no está healthy. Necesita atención."
    observation?.status == "NOT_INCLUDED" -> "La versión de PROD todavía no contiene este cambio."
    else -> "No se pudo verificar el despliegue actual. Consultar no publica ni reintenta operaciones."
}

internal fun sourceUpdateLabel(state: String, errorCode: String? = null): String = when (state) {
    "QUEUED", "PREPARE_CLAIMED" -> "Atenea está obteniendo la versión exacta aprobada de main y preparando los conflictos en esta misma conversación. Puedes salir de la app; no repitas la acción."
    "UNCERTAIN" -> "Comprobando la misma preparación; no repitas la acción."
    "READY_TO_RESOLVE" -> "Preparación lista; Atenea está iniciando el resolver."
    "RETRY_REQUESTED" -> "Reintento autorizado de la misma resolución."
    "RESOLVING" -> "Codex está resolviendo los conflictos en esta conversación."
    "RESOLVER_COMPLETED" -> "Codex terminó. Falta validar la nueva revisión y actualizar la misma PR."
    "READY_TO_FINALIZE" -> "Preparación terminada. Falta validar la nueva revisión y actualizar la misma PR."
    "PUBLISHED" -> "La misma PR contiene la revisión nueva. GitHub debe validar este head antes de integrar."
    "ATTENTION", "BLOCKED", "FAILED" -> "Atenea está detenida, no trabajando. " + when (errorCode) {
        "SOURCE_UPDATE_TARGET_UNAVAILABLE" -> "No se pudo obtener el código aprobado. Tu trabajo y la operación están conservados."
        "SOURCE_UPDATE_REF_MOVED" -> "Main avanzó durante la preparación. Se conserva la versión aprobada; no se elegirá otra automáticamente."
        "SOURCE_UPDATE_TARGET_INVALID" -> "La versión o la evidencia no coinciden con lo autorizado. No se cambiará el código; consulta Ayuda con este cambio."
        else -> "No se ha confirmado la preparación. Tu código, conversación y PR se conservan."
    }
    else -> "Falta confirmar el estado de esta operación. Consulta el resultado; no se ha integrado ni publicado el cambio."
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

internal fun buildPhaseLabel(phase: String?): String = when (phase) {
    "BACKEND_COMPILATION" -> "Compilación del backend o sus pruebas"
    "BACKEND_TEST_COMPILE_OR_TEST" -> "Compilación y pruebas del backend"
    "BACKEND_BUILD" -> "Construcción de la imagen del backend"
    "ANDROID_BUILD" -> "Construcción de Android"
    "WAITING_CI" -> "Pendiente de comenzar en GitHub"
    "BUILDING" -> "Construcción en GitHub"
    "COMPLETED" -> "Construcción completada"
    else -> "Construcción de la versión"
}

internal fun deliveryKindLabel(operation: MobileDeliveryOperation): String = when (operation.kind) {
    "ANDROID_VERSION" -> "Preparación de versión Android"
    "FINALIZE" -> "Finalización del cambio"
    "PUBLISH_PR" -> "PR"; "INTEGRATE" -> "Integración"; else -> operation.target.label
}

internal fun visibleDeliveryOperations(operations: List<MobileDeliveryOperation>, audited: Boolean,
    showHistory: Boolean): List<MobileDeliveryOperation> = if (!audited || showHistory) operations.take(5)
    else operations.filter { !it.terminal || it.state == "ROLLBACK_FAILED"
        || (it.kind == "FINALIZE" && it.state != "SUCCEEDED") }.take(5)
internal fun deliveryStateLabel(state: String): String = when (state) {
    "QUEUED" -> "En cola"; "WAITING_CI" -> "Esperando validaciones GitHub"
    "PLANNING", "PREPARING" -> "Preparando artefacto"; "READY" -> "Preparado, pendiente de confirmar"
    "CONFIRMED", "ACCEPTED", "APPLYING" -> "Publicando"; "SUCCEEDED" -> "Completado"
    "ROLLING_BACK" -> "Restaurando versión anterior"; "ROLLED_BACK" -> "Versión anterior restaurada"
    "QUARANTINED" -> "Necesita atención: evidencia contradictoria"
    "ROLLBACK_FAILED" -> "Necesita atención: restauración fallida"; else -> "Bloqueado"
}

internal fun deliveryErrorLabel(code: String): String = when (code) {
    "RELEASE_STORAGE_INSUFFICIENT" -> "Falta espacio seguro para preparar esta versión. Atenea ya ha retirado lo permitido por la retención; no se ha desplegado."
    "RETENTION_BUSY" -> "Hay otra operación protegida en curso. No se ha forzado la limpieza ni la publicación."
    "RETENTION_RECOVERY_CONTENT_MISSING", "RETENTION_CURRENT_RECEIPT_REQUIRED" -> "Falta una copia o un recibo protegido de recuperación. Atenea no ha forzado la limpieza ni el despliegue. Hace falta revisar esa evidencia."
    "RETENTION_EVIDENCE_MISMATCH", "RETENTION_RESOURCE_MOVED", "RETENTION_PRODUCTION_MOVED", "RETENTION_REQUIRES_INSPECTION" -> "La retención necesita revisar su evidencia. Se han conservado la operación y los datos; no repitas la publicación."
    "APK_VERSION_NOT_NEWER" -> "La APK candidata tiene una versión igual o anterior a la del canal. No se ha publicado. Hace falta preparar e integrar una versión nueva; consultar no cambia el número."
    "ANDROID_CHANNEL_MOVED" -> "El canal Android cambió desde esta preparación. Tu código se conserva; prepara de nuevo con la versión vigente."
    "ANDROID_VERSION_POLICY_REJECTED", "ANDROID_VERSION_IDENTITY_MISMATCH" -> "La versión o el diff no cumplen el incremento cerrado previsto. La operación está detenida sin publicar; revisa la PR técnica."
    "ANDROID_VERSION_PR_CLOSED" -> "La PR técnica de versión se cerró sin integrar. No se crea otra automáticamente ni se publica una APK."
    "SOURCE_FINALIZATION_MAIN_MOVED" -> "La base preparada es anterior a main. Pulsa Actualizar base con main; después valida la revisión resultante y actualiza la misma PR. Tu código se conserva."
    "SOURCE_BASE_OBSERVATION_UNAVAILABLE" -> "No se pudo comprobar la base en GitHub. Consulta el estado; no repitas las pruebas ni la publicación."
    "HEALTHY_DELIVERED_CHANGE_REQUIRED" -> "Atenea no pudo confirmar un despliegue sano que contenga este cambio. El ticket no se ha completado."
    "COMPLETION_WORKSPACE_NOT_CLEAN_OR_EXACT", "COMPLETION_WORKSPACE_NOT_VERIFIED" -> "El workspace no coincide con el código entregado o tiene cambios sin publicar. Se conserva sin borrar nada."
    "COMPLETION_WORK_IN_PROGRESS" -> "Hay trabajo pendiente en esta WorkSession. Espera a que termine antes de finalizar."
    "COMPLETION_EVIDENCE_MISMATCH", "COMPLETION_STATE_MISMATCH", "COMPLETION_RELEASE_RECEIPT_REQUIRED" -> "La evidencia de finalización necesita revisión. Atenea conserva el ticket y la misma operación."
    "DELIVERED_CHANGE_COMPLETION_REQUIRED" -> "Para cerrar este ticket utiliza Finalizar cambio, después de validar, integrar y publicar."
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
    "ALREADY_CURRENT" -> "El backend ya ejecuta esta versión. No se ha iniciado otra publicación ni creado un nuevo recibo de despliegue."
    "RELEASE_CHANGE_NOT_INCLUDED", "RELEASE_INTEGRATION_EVIDENCE_MISMATCH", "RELEASE_SOURCE_EVIDENCE_MISMATCH",
    "RELEASE_ANCESTRY_EVIDENCE_INCOMPLETE" -> "Atenea no pudo comprobar que esta versión contiene el ticket integrado. No se ha publicado."
    "RELEASE_TRANSPORT_UNAVAILABLE", "GITHUB_UNAVAILABLE" -> "No se pudo confirmar el estado del servidor. Atenea conserva la misma operación y vuelve a consultarlo."
    "GITHUB_REJECTED" -> "GitHub ha rechazado una comprobación o la integración. No se ha saltado la validación; consulta la PR."
    "RELEASE_BUILD_FAILED" -> "Falló la construcción posterior a integrar. No se ha desplegado esa versión. Consulta el diagnóstico y la reparación; no repitas Preparar."
    "PR_BASE_VALIDATION_MOVED" -> "Main avanzó. Actualiza la base y comprueba la combinación actual antes de integrar."
    "ACTIVE_AGENT_RUN", "ACTIVE_WORKLOAD", "WORKER_NOT_IDLE_OR_HEALTHY" -> "Hay trabajo activo o el worker no está preparado. No se ha iniciado otra publicación."
    "VALIDATED_OWNERSHIP_REQUIRED" -> "El código actual necesita una validación válida antes de continuar."
    "RELEASE_EVIDENCE_MISMATCH" -> "La evidencia del servidor no coincide con esta operación. No repitas la publicación."
    else -> "Necesita atención: $code"
}

internal fun sourceBaseFailureMessage(message: String?): String? =
    listOf("SOURCE_FINALIZATION_MAIN_MOVED", "SOURCE_BASE_OBSERVATION_UNAVAILABLE")
        .firstOrNull { message?.contains(it) == true }?.let(::deliveryErrorLabel)
