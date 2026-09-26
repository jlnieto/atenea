package com.atenea.android.coreconsole

import com.atenea.android.api.MobileWorkSession

internal data class DevelopmentChangeValidationUiState(
    val visible: Boolean,
    val canStart: Boolean,
    val label: String,
    val message: String
)

internal fun developmentChangeValidationUiState(
    session: MobileWorkSession?,
    runInProgress: Boolean,
    validationPending: Boolean,
    operationNotice: String?
): DevelopmentChangeValidationUiState {
    if (session?.developmentChangeKey == null) {
        return DevelopmentChangeValidationUiState(false, false, "Validar cambio", "")
    }
    val current = session.developmentChangeValidationState == "CURRENT"
    val dirty = session.developmentChangeSourceState == "DIRTY"
    val message = operationNotice ?: when (session.developmentChangeValidationState) {
        "CURRENT" -> "Cambio validado para la revisión actual."
        "STALE" -> "El código cambió desde la última validación."
        "BLOCKED" -> "La validación necesita atención."
        else -> if (!dirty) {
            "La validación estará disponible cuando Codex haya producido cambios."
        } else {
            "Valida el cambio antes de publicarlo."
        }
    }
    return DevelopmentChangeValidationUiState(
        visible = true,
        canStart = dirty && !current && !runInProgress && !validationPending,
        label = if (validationPending) "Validando…" else "Validar cambio",
        message = message
    )
}
