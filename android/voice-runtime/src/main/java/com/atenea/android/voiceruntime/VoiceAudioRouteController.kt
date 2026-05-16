package com.atenea.android.voiceruntime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

internal class VoiceAudioRouteController(
    context: Context,
    private val onRouteChanged: (VoiceAudioRoute) -> Unit,
    private val onAudioFocusLost: () -> Unit,
    private val onEvent: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager: AudioManager?
        get() = appContext.getSystemService(AudioManager::class.java)
    private var previousAudioMode: Int? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var callbackRegistered = false
    private var preferredOutputVolume = 1.0f
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onAudioFocusLost()
            AudioManager.AUDIOFOCUS_GAIN -> onEvent("Audio focus recuperado.")
            else -> Unit
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            configurePreferredCommunicationDevice()
            publishRoute("Dispositivo de audio conectado.")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            configurePreferredCommunicationDevice()
            publishRoute("Dispositivo de audio desconectado.")
        }
    }

    fun prepare() {
        val manager = audioManager ?: return
        if (previousAudioMode == null) {
            previousAudioMode = manager.mode
        }
        requestFocus(manager)
        registerRouteUpdates(manager)
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        configurePreferredCommunicationDevice()
        applyOutputVolume(preferredOutputVolume)
        publishRoute("Audio Realtime preparado.")
    }

    fun restore() {
        val manager = audioManager ?: return
        unregisterRouteUpdates(manager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                manager.stopBluetoothSco()
                manager.isBluetoothScoOn = false
                manager.isSpeakerphoneOn = false
            }
        }
        abandonFocus(manager)
        previousAudioMode?.let { manager.mode = it }
        previousAudioMode = null
        publishRoute("Audio Realtime restaurado.")
    }

    fun applyOutputVolume(volume: Float): VoiceAudioRoute {
        preferredOutputVolume = volume.coerceIn(0.0f, MAX_OUTPUT_VOLUME)
        val manager = audioManager
        if (manager != null) {
            val stream = AudioManager.STREAM_VOICE_CALL
            val max = manager.getStreamMaxVolume(stream).coerceAtLeast(1)
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.getStreamMinVolume(stream)
            } else {
                0
            }
            val targetRatio = if (preferredOutputVolume >= 1.0f) {
                1.0f
            } else {
                preferredOutputVolume.coerceIn(0.0f, 1.0f)
            }
            val target = (min + ((max - min) * targetRatio)).toInt().coerceIn(min, max)
            runCatching { manager.setStreamVolume(stream, target, 0) }
        }
        val route = currentRoute()
        onRouteChanged(route)
        return route
    }

    fun currentRoute(): VoiceAudioRoute {
        val manager = audioManager ?: return VoiceAudioRoute("unknown", "Desconocida", "AudioManager no disponible", 0, 0, preferredOutputVolume)
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val communicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.communicationDevice else null
        val selected = communicationDevice ?: preferredDevice(outputs)
        val routeKey = selected?.routeKey() ?: "speaker"
        val routeLabel = selected?.productName?.toString()?.takeIf { it.isNotBlank() } ?: routeKey.displayRoute()
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val current = manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        return VoiceAudioRoute(
            key = routeKey,
            label = routeLabel,
            detail = selected?.type?.let { "type=$it" } ?: "altavoz por defecto",
            streamVolume = current,
            streamMaxVolume = max,
            requestedGain = preferredOutputVolume
        )
    }

    private fun configurePreferredCommunicationDevice() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = preferredDevice(manager.availableCommunicationDevices)
            if (device != null) {
                runCatching { manager.setCommunicationDevice(device) }
                    .onSuccess { onEvent("Salida de comunicacion: ${device.productName}.") }
                    .onFailure { onEvent("No se pudo fijar salida de comunicacion: ${it.message}.") }
            }
            return
        }
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val hasBluetoothSco = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        @Suppress("DEPRECATION")
        if (hasBluetoothSco) {
            runCatching {
                manager.isSpeakerphoneOn = false
                manager.startBluetoothSco()
                manager.isBluetoothScoOn = true
            }
        }
    }

    private fun preferredDevice(devices: List<AudioDeviceInfo>): AudioDeviceInfo? =
        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } ?:
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?:
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ?:
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } ?:
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } ?:
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET } ?:
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun AudioDeviceInfo.routeKey(): String = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headset"
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_audio"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        else -> "speaker"
    }

    private fun String.displayRoute(): String = when (this) {
        "bluetooth" -> "Bluetooth"
        "wired_headset" -> "Auriculares cableados"
        "usb_audio" -> "Audio USB"
        "earpiece" -> "Auricular"
        "speaker" -> "Altavoz"
        else -> "Desconocida"
    }

    private fun requestFocus(manager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonFocus(manager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(manager::abandonAudioFocusRequest)
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusChangeListener)
        }
    }

    private fun registerRouteUpdates(manager: AudioManager) {
        if (callbackRegistered) {
            return
        }
        manager.registerAudioDeviceCallback(deviceCallback, null)
        callbackRegistered = true
    }

    private fun unregisterRouteUpdates(manager: AudioManager) {
        if (!callbackRegistered) {
            return
        }
        manager.unregisterAudioDeviceCallback(deviceCallback)
        callbackRegistered = false
    }

    private fun publishRoute(event: String) {
        val route = currentRoute()
        AteneaDiagnostics.info("voice-audio", "route", mapOf("route" to route.key, "label" to route.label))
        onRouteChanged(route)
        onEvent(event)
    }

    companion object {
        private const val MAX_OUTPUT_VOLUME = 2.5f
    }
}

internal data class VoiceAudioRoute(
    val key: String,
    val label: String,
    val detail: String,
    val streamVolume: Int,
    val streamMaxVolume: Int,
    val requestedGain: Float
) {
    fun display(): String = if (streamMaxVolume > 0) {
        "$label · $streamVolume/$streamMaxVolume · ganancia ${(requestedGain * 100).toInt()}%"
    } else {
        "$label · ganancia ${(requestedGain * 100).toInt()}%"
    }
}
