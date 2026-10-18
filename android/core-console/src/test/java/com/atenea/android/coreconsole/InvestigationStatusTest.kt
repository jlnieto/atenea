package com.atenea.android.coreconsole

import kotlin.test.*
import org.junit.Test

class InvestigationStatusTest {
    @Test fun queuedDoesNotMeanCodexHasStarted() {
        assertEquals("Pendiente de arrancar", investigationHeading("QUEUED"))
        assertTrue(investigationExplanation("QUEUED").contains("todavía no ha arrancado"))
        assertFalse(investigationHeading("INVESTIGATED").contains("terminada"))
    }
    @Test fun terminalAttentionAndUnknownHaveDifferentTruthfulStates() {
        assertEquals("Investigación terminada", investigationHeading("SUCCEEDED"))
        assertTrue(investigationExplanation("SUCCEEDED").contains("no valida ni publica"))
        assertTrue(investigationExplanation("SUCCEEDED").contains("no confirma una corrección"))
        for (state in listOf("FAILED", "CANCELLED")) assertTrue(investigationHeading(state).contains("atención"))
        assertFalse(investigationHeading("RECONCILING").contains("Codex"))
        assertTrue(investigationExplanation(null).contains("No se ha confirmado"))
    }
}
