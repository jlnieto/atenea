package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    deliveryContent: @Composable () -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text("Misma WorkSession $sessionId", color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    Text("1 · Validación", style = MaterialTheme.typography.titleSmall)
    Text(validation.message)
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
    // Cached evidence must never hide a new validation required by the session projection.
    if (validation.visible && !validation.current) {
        AteneaButton(validation.label, enabled = validation.canStart, onClick = onValidate)
    }
    Text("Consultar no ejecuta ni reintenta pruebas.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    deliveryContent()
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
