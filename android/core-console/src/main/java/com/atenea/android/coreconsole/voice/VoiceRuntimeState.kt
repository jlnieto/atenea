package com.atenea.android.coreconsole.voice

internal enum class VoiceRuntimeState(val label: String) {
    IDLE("En espera"),
    STARTING("Preparando"),
    LISTENING("Escuchando"),
    PROCESSING("Procesando"),
    SPEAKING("Hablando")
}
