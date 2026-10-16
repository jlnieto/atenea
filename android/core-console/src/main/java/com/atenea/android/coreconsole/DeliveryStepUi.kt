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
    WORKING("Atenea está trabajando"), ACTION("Te toca a ti"), ATTENTION("Necesita atención"),
    COMPLETE("Terminado"), CHECKING("Estado por confirmar")
}

internal enum class DeliveryStepAction {
    NONE, CREATE_PR, INTEGRATE, CONFIRM_RELEASE, PREPARE_BACKEND, PREPARE_ANDROID, FINALIZE, RECONCILE_FINALIZE,
    RESOLVE, UPDATE_PR, RETRY_RESOLVER, RECOVER_SOURCE, REFRESH
}

/** A presentation of current evidence, never an authorization or an automatic action. */
internal data class DeliveryStepUi(val status: DeliveryStepStatus, val title: String, val message: String,
    val action: DeliveryStepAction = DeliveryStepAction.NONE, val operation: MobileDeliveryOperation? = null)

internal fun MobileDeliveryUiState.currentStep(validated: Boolean, runInProgress: Boolean,
    nowSeconds: Long = System.currentTimeMillis() / 1000): DeliveryStepUi {
    if (completed) return DeliveryStepUi(DeliveryStepStatus.COMPLETE, "Cambio completado",
        "El ticket está cerrado. La conversación y los recibos se conservan como historia.")
    if (loadError != null || !available) return DeliveryStepUi(DeliveryStepStatus.CHECKING,
        if (!loaded && loadError == null) "Consultando estado" else "Entrega no disponible",
        loadError ?: if (loaded) "La entrega no está habilitada. Ayuda explica los permisos y capacidades necesarios."
            else "Atenea está leyendo el estado. Consultar no inicia operaciones.", DeliveryStepAction.REFRESH)
    if (busy) return DeliveryStepUi(DeliveryStepStatus.WORKING, "Registrando tu acción",
        "Atenea está comprobando la solicitud. No vuelvas a pulsar; se conserva la misma operación.")
    if (runInProgress) return DeliveryStepUi(DeliveryStepStatus.WORKING, "Codex está trabajando",
        "Espera al resultado. Puedes salir de la app; no hace falta enviar otra vez el prompt.")
    completion?.let {
        return if (it.state == "BLOCKED") DeliveryStepUi(DeliveryStepStatus.ATTENTION, "Finalización detenida",
            deliveryErrorLabel(it.errorCode ?: "COMPLETION_EVIDENCE_MISMATCH"), DeliveryStepAction.RECONCILE_FINALIZE, it)
        else DeliveryStepUi(DeliveryStepStatus.WORKING, "Finalizando cambio",
            "Puedes salir de la app. Atenea cerrará la misma WorkSession y conservará la conversación.", operation = it)
    }
    sourceUpdate?.takeIf { !sourcePhaseComplete && it.state != "PUBLISHED"
        && (validated || it.state !in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE")) }?.let { update ->
        val action = when {
            canRecoverSource(validated, false) -> DeliveryStepAction.RECOVER_SOURCE
            canRetryResolver(false) -> DeliveryStepAction.RETRY_RESOLVER
            update.state in setOf("RESOLVER_COMPLETED", "READY_TO_FINALIZE") -> DeliveryStepAction.UPDATE_PR
            else -> DeliveryStepAction.NONE
        }
        val stopped = update.state in setOf("BLOCKED", "FAILED", "ATTENTION") || action == DeliveryStepAction.RECOVER_SOURCE
        val title = when {
            stopped -> "Actualización detenida"
            update.state in setOf("QUEUED", "PREPARE_CLAIMED") -> "Preparando código y conflictos"
            else -> "Actualizar la misma PR"
        }
        val message = sourceUpdateLabel(update.state, update.errorCode) +
            if (action == DeliveryStepAction.RECOVER_SOURCE)
                " Pulsa Recuperar operación para continuar con la versión exacta aprobada. No crea otra PR ni repite tu prompt."
            else ""
        return DeliveryStepUi(if (stopped) DeliveryStepStatus.ATTENTION
            else if (action != DeliveryStepAction.NONE) DeliveryStepStatus.ACTION
            else DeliveryStepStatus.WORKING, title, message, action)
    }
    if (!validated && !sourcePhaseComplete) return DeliveryStepUi(DeliveryStepStatus.ACTION, "Validación pendiente",
        "Primero comprueba esta revisión. Después vendrán PR, integración y publicación.")
    operations.firstOrNull { !it.terminal }?.let { op ->
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
            op.kind == "PUBLISH_PR" -> "Creando la PR"
            op.state == "ROLLING_BACK" -> "Restaurando la versión anterior"
            op.state == "QUARANTINED" -> "Evidencia contradictoria"
            else -> "Publicando ${op.target.label}"
        }
        return DeliveryStepUi(if (op.state == "QUARANTINED") DeliveryStepStatus.ATTENTION else DeliveryStepStatus.WORKING,
            title, if (op.state == "QUARANTINED") "La operación está detenida. Consulta sus detalles; no repitas la publicación."
            else if (preparing) "Puedes salir de la app. Preparar no publica; cuando termine faltará tu confirmación."
            else "Puedes salir de la app. Atenea continuará con esta operación; no tienes que repetirla.", operation = op)
    }
    operations.firstOrNull { it.state == "ROLLBACK_FAILED" }?.let {
        return DeliveryStepUi(DeliveryStepStatus.ATTENTION, "Restauración fallida",
            "La publicación sigue bloqueada. Consulta el resultado; no inicies otra operación.", operation = it)
    }
    operations.filter { it.kind == "RELEASE" && it.target != MobileDeliveryTarget.APP_PROD }
        .distinctBy { it.target }.firstOrNull { it.state in setOf("FAILED", "BLOCKED", "ROLLED_BACK") }?.let {
            return DeliveryStepUi(DeliveryStepStatus.ATTENTION, "${it.target.label}: necesita atención",
                (it.errorCode?.let(::deliveryErrorLabel) ?: "La última publicación de este destino no terminó correctamente.") +
                    " Un backend sano no confirma la entrega de Android o Worker. Consulta esta operación en Otras publicaciones y sus detalles.",
                DeliveryStepAction.REFRESH, it)
        }
    if (integrated && readiness?.requirementsStatus != "CONFIRMED") return DeliveryStepUi(DeliveryStepStatus.CHECKING,
        "Entregas por confirmar", "Falta confirmar el diff y sus publicaciones. Un backend sano no demuestra una APK publicada.", DeliveryStepAction.REFRESH)
    if (integrated && readiness?.requiredTargets?.contains(MobileDeliveryTarget.ANDROID_STABLE) == true
        && readiness?.androidStatus == "UNAVAILABLE") return DeliveryStepUi(DeliveryStepStatus.CHECKING,
        "APK por confirmar", "No se pudo leer la evidencia del canal Android. Consulta el estado; no se da por publicada.", DeliveryStepAction.REFRESH)
    if (integrated && readiness?.requiredTargets?.contains(MobileDeliveryTarget.ANDROID_STABLE) == true
        && readiness?.androidStatus != "INCLUDED") return DeliveryStepUi(DeliveryStepStatus.ACTION,
        "Publicar Android estable", "Este cambio modifica Android. Falta una APK que lo contenga; preparar no publica. La versión se genera automáticamente.", DeliveryStepAction.PREPARE_ANDROID)
    if (allRequiredDelivered) return DeliveryStepUi(DeliveryStepStatus.ACTION, "Entregas del cambio: confirmadas",
        "Las publicaciones que requiere este diff contienen el cambio. Ya puedes finalizar el ticket; instalar la APK en el móvil es una acción separada.",
        if (completionEnabled) DeliveryStepAction.FINALIZE else DeliveryStepAction.NONE)
    if (integrated) {
        if (!canPrepareBackend) return DeliveryStepUi(DeliveryStepStatus.CHECKING, "Despliegue por confirmar",
            deploymentLabel(deployment, false), DeliveryStepAction.REFRESH)
        val release = operations.firstOrNull { it.kind == "RELEASE" && it.target == MobileDeliveryTarget.APP_PROD }
        return DeliveryStepUi(if (release?.errorCode != null) DeliveryStepStatus.ATTENTION else DeliveryStepStatus.ACTION,
            "Publicar Backend PROD", release?.errorCode?.let(::deliveryErrorLabel)
                ?: "Tu PR ya está integrada. Prepara la versión aprobada; después podrás confirmarla. Preparar no despliega.",
            DeliveryStepAction.PREPARE_BACKEND, release)
    }
    if (integration?.mergeState == "CONFLICTS") return DeliveryStepUi(DeliveryStepStatus.ACTION,
        "Resolver conflictos con main", deliveryIntegrationLabel("CONFLICTS"), DeliveryStepAction.RESOLVE)
    if (operations.any { it.kind == "PUBLISH_PR" && it.state == "SUCCEEDED" }) {
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
            if (step.status == DeliveryStepStatus.WORKING) LinearProgressIndicator(Modifier.fillMaxWidth())
            primaryAction()
        }
    }
}
