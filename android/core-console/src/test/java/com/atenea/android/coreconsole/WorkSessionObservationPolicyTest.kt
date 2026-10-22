package com.atenea.android.coreconsole

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkSessionObservationPolicyTest {
    @Test fun `idle view backs off while an action changes immediately to active cadence`() {
        val idle = WorkSessionUiState(29)
        assertEquals(30_000L, idle.pollDelayMillis)
        assertEquals(3_000L, idle.copy(pendingAction = "Acción existente").pollDelayMillis)
    }

    @Test fun `failed reads have a slower cadence even when the last snapshot was active`() {
        val active = WorkSessionUiState(29, pendingAction = "Acción existente")
        assertEquals(15_000L, active.copy(stale = true).pollDelayMillis)
        assertEquals(15_000L, active.copy(error = "Lectura no confirmada").pollDelayMillis)
        assertEquals(3_000L, active.pollDelayMillis)
    }
}
