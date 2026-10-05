package com.atenea.android.coreconsole

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationUiStateTest {
    @Test
    fun `completed run must not hide blocked validation`() {
        assertEquals("Validación bloqueada", conversationStatusLabel(false, "BLOCKED", "OPEN"))
        assertEquals("Cambio validado", conversationStatusLabel(false, "CURRENT", "OPEN"))
        assertEquals("Cambios pendientes de validar", conversationStatusLabel(false, "STALE", "OPEN"))
        assertEquals("Ejecución necesita atención · ⋮", conversationStatusLabel(false, "NOT_STARTED", "OPEN", "FAILED"))
        assertEquals("Validando cambio", conversationStatusLabel(false, "BLOCKED", "OPEN", validationInProgress = true))
    }

    @Test
    fun `ongoing run has priority while validation remains in Change pane`() {
        assertEquals("Codex trabajando", conversationStatusLabel(true, "BLOCKED", "OPEN"))
        assertEquals("Conversación abierta", conversationStatusLabel(false, null, "OPEN"))
        assertEquals("Conversación cerrada", conversationStatusLabel(false, null, "CLOSED"))
    }

    @Test
    fun `four panes have separate readable roles`() {
        assertEquals(listOf("Conversación", "Cambio", "Detalle de ejecución", "Configuración de Codex"),
            ConversationPane.entries.map { it.title })
    }

    @Test
    fun `validation evidence labels preserve exact phase and status`() {
        assertEquals("Tests backend", validationOperationLabel("BACKEND_TEST"))
        assertEquals("Build Android", validationOperationLabel("ANDROID_BUILD"))
        assertEquals("Bloqueada", validationAttemptStatusLabel("BLOCKED"))
        assertEquals("Fallida", validationAttemptStatusLabel("FAILED"))
    }
}
