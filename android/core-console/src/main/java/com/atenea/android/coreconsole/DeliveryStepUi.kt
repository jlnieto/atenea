package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryTarget

internal enum class DeliveryStepStatus(val label: String) {
    WORKING("Atenea está trabajando"), WAITING("Esperando a otro servicio"), ACTION("Te toca a ti"), ATTENTION("Necesita atención"),
    COMPLETE("Terminado"), CHECKING("Estado por confirmar")
}

internal enum class DeliveryStepAction {
    NONE, CREATE_PR, INTEGRATE, CONFIRM_RELEASE, PREPARE_BACKEND, PREPARE_ANDROID, FINALIZE, RECONCILE_FINALIZE,
    PREPARE_ANDROID_VERSION, INTEGRATE_ANDROID_VERSION,
    RESOLVE, UPDATE_PR, UPDATE_BASE, RETRY_RESOLVER, RECOVER_SOURCE, REPAIR_BUILD, REFRESH
}

/** A presentation of current evidence, never an authorization or an automatic action. */
internal data class DeliveryStepUi(val status: DeliveryStepStatus, val title: String, val message: String,
    val action: DeliveryStepAction = DeliveryStepAction.NONE, val operation: MobileDeliveryOperation? = null)

internal fun MobileDeliveryUiState.currentStep(validated: Boolean, runInProgress: Boolean,
    nowSeconds: Long = System.currentTimeMillis() / 1000, validationInProgress: Boolean = false): DeliveryStepUi {
    if (completed) return DeliveryStepUi(DeliveryStepStatus.COMPLETE, "Cambio completado",
        "El ticket está cerrado. La conversación y los recibos se conservan como historia.")
    if (busy && !validationRequested) return DeliveryStepUi(
        if (pendingUnconfirmed) DeliveryStepStatus.CHECKING else DeliveryStepStatus.WORKING,
        when (pendingAction) {
            DeliveryStepAction.UPDATE_BASE -> "Actualizando base con main"
            DeliveryStepAction.RESOLVE -> "Preparando código y conflictos"
            DeliveryStepAction.REPAIR_BUILD -> "Preparando diagnóstico y reparación"
            DeliveryStepAction.UPDATE_PR -> "Actualizando la misma PR"
            DeliveryStepAction.CREATE_PR -> "Creando la PR"
            DeliveryStepAction.PREPARE_BACKEND -> "Preparando ${pendingTarget.label}"
            DeliveryStepAction.PREPARE_ANDROID -> "Preparando Android estable"
            DeliveryStepAction.PREPARE_ANDROID_VERSION -> "Preparando una versión Android nueva"
            DeliveryStepAction.INTEGRATE_ANDROID_VERSION -> "Integrando la versión Android"
            DeliveryStepAction.INTEGRATE -> "Integrando la PR"
            DeliveryStepAction.FINALIZE, DeliveryStepAction.RECONCILE_FINALIZE -> "Finalizando cambio"
            else -> "Confirmando la operación"
        }, if (pendingUnconfirmed) "La solicitud está por confirmar. Consulta la misma operación; no vuelvas a enviarla."
            else "Atenea está confirmando esta solicitud. Puedes salir de la app; no hace falta volver a pulsar.",
        if (pendingUnconfirmed) DeliveryStepAction.REFRESH else DeliveryStepAction.NONE)
    if (loadError != null || !available) return DeliveryStepUi(DeliveryStepStatus.CHECKING,
        if (!loaded && loadError == null) "Consultando estado" else "Entrega no disponible",
        loadError ?: if (loaded) "La entrega no está habilitada. Ayuda explica los permisos y capacidades necesarios."
            else "Atenea está leyendo el estado. Consultar no inicia operaciones.", DeliveryStepAction.REFRESH)
    if (runInProgress) return DeliveryStepUi(DeliveryStepStatus.WORKING, if (repairingBuild) "Reparación en curso" else "Codex está trabajando",
        "Espera al resultado. Puedes salir de la app; no hace falta enviar otra vez el prompt.")
    if (validationInProgress || validationRunning) return DeliveryStepUi(DeliveryStepStatus.WORKING, "Validando cambio",
        "Atenea está comprobando esta revisión. Espera al resultado; no actualices la base ni repitas la validación mientras siga en curso.")
    completion?.let {
        return if (it.state == "BLOCKED") DeliveryStepUi(DeliveryStepStatus.ATTENTION, "Finalización detenida",
            deliveryErrorLabel(it.errorCode ?: "COMPLETION_EVIDENCE_MISMATCH"), DeliveryStepAction.RECONCILE_FINALIZE, it)
        else DeliveryStepUi(DeliveryStepStatus.WORKING, "Finalizando cambio",
            "Puedes salir de la app. Atenea cerrará la misma WorkSession y conservará la conversación.", operation = it)
    }
    sourceUpdate?.takeIf { !sourcePhaseComplete && it.state != "PUBLISHED"
        && (it.state !in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE") || operations.none { op -> !op.terminal })
        && (validated && validationCurrent || it.state !in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE") || it.baseStatus != "CURRENT") }?.let { update ->
        val prepared = update.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE")
        val action = when {
            canRecoverSource(validated, false) -> DeliveryStepAction.RECOVER_SOURCE
            canRetryResolver(false) -> DeliveryStepAction.RETRY_RESOLVER
            prepared && update.baseStatus == "UPDATE_REQUIRED" -> DeliveryStepAction.UPDATE_BASE
            prepared && update.baseStatus != "CURRENT" -> DeliveryStepAction.REFRESH
            prepared -> DeliveryStepAction.UPDATE_PR
            else -> DeliveryStepAction.NONE
        }
        val stopped = update.state in setOf("BLOCKED", "FAILED", "ATTENTION")
            || action in setOf(DeliveryStepAction.RECOVER_SOURCE, DeliveryStepAction.UPDATE_BASE)
        val title = when {
            action == DeliveryStepAction.UPDATE_BASE -> "Actualizar base con main"
            prepared && action == DeliveryStepAction.REFRESH -> "Base por confirmar"
            stopped -> "Actualización detenida"
            update.state in setOf("QUEUED", "PREPARE_CLAIMED") -> "Preparando código y conflictos"
            update.state in setOf("READY_TO_RESOLVE", "RETRY_REQUESTED", "RESOLVING") -> "Resolviendo conflictos con Codex"
            else -> if (repairingBuild) "Crear PR de reparación" else "Actualizar la misma PR"
        }
        val message = when (action) {
            DeliveryStepAction.UPDATE_BASE -> "La base preparada es anterior a main. Atenea está detenida; tu código y la misma PR se conservan. Actualiza la base primero; después valida la revisión resultante y actualiza esa PR. No repitas el prompt ni valides la base antigua."
            DeliveryStepAction.REFRESH -> "No se pudo confirmar la vigencia de la base en GitHub. Consulta el estado; no repitas las pruebas ni la publicación."
            else -> if (repairingBuild) when(update.state) {
                "QUEUED", "PREPARE_CLAIMED", "UNCERTAIN" -> "Atenea prepara el main fijado y conserva la evidencia del fallo. No repitas la solicitud."
                "READY_TO_RESOLVE", "RESOLVING", "RETRY_REQUESTED" -> "La reparación continúa en esta misma conversación. La PR integrada se conserva; no se publica nada."
                "RESOLVER_COMPLETED", "READY_TO_FINALIZE" -> "La corrección está guardada. Valida esta revisión; después crea su PR de reparación. La PR original no se reabre."
                else -> sourceUpdateLabel(update.state,update.errorCode)
            } else sourceUpdateLabel(update.state, update.errorCode)
        } +
            if (action == DeliveryStepAction.RECOVER_SOURCE)
                " Pulsa Recuperar operación para continuar con la versión exacta aprobada. No crea otra PR ni repite tu prompt."
            else ""
        return DeliveryStepUi(if (stopped) DeliveryStepStatus.ATTENTION
            else if (prepared && action == DeliveryStepAction.REFRESH) DeliveryStepStatus.CHECKING
            else if (action != DeliveryStepAction.NONE) DeliveryStepStatus.ACTION
            else DeliveryStepStatus.WORKING, title, message, action)
    }
    if (!validationCoherent && !sourcePhaseComplete) return DeliveryStepUi(DeliveryStepStatus.CHECKING, "Revisión por confirmar",
        "Falta una lectura coherente de la revisión y sus pruebas. Consulta el estado; no repitas la publicación.", DeliveryStepAction.REFRESH)
    if ((!validated || !validationCurrent) && !sourcePhaseComplete) return DeliveryStepUi(DeliveryStepStatus.ACTION, "Validación pendiente",
        "Primero comprueba esta revisión. Después vendrán PR, integración y publicación.")
    operations.firstOrNull { !it.terminal }?.let { op ->
        if (op.kind == "ANDROID_VERSION") return DeliveryStepUi(
            if (op.state == "READY") DeliveryStepStatus.ACTION
            else if(op.state=="WAITING_CI" && progress?.github?.state!="RUNNING") DeliveryStepStatus.WAITING else DeliveryStepStatus.WORKING,
            if (op.state == "READY") "Versión Android lista para integrar" else "Preparando versión Android",
            if (op.state == "READY") "La PR técnica sólo cambia la versión a ${op.versionName} / ${op.versionCode}. Revisa e integra esa PR; después se construirá la APK. Todavía no se publica."
            else "Atenea prepara y comprueba la PR de versión. Puedes salir de la app; tu PR original se conserva y no se publica ninguna APK.",
            if (op.state == "READY") DeliveryStepAction.INTEGRATE_ANDROID_VERSION else DeliveryStepAction.NONE, op)
        if (op.kind == "RELEASE" && op.state == "READY") {
            return if (op.canConfirm(nowSeconds)) DeliveryStepUi(DeliveryStepStatus.ACTION,
                "${op.target.label}: listo para publicar",
                "La preparación terminó. No se publicará sola: confirma con tu autenticador antes de que caduque.",
                DeliveryStepAction.CONFIRM_RELEASE, op)
            else if (op.expiresAt == null || op.planSha256?.matches(Regex("[0-9a-f]{64}")) != true)
                DeliveryStepUi(DeliveryStepStatus.ATTENTION, "Preparación por confirmar",
                    "Falta evidencia de un plan válido. Consulta el estado; no se puede publicar con esta información.",
                    DeliveryStepAction.REFRESH, op)
            else DeliveryStepUi(DeliveryStepStatus.ATTENTION, "Preparación caducada",
                "No se ha publicado. Atenea está cerrando este plan; después podrás preparar uno nuevo.",
                DeliveryStepAction.REFRESH, op)
        }
        val preparing = op.state in setOf("PLANNING", "PREPARING")
        val title = when {
            op.state == "WAITING_CI" -> "Esperando comprobaciones de GitHub"
            preparing -> "Preparando ${op.target.label}"
            op.kind == "INTEGRATE" -> "Integrando la PR"
            op.kind == "PUBLISH_PR" -> if (sourceUpdate != null) "Actualizando la misma PR" else "Creando la PR"
            op.state == "ROLLING_BACK" -> "Restaurando la versión anterior"
            op.state == "QUARANTINED" -> "Evidencia contradictoria"
            else -> "Publicando ${op.target.label}"
        }
        val github=progress?.takeIf { it.operationId==op.id }?.github
        val status=when {
            op.state=="QUARANTINED" -> DeliveryStepStatus.ATTENTION
            op.state=="WAITING_CI" && (github?.state in setOf("UNAVAILABLE","FAILED") || github!=null && github.observedAt !in (nowSeconds-90)..(nowSeconds+5)) -> DeliveryStepStatus.CHECKING
            op.state=="WAITING_CI" && github?.state !in setOf("RUNNING","FAILED") -> DeliveryStepStatus.WAITING
            else -> DeliveryStepStatus.WORKING
        }
        return DeliveryStepUi(status,
            title, if (op.state == "QUARANTINED") "La operación está detenida. Consulta sus detalles; no repitas la publicación."
            else if (preparing) "Puedes salir de la app. Preparar no publica; cuando termine faltará tu confirmación."
            else "Puedes salir de la app. Atenea continuará con esta operación; no tienes que repetirla.",
            action = if(status==DeliveryStepStatus.CHECKING) DeliveryStepAction.REFRESH else DeliveryStepAction.NONE, operation = op)
    }
    operations.firstOrNull { it.state == "ROLLBACK_FAILED" }?.let {
        return DeliveryStepUi(DeliveryStepStatus.ATTENTION, "Restauración fallida",
            "La publicación sigue bloqueada. Consulta el resultado; no inicies otra operación.", operation = it)
    }
    if (integrated && readiness?.requirementsStatus != "CONFIRMED") return DeliveryStepUi(DeliveryStepStatus.CHECKING,
        "Entregas por confirmar", "Falta confirmar el diff y sus publicaciones. Un backend sano no demuestra una APK publicada.", DeliveryStepAction.REFRESH)
    if (integrated && readiness?.fresh(nowSeconds) != true) return DeliveryStepUi(DeliveryStepStatus.CHECKING,
        "Entregas por confirmar", "La observación ha caducado. Consulta el estado antes de continuar; no se publica ni se reintenta nada.", DeliveryStepAction.REFRESH)
    if (integrated && !allRequiredDelivered && build?.state == "FAILED") return DeliveryStepUi(DeliveryStepStatus.ATTENTION,
        "La construcción de la versión falló", "No hay un despliegue trabajando. Tu PR integrada se conserva. " +
            "Consulta el diagnóstico de abajo y solicita una reparación en la misma conversación. Repetir Preparar no corrige el código.",
        if (canRepairBuild(runInProgress)) DeliveryStepAction.REPAIR_BUILD else DeliveryStepAction.REFRESH)
    if (integrated && !allRequiredDelivered && build?.state == "PENDING") return DeliveryStepUi(
        when(progress?.takeIf { it.kind=="BUILD" }?.github?.state) {
            "NOT_STARTED","QUEUED","CLOSING","PASSED" -> DeliveryStepStatus.WAITING
            "UNAVAILABLE","FAILED" -> DeliveryStepStatus.CHECKING
            else -> DeliveryStepStatus.WORKING
        },
        "GitHub está construyendo la versión", "La PR ya está integrada. Falta terminar la construcción publicable; todavía no se despliega. Puedes salir de la app.",
        DeliveryStepAction.REFRESH)
    if (integrated && !allRequiredDelivered && build?.errorCode != null) return DeliveryStepUi(DeliveryStepStatus.CHECKING,
        "Construcción por confirmar", "No se pudo leer el estado de GitHub. Consulta otra vez; no se solicita otra preparación.", DeliveryStepAction.REFRESH)
    if (operations.firstOrNull { it.kind == "INTEGRATE" }?.errorCode == "PR_BASE_VALIDATION_MOVED") return DeliveryStepUi(
        DeliveryStepStatus.ATTENTION,"Main avanzó después de las pruebas", "Actualiza la base, valida y actualiza la misma PR antes de integrar.",DeliveryStepAction.UPDATE_BASE)
    if (integrated && readiness?.requiredTargets?.contains(MobileDeliveryTarget.APP_PROD) == true && !appDeployed) {
        if (!canPrepareBackend) return DeliveryStepUi(DeliveryStepStatus.CHECKING, "Backend PROD por confirmar",
            deploymentLabel(deployment, false), DeliveryStepAction.REFRESH)
        val release = operations.firstOrNull { it.kind == "RELEASE" && it.target == MobileDeliveryTarget.APP_PROD }
        if (release?.errorCode == "RELEASE_BUILD_FAILED" && build == null) return DeliveryStepUi(DeliveryStepStatus.ATTENTION,
            "Construcción fallida", "Consulta el diagnóstico de la construcción; no repitas Preparar Backend PROD.",DeliveryStepAction.REFRESH,release)
        val currentError = release?.errorCode != null && !(release.errorCode == "RELEASE_BUILD_FAILED" && build?.state == "READY")
        return DeliveryStepUi(if (currentError) DeliveryStepStatus.ATTENTION else DeliveryStepStatus.ACTION,
            "Publicar Backend PROD", "Primero publica el backend que requiere este cambio. Después Atenea te guiará con Android, si también hace falta. Preparar no despliega.",
            DeliveryStepAction.PREPARE_BACKEND, release)
    }
    if (integrated && readiness?.requiredTargets?.contains(MobileDeliveryTarget.ANDROID_STABLE) == true
        && (readiness?.androidStatus == "UNAVAILABLE" || readiness?.android == null
            || readiness?.android?.observedAt?.let { it !in (nowSeconds - 60)..(nowSeconds + 5) } == true)) return DeliveryStepUi(DeliveryStepStatus.CHECKING,
        "APK por confirmar", "No se pudo leer la evidencia del canal Android. Consulta el estado; no se da por publicada.", DeliveryStepAction.REFRESH)
    if (integrated && readiness?.requiredTargets?.contains(MobileDeliveryTarget.ANDROID_STABLE) == true
        && !targetDelivered(MobileDeliveryTarget.ANDROID_STABLE)) {
        val release = operations.firstOrNull { it.kind == "RELEASE" && it.target == MobileDeliveryTarget.ANDROID_STABLE }
        val version = operations.firstOrNull { it.kind == "ANDROID_VERSION" }
        val preparedAfterFailure = version?.state == "SUCCEEDED" && (release == null || operations.indexOf(version) < operations.indexOf(release))
        if (version?.state in setOf("BLOCKED", "FAILED") && (release == null || operations.indexOf(version) < operations.indexOf(release))
            && version?.errorCode !in setOf("CANONICAL_MAIN_MOVED", "ANDROID_CHANNEL_MOVED")) return DeliveryStepUi(DeliveryStepStatus.ATTENTION,
            "Preparación de versión detenida", deliveryErrorLabel(version?.errorCode ?: "ANDROID_VERSION_IDENTITY_MISMATCH"), DeliveryStepAction.REFRESH, version)
        if (release?.errorCode == "APK_VERSION_NOT_NEWER" && !preparedAfterFailure
            || version?.errorCode in setOf("CANONICAL_MAIN_MOVED", "ANDROID_CHANNEL_MOVED")) return DeliveryStepUi(DeliveryStepStatus.ATTENTION,
            "Android necesita una versión nueva",
            if (androidVersionPreparationEnabled) "La APK candidata no supera la del canal. Prepara una PR que sólo incremente la versión; Atenea comprobará sus tests antes de que la integres. Tu cambio original se conserva."
            else "La APK candidata no supera la del canal. El backend necesita la capacidad de preparar la versión; consultar no cambia el número ni reintenta la publicación.",
            if (androidVersionPreparationEnabled) DeliveryStepAction.PREPARE_ANDROID_VERSION else DeliveryStepAction.REFRESH, release)
        if (version?.state in setOf("BLOCKED", "FAILED") && !preparedAfterFailure) return DeliveryStepUi(DeliveryStepStatus.ATTENTION,
            "Preparación de versión detenida", deliveryErrorLabel(version?.errorCode ?: "ANDROID_VERSION_IDENTITY_MISMATCH"), DeliveryStepAction.REFRESH, version)
        if (release?.state in setOf("BLOCKED", "FAILED", "ROLLED_BACK") && !preparedAfterFailure
            && release?.errorCode !in setOf("PLAN_EXPIRED", "CANONICAL_MAIN_MOVED")) return DeliveryStepUi(DeliveryStepStatus.ATTENTION,
            "Android estable: necesita atención", deliveryErrorLabel(release?.errorCode ?: "RELEASE_EVIDENCE_MISMATCH"), DeliveryStepAction.REFRESH, release)
        return DeliveryStepUi(DeliveryStepStatus.ACTION, "Publicar Android estable",
            "Falta una APK que contenga este cambio. Preparar obtiene el artefacto comprobado; después confirmarás su publicación. Si falta incrementar la versión, Atenea mostrará ese paso por separado.", DeliveryStepAction.PREPARE_ANDROID)
    }
    if (allRequiredDelivered) return DeliveryStepUi(DeliveryStepStatus.ACTION, "Entregas del cambio: confirmadas",
        "Las publicaciones que requiere este diff contienen el cambio. Ya puedes finalizar el ticket; instalar la APK en el móvil es una acción separada.",
        if (completionEnabled) DeliveryStepAction.FINALIZE else DeliveryStepAction.NONE)
    if (integrated) return DeliveryStepUi(DeliveryStepStatus.CHECKING, "Entregas por confirmar",
        "Falta evidencia vigente de las publicaciones requeridas. Consulta el estado; no se confirma una entrega por su historial.", DeliveryStepAction.REFRESH)
    if (integration?.mergeState == "CONFLICTS") return DeliveryStepUi(DeliveryStepStatus.ACTION,
        "Resolver conflictos con main", deliveryIntegrationLabel("CONFLICTS"), DeliveryStepAction.RESOLVE)
    if (operations.any { it.kind == "PUBLISH_PR" && it.state == "SUCCEEDED" }) {
        progress?.takeIf { it.kind=="PR_CHECKS" }?.github?.let { checks ->
            return DeliveryStepUi(when(checks.state) {
                "FAILED" -> DeliveryStepStatus.ATTENTION
                "UNAVAILABLE" -> DeliveryStepStatus.CHECKING
                "RUNNING" -> DeliveryStepStatus.WORKING
                else -> DeliveryStepStatus.WAITING
            },"Comprobando la PR en GitHub",
                if(checks.state=="FAILED") "Una comprobación no ha pasado. Consulta el detalle y Ayuda con este cambio; no se ha integrado ni publicado."
                else "La PR ya existe. Atenea sigue comprobando esta revisión antes de ofrecer Integrar cambio. Puedes salir de la app.",
                if(checks.state in setOf("FAILED","UNAVAILABLE")) DeliveryStepAction.REFRESH else DeliveryStepAction.NONE,
                operations.firstOrNull { it.id==progress?.operationId })
        }
        return DeliveryStepUi(if (integration?.allowsRequest == true) DeliveryStepStatus.ACTION else DeliveryStepStatus.CHECKING,
            "Integrar cambio", deliveryIntegrationLabel(integration?.mergeState),
            if (integration?.allowsRequest == true) DeliveryStepAction.INTEGRATE else DeliveryStepAction.REFRESH)
    }
    val pr = operations.firstOrNull { it.kind == "PUBLISH_PR" }
    return DeliveryStepUi(if (pr?.errorCode != null) DeliveryStepStatus.ATTENTION else DeliveryStepStatus.ACTION,
        "Crear PR", pr?.errorCode?.let(::deliveryErrorLabel)
            ?: "La validación está completada. Crea la PR de esta revisión para continuar; no despliega el cambio.",
        DeliveryStepAction.CREATE_PR, pr)
}

@Composable
internal fun DeliveryStepCard(step: DeliveryStepUi, primaryAction: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().testTag("change-current-step"),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(step.status.label, style = MaterialTheme.typography.labelLarge,
                color = if (step.status == DeliveryStepStatus.ATTENTION) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary)
            Text(step.title, style = MaterialTheme.typography.titleMedium)
            Text(step.message, style = MaterialTheme.typography.bodyMedium)
            if (step.status in setOf(DeliveryStepStatus.WORKING, DeliveryStepStatus.WAITING)) LinearProgressIndicator(Modifier.fillMaxWidth())
            primaryAction()
        }
    }
}
