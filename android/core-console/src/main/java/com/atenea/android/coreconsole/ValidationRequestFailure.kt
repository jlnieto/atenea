package com.atenea.android.coreconsole

import androidx.compose.runtime.saveable.listSaver
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.DevelopmentChangeValidationEvidence

/** Local request result, not a failed check or authority to start another one. */
internal data class ValidationRequestFailure(
    val sessionId: Long,
    val changeKey: String,
    val revision: Long,
    val fingerprint: String?,
    val message: String,
    val rejected: Boolean
) {
    fun matches(evidence: DevelopmentChangeValidationEvidence?) = evidence != null
        && evidence.workSessionId == sessionId && evidence.changeKey.toString() == changeKey
        && evidence.sourceRevision == revision && evidence.sourceFingerprintSha256 == fingerprint

    companion object {
        // Only bounded presentation facts. Restoring/refreshing never sends a POST.
        val Saver = listSaver<ValidationRequestFailure?, Any>(
            save = { it?.let { value -> listOf(value.sessionId, value.changeKey, value.revision,
                value.fingerprint.orEmpty(), value.message, value.rejected) } ?: emptyList() },
            restore = { values -> if (values.size == 6) ValidationRequestFailure(values[0] as Long,
                values[1] as String, values[2] as Long, (values[3] as String).ifBlank { null },
                values[4] as String, values[5] as Boolean) else null }
        )

        fun capture(evidence: DevelopmentChangeValidationEvidence, failure: Exception): ValidationRequestFailure {
            val rejected = failure is AteneaApiException && failure.status in 400..499 && failure.status != 408
            val message = if (rejected) {
                val detail = sourceBaseFailureMessage(failure.message) ?: when (failure.message) {
                    "Acceptance projection does not own the current source tree; validation state was not promoted" ->
                        "Atenea no pudo asociar la revisión a esta sesión antes de iniciar las pruebas. Tu código se conserva."
                    "VALIDATION_SOURCE_CHANGED" -> "La revisión cambió. Consulta el estado antes de validar de nuevo."
                    else -> failure.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(500)
                        ?: "Consulta el estado o abre Ayuda con este cambio."
                }
                "El servidor no aceptó esta solicitud de validación. $detail"
            } else {
                "No se pudo confirmar la solicitud de validación. Consulta el estado; si necesitas recuperarla, " +
                    "Recuperar solicitud utiliza el mismo identificador, sin duplicar la validación."
            }
            return ValidationRequestFailure(evidence.workSessionId, evidence.changeKey.toString(), evidence.sourceRevision,
                evidence.sourceFingerprintSha256, message, rejected)
        }
    }
}
