package com.atenea.android.coreconsole

import kotlin.test.*
import org.junit.Test

class RunObservationTest {
    @Test fun oldCompletedReplyCannotReplaceTheNewInvestigationOrEnableTheComposer() {
        assertFalse(acceptsRunObservation(125, 126))
        assertFalse(acceptsRunObservation(null, 126))
        assertTrue(acceptsRunObservation(126, 126))
        assertTrue(acceptsRunObservation(127, 126))
    }
    @Test fun firstObservationMayHaveNoRunYet() {
        assertTrue(acceptsRunObservation(null, null))
        assertTrue(acceptsRunObservation(125, null))
    }
}
