package com.atenea.android.voiceruntime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object AteneaVoiceRuntimeStateStore {
    private val _state = MutableStateFlow(AteneaVoiceRuntimeState())

    val state: StateFlow<AteneaVoiceRuntimeState> = _state

    fun update(transform: (AteneaVoiceRuntimeState) -> AteneaVoiceRuntimeState) {
        _state.update { transform(it).copy(updatedAtMillis = System.currentTimeMillis()) }
    }

    fun event(message: String) {
        update { it.copy(lastEvent = message) }
    }
}
