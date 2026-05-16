package com.atenea.android.coreconsole

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceSpeechSafetyTest {
    @Test
    fun removesWakeWordFromSpokenControlHints() {
        val speech = "Nota abierta. Cierra con Atenea fin.".forRealtimeSpeech()

        assertFalse(speech.contains("Atenea fin", ignoreCase = true))
        assertTrue(speech.contains("orden de cierre", ignoreCase = true))
    }

    @Test
    fun removesWakeWordFromConfirmationPrompts() {
        val speech = "Di Atenea confirmo para enviar o Atenea cancela para no enviar.".forRealtimeSpeech()

        assertFalse(speech.contains("Atenea confirmo", ignoreCase = true))
        assertFalse(speech.contains("Atenea cancela", ignoreCase = true))
        assertTrue(speech.contains("confirma", ignoreCase = true))
    }
}
