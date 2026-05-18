package com.atenea.android.voiceruntime

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.StateFlow

class AteneaVoiceRuntimeController(
    context: Context
) {
    private val appContext = context.applicationContext

    val state: StateFlow<AteneaVoiceRuntimeState> = AteneaVoiceRuntimeStateStore.state

    fun start() {
        val intent = Intent(appContext, AteneaVoiceRuntimeService::class.java)
            .setAction(AteneaVoiceRuntimeService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun stop() {
        if (!state.value.serviceActive &&
            !state.value.captureActive &&
            !state.value.realtimeConnected &&
            !state.value.outputPlaybackActive
        ) {
            return
        }
        val intent = Intent(appContext, AteneaVoiceRuntimeService::class.java)
            .setAction(AteneaVoiceRuntimeService.ACTION_STOP)
        appContext.startService(intent)
    }

    fun connectRealtime(
        clientSecret: String,
        model: String,
        voice: String
    ) {
        val intent = Intent(appContext, AteneaVoiceRuntimeService::class.java)
            .setAction(AteneaVoiceRuntimeService.ACTION_CONNECT_REALTIME)
            .putExtra(AteneaVoiceRuntimeService.EXTRA_CLIENT_SECRET, clientSecret)
            .putExtra(AteneaVoiceRuntimeService.EXTRA_MODEL, model)
            .putExtra(AteneaVoiceRuntimeService.EXTRA_VOICE, voice)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun disconnectRealtime() {
        if (!state.value.serviceActive &&
            !state.value.captureActive &&
            !state.value.realtimeConnected
        ) {
            return
        }
        val intent = Intent(appContext, AteneaVoiceRuntimeService::class.java)
            .setAction(AteneaVoiceRuntimeService.ACTION_DISCONNECT_REALTIME)
        appContext.startService(intent)
    }

    fun cancelRealtimeResponse() {
        val intent = Intent(appContext, AteneaVoiceRuntimeService::class.java)
            .setAction(AteneaVoiceRuntimeService.ACTION_CANCEL_REALTIME_RESPONSE)
        appContext.startService(intent)
    }

    fun speakText(text: String) {
        val intent = Intent(appContext, AteneaVoiceRuntimeService::class.java)
            .setAction(AteneaVoiceRuntimeService.ACTION_SPEAK_TEXT)
            .putExtra(AteneaVoiceRuntimeService.EXTRA_SPEAK_TEXT, text)
        appContext.startService(intent)
    }

    fun setOutputVolume(volume: Float) {
        val intent = Intent(appContext, AteneaVoiceRuntimeService::class.java)
            .setAction(AteneaVoiceRuntimeService.ACTION_SET_OUTPUT_VOLUME)
            .putExtra(AteneaVoiceRuntimeService.EXTRA_OUTPUT_VOLUME, volume)
        appContext.startService(intent)
    }
}
