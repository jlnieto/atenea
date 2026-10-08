package com.atenea.android.coreconsole

internal enum class ConversationPane(val title: String) {
    CHAT("Conversación"), CHANGE("Cambio"), ACTIVITY("Detalle de ejecución"), PROFILE("Configuración de Codex")
}

internal fun conversationStatusLabel(
    runInProgress: Boolean,
    validationState: String?,
    sessionStatus: String?,
    runStatus: String? = null,
    validationInProgress: Boolean = false
): String = when {
    runInProgress -> "Codex trabajando"
    validationInProgress -> "Validando cambio"
    runStatus in setOf("FAILED", "BLOCKED", "NEEDS_ATTENTION") -> "Ejecución necesita atención · ⋮"
    validationState == "BLOCKED" -> "Validación bloqueada"
    validationState == "CURRENT" -> "Cambio validado"
    validationState == "STALE" -> "Cambios pendientes de validar"
    validationState != null -> "Pendiente de validación"
    sessionStatus == "OPEN" -> "Conversación abierta"
    sessionStatus == "CLOSED" -> "Conversación cerrada"
    else -> sessionStatus ?: "Cargando conversación…"
}
