package com.atenea.android.coreconsole

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryState
import com.atenea.android.api.MobileDeliveryTarget
import com.atenea.android.api.MobileSourceUpdate
import com.atenea.android.api.MobileWorkSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** One observation owns validation and delivery. Conversation caches never authorize a button. */
internal class MobileDeliveryUiState(
    private val sessionId: Long,
    private val scope: CoroutineScope,
    private val load: suspend () -> MobileDeliveryState
) {
    constructor(api: AteneaApiClient, sessionId: Long, scope: CoroutineScope) :
        this(sessionId, scope, {
            if (api.currentOperatorRole() == "PLATFORM_ADMINISTRATOR") api.fetchDelivery(sessionId)
            else MobileDeliveryState(false, emptyList(), validation = api.fetchDevelopmentChangeValidationEvidence(sessionId))
        })

    private var snapshot by mutableStateOf<MobileDeliveryState?>(null)
    private var pending by mutableStateOf<PendingAction?>(null)
    private var generation = 0L
    private var readSequence = 0L
    private var highestRevision = -1L
    private var highestFingerprint: String? = null
    private var changeKey: UUID? = null
    var available by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var lastObservedAt by mutableStateOf<Instant?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var manualRefreshing by mutableStateOf(false)
        private set
    var consultationMessage by mutableStateOf<String?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var actionError by mutableStateOf<String?>(null)
        private set
    var validationRequested by mutableStateOf(false)
        private set

    val operations get() = snapshot?.operations.orEmpty()
    val integration get() = snapshot?.integration?.takeIf { available }
    val sourceUpdate get() = snapshot?.sourceUpdate
    val sourceUpdateEnabled get() = available && snapshot?.sourceUpdateEnabled == true
    val releaseRecoveryEnabled get() = available && snapshot?.releaseRecoveryEnabled == true
    val androidVersionPreparationEnabled get() = available && snapshot?.androidVersionPreparationEnabled == true
    val canConsult get() = !sending && !manualRefreshing
    val completionEnabled get() = available && snapshot?.completionEnabled == true
    val readiness get() = snapshot?.readiness?.takeIf { available }
    val build get() = snapshot?.build?.takeIf { available }
    val progress get() = snapshot?.progress?.takeIf { value -> available && operations.any {
        it.id==value.operationId && it.sessionId==value.sessionId
            && (value.storage==null || value.storage?.sourceCommit==it.sourceCommit && value.storage?.target==it.target)
            && (it.kind==value.kind && it.state==value.state && (!it.terminal || value.storage?.phase in setOf("INSUFFICIENT_SPACE","ATTENTION"))
            || value.kind=="BUILD" && it.kind=="INTEGRATE" && it.state=="SUCCEEDED" && build?.state=="PENDING"
                && value.github?.sourceCommit==build?.sourceCommit
            || value.kind=="PR_CHECKS" && it.kind=="PUBLISH_PR" && it.state=="SUCCEEDED" && !integrated
                && value.github?.sourceCommit!=null && value.github?.sourceCommit==integration?.sourceCommit)
    } }
    val repairingBuild get() = sourceUpdate?.purpose == "BUILD_REPAIR" && sourceUpdate?.state != "PUBLISHED"
    fun canRepairBuild(runInProgress: Boolean) = available && build?.canRepair == true && sourceUpdateEnabled
        && integrated && validationCurrent && !runInProgress && !busy && !validationRunning
        && operations.none { !it.terminal || it.state == "ROLLBACK_FAILED" }
    val deployment get() = snapshot?.deployment?.takeIf { available }
    val validationEvidence get() = snapshot?.validation
    val busy get() = pending != null || validationRequested
    val sending get() = pending?.sending == true
    val pendingAction get() = pending?.kind
    val pendingTarget get() = pending?.target ?: MobileDeliveryTarget.APP_PROD
    val pendingUnconfirmed get() = pending != null && !sending && (pending?.uncertain == true || loadError != null)
    val completion get() = operations.firstOrNull { it.kind == "FINALIZE" }
    val completed get() = completion?.state == "SUCCEEDED"
    val completing get() = completion != null && !completed
    val integrated: Boolean get() {
        val receipt = operations.firstOrNull { it.kind == "INTEGRATE" }
        return receipt?.state == "SUCCEEDED" && integration?.mergeState != "STALE_SOURCE"
            && deployment?.status != "NOT_INTEGRATED"
            && (integration?.sourceCommit == null || integration?.sourceCommit == receipt.sourceCommit)
    }
    val sourcePhaseComplete get() = completion != null || integrated || integration?.mergeState == "MERGED"
    val allRequiredDelivered get() = readiness?.let {
        it.requirementsStatus == "CONFIRMED" && it.ready && it.fresh(System.currentTimeMillis() / 1000)
            && it.integratedMergeCommit == operations.firstOrNull { op -> op.kind == "INTEGRATE" }?.mergeCommit
            && it.requiredTargets.all(::targetDelivered)
    } == true
    fun targetDelivered(target: MobileDeliveryTarget): Boolean {
        val now = System.currentTimeMillis() / 1000
        val facts = readiness ?: return false
        if (!available || !integrated || facts.requirementsStatus != "CONFIRMED" || !facts.fresh(now)
            || facts.integratedMergeCommit != operations.firstOrNull { it.kind == "INTEGRATE" }?.mergeCommit) return false
        return when (target) {
            MobileDeliveryTarget.APP_PROD -> appDeployed
            MobileDeliveryTarget.ANDROID_STABLE -> facts.androidStatus == "INCLUDED"
                && facts.android?.observedAt?.let { it in (now - 60)..(now + 5) } == true
            MobileDeliveryTarget.AX42_PLATFORM -> false
        }
    }
    val appDeployed get() = available && integrated
        && operations.firstOrNull { it.kind == "INTEGRATE" }?.mergeCommit == deployment?.integratedMergeCommit
        && deployment?.isDeployed(System.currentTimeMillis() / 1000) == true
    val canPrepareBackend get() = completion == null && !appDeployed
        && (deployment == null || deployment?.status == "NOT_INCLUDED")

    val validationCoherent: Boolean get() = validationEvidence?.let { evidence ->
        evidence.workSessionId == sessionId && sourceUpdate?.takeIf {
            it.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE")
        }?.sourceRevision?.let { it == evidence.sourceRevision } != false
    } == true
    private val validationObserved get() = loaded && loadError == null
    val validationCurrent: Boolean get() = validationObserved && validationCoherent
        && validationEvidence?.workflow?.let { it.state == "SUCCEEDED" } != false
        && pendingAction !in setOf(DeliveryStepAction.UPDATE_BASE, DeliveryStepAction.RESOLVE,
            DeliveryStepAction.RECOVER_SOURCE, DeliveryStepAction.RETRY_RESOLVER) && validationEvidence?.let {
        it.validationState == "CURRENT" && it.requiredOperations == 4 && it.passedOperations == 4
            && it.operations.size == 4 && it.operations.all { attempt -> attempt.status == "SUCCEEDED"
                && attempt.sourceTreeFingerprintSha256 == it.sourceFingerprintSha256 }
    } == true
    val validationRunning get() = validationRequested || validationObserved && validationCoherent
        && (validationEvidence?.workflow?.running ?: (validationEvidence?.operations?.any { it.status == "RUNNING" } == true))

    fun validationUi(session: MobileWorkSession?, runInProgress: Boolean): DevelopmentChangeValidationUiState {
        if (session?.developmentChangeKey == null) return DevelopmentChangeValidationUiState(false, false, "Validar cambio", "")
        val evidence = validationEvidence
        val matches = validationObserved && validationCoherent && evidence?.changeKey?.toString() == session.developmentChangeKey
        val current = matches && validationCurrent
        val running = matches && validationRunning
        return DevelopmentChangeValidationUiState(true,
            matches && !current && !busy && !running && !runInProgress && evidence?.sourceFingerprintSha256 != null
                && !deliveryBeforeValidation(false) && !sourcePhaseComplete && session.status == "OPEN",
            if (running) "Validando…" else if (evidence?.workflow?.state in setOf("FAILED", "BLOCKED")) "Reintentar validación" else "Validar cambio",
            when {
                !matches -> loadError ?: if (!loaded || refreshing) "Consultando la revisión y sus pruebas. No hay que repetir ninguna acción."
                    else "No se pudo confirmar la revisión y sus pruebas. Consulta el estado o abre Ayuda con este cambio."
                running -> "El servidor comprueba la revisión ${evidence!!.sourceRevision} · ${evidence.passedOperations}/4 pruebas completadas. Puedes salir de Atenea."
                current -> "Revisión ${evidence!!.sourceRevision} validada · 4/4 comprobaciones correctas."
                evidence?.workflow?.state in setOf("FAILED", "BLOCKED", "STALE") -> when (evidence?.workflow?.errorCode) {
                    "VALIDATION_CHECK_FAILED" -> "La validación se ha detenido en una prueba. Revisa su resultado antes de reintentar; las pruebas correctas se conservan."
                    "VALIDATION_SOURCE_CHANGED", "VALIDATION_SOURCE_OR_OWNER_CHANGED" -> "La revisión o su identidad cambiaron. La validación se ha detenido; no se ha validado otra revisión."
                    "SOURCE_FINALIZATION_MAIN_MOVED" -> "Main ha avanzado. Actualiza la base antes de validar la revisión nueva."
                    else -> "La validación del servidor necesita atención. Consulta los detalles o Ayuda con este cambio; no se repiten pruebas automáticamente."
                }
                else -> "Revisión ${evidence!!.sourceRevision} pendiente de validar · ${evidence.passedOperations}/4 comprobaciones."
            }, current, matches && !running && (evidence?.validationState == "BLOCKED"
                || evidence?.workflow?.state in setOf("FAILED", "BLOCKED", "STALE")), running, checking = !matches)
    }

    fun canFinalize(validated: Boolean, runInProgress: Boolean) = available && completionEnabled && completion == null
        && allRequiredDelivered && validated && validationCurrent && !runInProgress && !busy
        && operations.none { !it.terminal || it.state == "ROLLBACK_FAILED" }
    fun canReconcileCompletion(runInProgress: Boolean) = available && completionEnabled && completion?.state == "BLOCKED"
        && !runInProgress && !busy
    fun canResolveConflicts(validated: Boolean, runInProgress: Boolean) = available && sourceUpdateEnabled && !sourcePhaseComplete
        && ((sourceUpdate == null && integration?.mergeState == "CONFLICTS")
            || sourceUpdate?.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE", "PUBLISHED"))
        && validated && validationCurrent && !runInProgress && !busy
        && operations.none { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }
    fun canUpdatePullRequest(validated: Boolean, runInProgress: Boolean) = available && sourceUpdateEnabled && !sourcePhaseComplete
        && sourceUpdate?.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE") && sourceUpdate?.baseStatus == "CURRENT"
        && validated && validationCurrent && !runInProgress && !busy
        && operations.none { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }
    fun canUpdateBase(runInProgress: Boolean) = available && sourceUpdateEnabled && !sourcePhaseComplete
        && ((sourceUpdate?.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE") && sourceUpdate?.baseStatus == "UPDATE_REQUIRED")
            || operations.firstOrNull { it.kind == "INTEGRATE" }?.errorCode == "PR_BASE_VALIDATION_MOVED"
                && (sourceUpdate == null || sourceUpdate?.state == "PUBLISHED")
            || progress?.takeIf { it.kind == "PR_CHECKS" }?.github?.let { it.state == "BASE_CHANGED"
                && it.observedAt in (System.currentTimeMillis() / 1000 - 90)..(System.currentTimeMillis() / 1000 + 5) } == true
                && (sourceUpdate == null || sourceUpdate?.state == "PUBLISHED"))
        && sourceUpdate?.recoveryAvailable != true
        && !runInProgress && !busy && !validationRunning
        && operations.none { !it.terminal || it.state == "ROLLBACK_FAILED" }
    fun deliveryBeforeValidation(runInProgress: Boolean) = !runInProgress && (pending != null
        || !sourcePhaseComplete && sourceUpdate?.let { it.state in setOf("QUEUED", "PREPARE_CLAIMED", "UNCERTAIN", "ATTENTION", "READY_TO_RESOLVE",
            "RETRY_REQUESTED", "RESOLVING", "FAILED", "BLOCKED")
            || it.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE") && it.baseStatus != "CURRENT" } == true)
    fun canRetryResolver(runInProgress: Boolean) = available && sourceUpdateEnabled && !sourcePhaseComplete
        && sourceUpdate?.state == "FAILED" && sourceUpdate?.resolverRunId != null && !runInProgress && !busy
        && operations.none { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }
    fun canRecoverSource(validated: Boolean, runInProgress: Boolean) = available && sourceUpdateEnabled && !sourcePhaseComplete
        && sourceUpdate?.recoveryAvailable == true && !runInProgress && !busy
        && ((sourceUpdate?.state in setOf("ATTENTION", "BLOCKED", "UNCERTAIN") && sourceUpdate?.resolverRunId == null)
            || (validated && validationCurrent && sourceUpdate?.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE")
                && operations.any { it.kind == "PUBLISH_PR" && it.state in setOf("BLOCKED", "FAILED") }))
        && operations.none { (!it.terminal && it.state != "READY") || it.state == "ROLLBACK_FAILED" }
    fun canPrepareReleaseRecovery(validated: Boolean, runInProgress: Boolean) = available && releaseRecoveryEnabled && canPrepareBackend
        && build?.state != "FAILED" && build?.errorCode == null
        && !(build == null && operations.firstOrNull { it.kind == "RELEASE" && it.target == MobileDeliveryTarget.APP_PROD }?.errorCode == "RELEASE_BUILD_FAILED")
        && validated && validationCurrent && !runInProgress && !busy
        && operations.firstOrNull { it.kind == "INTEGRATE" }?.state == "SUCCEEDED"
        && operations.none { !it.terminal || it.state == "ROLLBACK_FAILED" }

    suspend fun consult() {
        if (!canConsult) return
        manualRefreshing = true
        val before = snapshot
        consultationMessage = "Consultando el estado; no se ejecuta ni reintenta ninguna operación."
        try {
            refresh(manual = true)
            consultationMessage = loadError ?: if (before?.operations == snapshot?.operations
                && before?.sourceUpdate == snapshot?.sourceUpdate && before?.validation == snapshot?.validation
                && before?.integration == snapshot?.integration && before?.deployment?.sourceCommit == snapshot?.deployment?.sourceCommit
                && before?.deployment?.status == snapshot?.deployment?.status && before?.deployment?.healthy == snapshot?.deployment?.healthy
                && before?.readiness?.requiredTargets == snapshot?.readiness?.requiredTargets
                && before?.readiness?.ready == snapshot?.readiness?.ready && before?.readiness?.androidStatus == snapshot?.readiness?.androidStatus)
                "Estado consultado: sin cambios. Sigue la acción indicada arriba; consultar no reintenta ni publica."
            else "Estado actualizado. El siguiente paso aparece arriba; no se ha iniciado ninguna operación."
        } finally { manualRefreshing = false }
    }

    suspend fun refresh(manual: Boolean = false) {
        if (sending || manualRefreshing && !manual) return
        val epoch = generation
        val sequence = ++readSequence
        refreshing = true
        try {
            val next = load()
            if (epoch != generation || sequence != readSequence) return
            require(next.operations.all { it.sessionId == sessionId })
            require(listOf(next.integration?.sessionId, next.sourceUpdate?.sessionId, next.deployment?.sessionId,
                next.readiness?.sessionId, next.validation?.workSessionId, next.progress?.sessionId).all { it == null || it == sessionId })
            next.validation?.let {
                require(changeKey == null || changeKey == it.changeKey)
                if (it.sourceRevision < highestRevision) return
                require(it.sourceRevision != highestRevision || highestFingerprint == it.sourceFingerprintSha256)
                changeKey = it.changeKey
                highestRevision = it.sourceRevision
                highestFingerprint = it.sourceFingerprintSha256
            }
            snapshot = next
            available = next.enabled
            loaded = true
            loadError = null
            lastObservedAt = Instant.now()
            pending?.let { if (observes(it, next)) pending = null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (epoch != generation || sequence != readSequence) return
            available = false
            loadError = "No se pudo confirmar el estado actual. Consulta de nuevo; no se ha repetido la solicitud."
        } finally {
            if (sequence == readSequence) refreshing = false
        }
    }

    fun submitSource(kind: DeliveryStepAction, action: suspend () -> MobileSourceUpdate) = submit(kind) { Receipt.Source(action()) }
    fun submitDelivery(kind: DeliveryStepAction,
        target: MobileDeliveryTarget = if (kind in setOf(DeliveryStepAction.PREPARE_ANDROID, DeliveryStepAction.PREPARE_ANDROID_VERSION,
            DeliveryStepAction.INTEGRATE_ANDROID_VERSION)) MobileDeliveryTarget.ANDROID_STABLE else MobileDeliveryTarget.APP_PROD,
        action: suspend () -> MobileDeliveryOperation) = submit(kind, target) { Receipt.Operation(action()) }

    private fun submit(kind: DeliveryStepAction, target: MobileDeliveryTarget = MobileDeliveryTarget.APP_PROD, action: suspend () -> Receipt) {
        if (busy || !available) return
        if (kind == DeliveryStepAction.UPDATE_PR && !canUpdatePullRequest(true, false)) return
        if (kind == DeliveryStepAction.UPDATE_BASE && !canUpdateBase(false)) return
        generation++
        readSequence++
        refreshing = false
        consultationMessage = null
        actionError = null
        pending = PendingAction(kind, snapshot!!, sending = true, target = target)
        scope.launch { perform(action) }
    }

    private suspend fun perform(action: suspend () -> Receipt): Receipt? {
        var accepted: Receipt? = null
        try {
            val receipt = action()
            require(when (receipt) {
                is Receipt.Source -> receipt.value.sessionId == sessionId
                is Receipt.Operation -> receipt.value.sessionId == sessionId
            })
            accepted = receipt
            pending = pending?.copy(sending = false, receipt = receipt)
        } catch (cancelled: CancellationException) {
            pending = pending?.copy(sending = false, uncertain = true)
            throw cancelled
        } catch (failure: Exception) {
            val rejected = failure is AteneaApiException && failure.status in 400..499
            pending = pending?.copy(sending = false, uncertain = !rejected, rejected = rejected)
            actionError = sourceBaseFailureMessage(failure.message) ?: if (rejected)
                "La solicitud no se ha aceptado. ${failure.message.orEmpty()}"
            else "La respuesta no se pudo confirmar. Atenea consultará la misma operación; no vuelvas a solicitarla."
        }
        refresh()
        return accepted
    }

    suspend fun confirmRelease(planId: UUID, action: suspend () -> MobileDeliveryOperation) {
        if (busy || !available || !validationCurrent) error("Estado por confirmar")
        generation++
        readSequence++
        pending = PendingAction(DeliveryStepAction.CONFIRM_RELEASE, snapshot!!, sending = true, expectedId = planId)
        check(perform { Receipt.Operation(action()) } != null) { actionError ?: "Publicación por confirmar" }
    }

    fun beginValidation(): Boolean {
        if (busy || validationRunning || !validationObserved || !validationCoherent || validationCurrent || deliveryBeforeValidation(false)
            || sourcePhaseComplete || validationEvidence?.sourceFingerprintSha256 == null) return false
        generation++
        readSequence++
        refreshing = false
        validationRequested = true
        actionError = null
        return true
    }
    suspend fun finishValidation() {
        try { refresh() } finally { validationRequested = false }
    }

    private fun observes(intent: PendingAction, state: MobileDeliveryState): Boolean {
        if (intent.rejected) return true
        return when (val receipt = intent.receipt) {
            is Receipt.Source -> state.sourceUpdate?.let { it.id == receipt.value.id && atLeast(it.updatedAt, receipt.value.updatedAt)
                && (it.state == receipt.value.state || it.state !in setOf("QUEUED", "PREPARE_CLAIMED")) } == true
            is Receipt.Operation -> state.operations.any { observed -> observed.id == receipt.value.id
                && atLeast(observed.updatedAt, receipt.value.updatedAt)
                && (observed.state == receipt.value.state || observed.updatedAt != null && receipt.value.updatedAt != null
                    || intent.before.operations.firstOrNull { it.id == observed.id } != observed) }
            null -> when (intent.kind) {
                DeliveryStepAction.UPDATE_BASE, DeliveryStepAction.RESOLVE, DeliveryStepAction.REPAIR_BUILD, DeliveryStepAction.RECOVER_SOURCE, DeliveryStepAction.RETRY_RESOLVER ->
                    state.sourceUpdate?.let { next ->
                        val before = intent.before.sourceUpdate
                        next.id != before?.id || next.state != before.state || next.updatedAt != before.updatedAt
                            || next.sourceRevision != before.sourceRevision || next.resolverRunId != before.resolverRunId
                    } == true
                else -> state.operations.any { current ->
                    val kind = when (intent.kind) {
                        DeliveryStepAction.CREATE_PR, DeliveryStepAction.UPDATE_PR -> "PUBLISH_PR"
                        DeliveryStepAction.INTEGRATE -> "INTEGRATE"
                        DeliveryStepAction.FINALIZE, DeliveryStepAction.RECONCILE_FINALIZE -> "FINALIZE"
                        DeliveryStepAction.PREPARE_ANDROID_VERSION, DeliveryStepAction.INTEGRATE_ANDROID_VERSION -> "ANDROID_VERSION"
                        else -> "RELEASE"
                    }
                    current.kind == kind && (intent.expectedId?.let { current.id == it } ?: (current.target == intent.target))
                        && intent.before.operations.none { it.id == current.id && it == current }
                }
            }
        }
    }
    private fun atLeast(observed: String?, accepted: String?): Boolean = accepted == null || observed?.let {
        try { !Instant.parse(it).isBefore(Instant.parse(accepted)) } catch (_: Exception) { false }
    } == true

    private data class PendingAction(val kind: DeliveryStepAction, val before: MobileDeliveryState,
        val sending: Boolean, val receipt: Receipt? = null, val uncertain: Boolean = false, val rejected: Boolean = false,
        val expectedId: UUID? = null, val target: MobileDeliveryTarget = MobileDeliveryTarget.APP_PROD)
    private sealed interface Receipt {
        data class Source(val value: MobileSourceUpdate) : Receipt
        data class Operation(val value: MobileDeliveryOperation) : Receipt
    }
}
