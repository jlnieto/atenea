package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.atenea.android.api.DevelopmentChangeValidationEvidence

@Composable
internal fun WorkSessionChangePanel(
    sessionId: Long,
    title: String,
    validation: DevelopmentChangeValidationUiState,
    evidence: DevelopmentChangeValidationEvidence?,
    loading: Boolean,
    readError: String?,
    onReadEvidence: () -> Unit,
    onValidate: () -> Unit,
    completed: Boolean = false,
    runInProgress: Boolean = false,
    deliveryBeforeValidation: Boolean = false,
    deliveryContent: @Composable () -> Unit
) {
    var showValidationDetails by remember(sessionId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("Cambio · sesión $sessionId", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (validation.current || completed || deliveryBeforeValidation) {
            // Current delivery and its primary button appear before completed validation.
            deliveryContent()
            Text(if (validation.current) "✓ Validación de esta revisión completada"
                else if (deliveryBeforeValidation) "Validación pendiente · después de recuperar esta operación"
                else "Validación · historial",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val status = when {
                runInProgress || validation.inProgress -> DeliveryStepStatus.WORKING
                validation.needsAttention -> DeliveryStepStatus.ATTENTION
                else -> DeliveryStepStatus.ACTION
            }
            DeliveryStepCard(DeliveryStepUi(status,
                if (runInProgress) "Codex está trabajando" else if (validation.inProgress) "Validando cambio" else "Validar cambio",
                if (runInProgress) "Puedes salir de la app. Espera al resultado; no repitas el prompt."
                else if (validation.inProgress) "Atenea está comprobando esta revisión. Puedes salir de la app; no repitas la validación."
                else validation.message)) {
                if (validation.visible && !runInProgress) AteneaButton(validation.label,
                    enabled = validation.canStart, onClick = onValidate)
            }
        }
        TextButton(onClick = { showValidationDetails = !showValidationDetails }) {
            Text(if (showValidationDetails) "Ocultar pruebas y detalles" else "Ver pruebas y detalles")
        }
        if (showValidationDetails) {
            HorizontalDivider()
            ValidationEvidenceDetails(evidence, loading, readError, onReadEvidence)
        }
    }
}

@Composable
private fun ValidationEvidenceDetails(evidence: DevelopmentChangeValidationEvidence?, loading: Boolean,
    readError: String?, onReadEvidence: () -> Unit) {
    Text("Resultados guardados · consultar no ejecuta pruebas", style = MaterialTheme.typography.titleSmall)
    if (evidence != null) {
        Text("${evidence.passedOperations}/${evidence.requiredOperations} comprobaciones de la revisión ${evidence.sourceRevision}")
        val failure = evidence.operations.firstOrNull { it.status in setOf("FAILED", "BLOCKED") }
        val reason = failure ?: evidence.lastAttempt
        reason?.let { attempt ->
            Text(if (failure != null) "Fallo guardado" else "Último intento guardado",
                style = MaterialTheme.typography.titleSmall)
            if (attempt.sourceTreeFingerprintSha256 != evidence.sourceFingerprintSha256) {
                Text("Resultado de una revisión anterior: no valida el código actual.", color = ConversationColors.warning)
            }
            if (failure == null && evidence.validationState == "BLOCKED" && attempt.status == "SUCCEEDED") {
                Text("Este intento pasó, pero no explica el bloqueo actual. No hay una causa específica guardada en esta evidencia.",
                    color = ConversationColors.warning)
            }
            Text("${validationOperationLabel(attempt.operation)} · ${validationAttemptStatusLabel(attempt.status)}")
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(attempt.summary.ifBlank { "No se conservó un motivo detallado." })
                    attempt.errorCode?.let { Text("Código: $it") }
                    attempt.exitCode?.let { Text("Exit code: $it") }
                    Text("Operation ID: ${attempt.id}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (evidence.operations.isEmpty() && evidence.lastAttempt == null) Text("No hay intentos de validación guardados.")
    } else if (loading) {
        Text("Consultando el resultado guardado…")
    }
    readError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    AteneaOutlinedButton("Consultar resultado", enabled = !loading, onClick = onReadEvidence)
    Text("Consultar no ejecuta ni reintenta pruebas.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun validationOperationLabel(operation: String): String = when (operation) {
    "BACKEND_TEST" -> "Tests backend"
    "WEB_BUILD" -> "Build web"
    "ANDROID_BUILD" -> "Build Android"
    "PLAYWRIGHT_ACCEPTANCE" -> "Aceptación web"
    else -> operation
}

internal fun validationAttemptStatusLabel(status: String): String = when (status) {
    "SUCCEEDED" -> "PASS"
    "RUNNING" -> "En curso"
    "BLOCKED" -> "Bloqueada"
    "FAILED" -> "Fallida"
    else -> status
}
