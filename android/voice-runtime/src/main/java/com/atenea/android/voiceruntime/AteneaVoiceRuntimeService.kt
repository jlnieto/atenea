package com.atenea.android.voiceruntime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class AteneaVoiceRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val realtimeLock = Any()
    private var realtimeClient: WebRtcRealtimeClient? = null
    private var realtimeConnected = false
    private var currentResponseId: String? = null
    private var currentAssistantItemId: String? = null
    private lateinit var audioRouteController: VoiceAudioRouteController
    private var outputVolume = 1.0f
    private var awaitingBargeInTranscript = false
    private val pendingRealtimeEvents = ArrayDeque<String>()

    override fun onCreate() {
        super.onCreate()
        audioRouteController = VoiceAudioRouteController(
            context = applicationContext,
            onRouteChanged = { route ->
                AteneaVoiceRuntimeStateStore.update {
                    it.copy(
                        audioRoute = route.key,
                        audioRouteLabel = route.display(),
                        audioOutputStreamVolume = route.streamVolume,
                        audioOutputStreamMaxVolume = route.streamMaxVolume,
                        outputGain = route.requestedGain
                    )
                }
            },
            onAudioFocusLost = { cancelRealtimeResponse("Audio interrumpido por otra aplicacion.") },
            onEvent = { event -> AteneaVoiceRuntimeStateStore.event(event) }
        )
        AteneaDiagnostics.info("voice-runtime", "service_created")
        createNotificationChannel()
        AteneaVoiceRuntimeStateStore.update {
            it.copy(
                serviceActive = true,
                wakeWordStatus = "provider_required",
                realtimeStatus = "ready",
                realtimeTransport = "webrtc",
                bargeInStatus = "ready_on_realtime",
                lastEvent = "Servicio nativo WebRTC creado."
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                disconnectRealtime("Servicio detenido.")
                stopRuntime()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT_REALTIME -> {
                AteneaDiagnostics.info("voice-runtime", "connect_action_received")
                startRuntime()
                connectRealtime(
                    clientSecret = intent.getStringExtra(EXTRA_CLIENT_SECRET).orEmpty(),
                    model = intent.getStringExtra(EXTRA_MODEL).orEmpty(),
                    voice = intent.getStringExtra(EXTRA_VOICE).orEmpty(),
                )
            }
            ACTION_DISCONNECT_REALTIME -> disconnectRealtime("Realtime desconectado por el operador.")
            ACTION_CANCEL_REALTIME_RESPONSE -> cancelRealtimeResponse("Respuesta cortada por el operador.")
            ACTION_SPEAK_TEXT -> speakText(intent.getStringExtra(EXTRA_SPEAK_TEXT).orEmpty())
            ACTION_SET_OUTPUT_VOLUME -> setOutputVolume(intent.getFloatExtra(EXTRA_OUTPUT_VOLUME, 1.0f))
            else -> startRuntime()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        disconnectRealtime("Servicio destruido.")
        stopRuntime()
        scope.cancel()
        AteneaVoiceRuntimeStateStore.update {
            it.copy(
                serviceActive = false,
                captureActive = false,
                realtimeConnected = false,
                realtimeStatus = "not_connected",
                inputSpeechActive = false,
                outputPlaybackActive = false,
                rmsDb = null,
                peakLevel = 0.0,
                lastEvent = "Servicio nativo WebRTC parado."
            )
        }
        super.onDestroy()
    }

    private fun startRuntime() {
        AteneaDiagnostics.info("voice-runtime", "start_runtime")
        startForegroundCompat()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AteneaDiagnostics.warn("voice-runtime", "missing_record_audio_permission")
            AteneaVoiceRuntimeStateStore.update {
                it.copy(
                    serviceActive = true,
                    captureActive = false,
                    lastEvent = "Falta permiso de microfono."
                )
            }
            return
        }
        prepareRealtimeAudioMode()
        AteneaVoiceRuntimeStateStore.update {
            it.copy(
                serviceActive = true,
                captureActive = realtimeConnected,
                audioRoute = audioRouteController.currentRoute().key,
                audioRouteLabel = audioRouteController.currentRoute().display(),
                realtimeTransport = "webrtc",
                lastEvent = "Runtime WebRTC listo."
            )
        }
    }

    private fun stopRuntime() {
        AteneaDiagnostics.info("voice-runtime", "stop_runtime")
        restoreAudioMode()
        AteneaVoiceRuntimeStateStore.update {
            it.copy(
                captureActive = false,
                rmsDb = null,
                peakLevel = 0.0,
                inputSpeechActive = false,
                lastEvent = "Runtime WebRTC detenido."
            )
        }
    }

    private fun connectRealtime(
        clientSecret: String,
        model: String,
        voice: String,
    ) {
        if (clientSecret.isBlank()) {
            AteneaDiagnostics.warn("voice-runtime", "empty_realtime_client_secret")
            AteneaVoiceRuntimeStateStore.event("No se pudo conectar Realtime: token efimero vacio.")
            return
        }
        disconnectRealtime("Preparando nueva sesion Realtime WebRTC.")
        val safeModel = model.ifBlank { "gpt-realtime" }
        val safeVoice = voice.ifBlank { "marin" }
        val client = WebRtcRealtimeClient(
            context = applicationContext,
            clientSecret = clientSecret,
            outputVolumeProvider = { outputVolume },
            listener = object : WebRtcRealtimeClient.Listener {
                override fun onConnecting() {
                    AteneaDiagnostics.info("voice-runtime", "webrtc_connecting", mapOf("model" to safeModel, "voice" to safeVoice))
                    AteneaVoiceRuntimeStateStore.update {
                        it.copy(
                            realtimeStatus = "connecting",
                            realtimeConnected = false,
                            realtimeTransport = "webrtc",
                            realtimeModel = safeModel,
                            realtimeVoice = safeVoice,
                            audioRoute = audioRouteController.currentRoute().key,
                            audioRouteLabel = audioRouteController.currentRoute().display(),
                            bargeInStatus = "realtime_ready",
                            lastErrorCode = null,
                            lastErrorRecoverable = false,
                            lastEvent = "Conectando Realtime WebRTC."
                        )
                    }
                }

                override fun onConnected() {
                    AteneaDiagnostics.info("voice-runtime", "webrtc_connected")
                    synchronized(realtimeLock) {
                        realtimeConnected = true
                    }
                    AteneaVoiceRuntimeStateStore.update {
                        it.copy(
                            captureActive = true,
                            realtimeStatus = "connected",
                            realtimeConnected = true,
                            realtimeTransport = "webrtc",
                            audioRoute = audioRouteController.currentRoute().key,
                            audioRouteLabel = audioRouteController.currentRoute().display(),
                            lastErrorCode = null,
                            lastErrorRecoverable = false,
                            lastEvent = "Realtime WebRTC conectado."
                        )
                    }
                    flushRealtimeEvents()
                }

                override fun onEvent(message: String) {
                    handleRealtimeEvent(message)
                }

                override fun onDisconnected(reason: String) {
                    AteneaDiagnostics.warn("voice-runtime", "webrtc_disconnected", mapOf("reason" to reason))
                    synchronized(realtimeLock) {
                        realtimeConnected = false
                        realtimeClient = null
                    }
                    currentResponseId = null
                    currentAssistantItemId = null
                    AteneaVoiceRuntimeStateStore.update {
                        it.copy(
                            captureActive = false,
                            realtimeConnected = false,
                            realtimeStatus = "not_connected",
                            inputSpeechActive = false,
                            outputPlaybackActive = false,
                            lastErrorCode = null,
                            lastErrorRecoverable = false,
                            lastEvent = "Realtime WebRTC cerrado: $reason"
                        )
                    }
                }

                override fun onError(message: String) {
                    AteneaDiagnostics.warn("voice-runtime", "webrtc_error", mapOf("message" to message))
                    AteneaVoiceRuntimeStateStore.update {
                        it.copy(
                            realtimeConnected = false,
                            realtimeStatus = "error",
                            outputPlaybackActive = false,
                            lastErrorCode = "WEBRTC_CONNECT_FAILED",
                            lastErrorRecoverable = true,
                            lastEvent = "Fallo Realtime WebRTC: $message"
                        )
                    }
                }
            }
        )
        synchronized(realtimeLock) {
            realtimeClient = client
            realtimeConnected = false
        }
        scope.launch {
            AteneaDiagnostics.info("voice-runtime", "webrtc_client_connect_launch")
            client.connect()
        }
    }

    private fun disconnectRealtime(reason: String) {
        AteneaDiagnostics.info("voice-runtime", "disconnect_realtime", mapOf("reason" to reason))
        val client = synchronized(realtimeLock) {
            realtimeConnected = false
            realtimeClient.also { realtimeClient = null }
        }
        client?.close(reason)
        currentResponseId = null
        currentAssistantItemId = null
        AteneaVoiceRuntimeStateStore.update {
            it.copy(
                captureActive = false,
                realtimeConnected = false,
                realtimeStatus = "not_connected",
                inputSpeechActive = false,
                outputPlaybackActive = false,
                lastEvent = reason
            )
        }
    }

    private fun speakText(text: String) {
        val message = text.trim()
        if (message.isBlank()) {
            return
        }
        if (!isRealtimeReady()) {
            AteneaDiagnostics.warn("voice-runtime", "speak_without_realtime")
            AteneaVoiceRuntimeStateStore.event("No se puede leer: Realtime WebRTC no esta conectado.")
            return
        }
        AteneaDiagnostics.info("voice-runtime", "speak_text", mapOf("characters" to message.length))
        cancelRealtimeResponse("Preparando lectura Realtime.")
        clearRealtimeInputBuffer("Limpiando entrada antes de lectura Realtime.")
        sendRealtimeEvent(
            JSONObject()
                .put("type", "response.create")
                .put(
                    "response",
                    JSONObject()
                        .put("conversation", "none")
                        .put("output_modalities", org.json.JSONArray().put("audio"))
                        .put("input", org.json.JSONArray())
                        .put(
                            "instructions",
                            """
                            Esta es una lectura literal de Atenea Core, no una conversacion nueva.
                            Convierte a voz exactamente el TEXTO_LITERAL.
                            No digas "entendido", "alla voy", introducciones, despedidas, opiniones ni contenido nuevo.
                            Tu respuesta debe empezar por la primera palabra de TEXTO_LITERAL y terminar en su ultima palabra.
                            Mantén tono cercano, profesional y natural en espanol de Espana.

                            TEXTO_LITERAL:
                            $message
                            """.trimIndent()
                        )
                        .put("metadata", JSONObject().put("source", "atenea_core_playback"))
                )
        )
        AteneaVoiceRuntimeStateStore.update {
            it.copy(
                realtimeStatus = "speaking_requested",
                outputPlaybackActive = true,
                lastAssistantTranscript = null,
                lastEvent = "Lectura Realtime solicitada."
            )
        }
    }

    private fun cancelRealtimeResponse(reason: String) {
        val hadActiveResponse = currentResponseId != null ||
            currentAssistantItemId != null ||
            AteneaVoiceRuntimeStateStore.state.value.outputPlaybackActive
        if (currentResponseId != null) {
            AteneaDiagnostics.info("voice-runtime", "cancel_realtime_response")
            sendRealtimeEvent(JSONObject().put("type", "response.cancel"))
        }
        currentResponseId = null
        currentAssistantItemId = null
        if (hadActiveResponse) {
            AteneaVoiceRuntimeStateStore.update {
                it.copy(
                    outputPlaybackActive = false,
                    bargeInStatus = "interrupted",
                    lastEvent = reason
                )
            }
        } else {
            AteneaVoiceRuntimeStateStore.update {
                it.copy(outputPlaybackActive = false, bargeInStatus = "idle")
            }
        }
    }

    private fun handleRealtimeEvent(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (val type = json.optString("type")) {
            "session.created" -> {
                AteneaDiagnostics.info("voice-runtime", "realtime_event_session_created")
                AteneaVoiceRuntimeStateStore.update {
                    it.copy(realtimeStatus = "session_created", lastEvent = "Sesion Realtime creada.")
                }
            }
            "session.updated" -> {
                AteneaDiagnostics.info("voice-runtime", "realtime_event_session_updated")
                AteneaVoiceRuntimeStateStore.update {
                    it.copy(
                        realtimeStatus = "connected",
                        realtimeConnected = true,
                        realtimeTransport = "webrtc",
                        lastErrorCode = null,
                        lastErrorRecoverable = false,
                        lastEvent = "Sesion Realtime configurada."
                    )
                }
            }
            "input_audio_buffer.speech_started" -> {
                val wasOutputActive = AteneaVoiceRuntimeStateStore.state.value.outputPlaybackActive
                if (wasOutputActive && !awaitingBargeInTranscript) {
                    awaitingBargeInTranscript = true
                    cancelRealtimeResponse("Lectura pausada para escuchar al operador.")
                    AteneaVoiceRuntimeStateStore.update {
                        it.copy(
                            inputSpeechActive = true,
                            bargeInStatus = "awaiting_wake_word",
                            bargeInTriggerCount = it.bargeInTriggerCount + 1,
                            lastEvent = "Lectura pausada; esperando orden con Atenea."
                        )
                    }
                } else {
                    AteneaVoiceRuntimeStateStore.update {
                        it.copy(
                            inputSpeechActive = true,
                            bargeInStatus = if (wasOutputActive) "awaiting_wake_word" else "speech_detected",
                            lastEvent = if (wasOutputActive) "Voz detectada durante lectura." else "Voz del operador detectada."
                        )
                    }
                }
            }
            "input_audio_buffer.speech_stopped" -> {
                AteneaVoiceRuntimeStateStore.update {
                    it.copy(inputSpeechActive = false, lastEvent = "Fin de voz detectado.")
                }
            }
            "response.created" -> {
                AteneaDiagnostics.info("voice-runtime", "realtime_event_response_created")
                currentResponseId = json.optJSONObject("response")?.optString("id")?.takeIf { it.isNotBlank() }
                AteneaVoiceRuntimeStateStore.update {
                    it.copy(realtimeStatus = "responding", outputPlaybackActive = true, lastEvent = "Atenea esta respondiendo.")
                }
            }
            "response.output_item.created" -> {
                currentAssistantItemId = json.optJSONObject("item")?.optString("id")?.takeIf { it.isNotBlank() }
            }
            "response.output_audio.delta" -> {
                AteneaVoiceRuntimeStateStore.update {
                    it.copy(outputPlaybackActive = true, realtimeStatus = "speaking")
                }
            }
            "response.output_audio.done" -> {
                AteneaVoiceRuntimeStateStore.event("Audio Realtime recibido completo.")
            }
            "response.done" -> {
                AteneaDiagnostics.info("voice-runtime", "realtime_event_response_done")
                val shouldAdvancePlayback = currentResponseId != null || currentAssistantItemId != null
                currentResponseId = null
                currentAssistantItemId = null
                if (!awaitingBargeInTranscript && !AteneaVoiceRuntimeStateStore.state.value.inputSpeechActive) {
                    clearRealtimeInputBuffer("Limpiando entrada tras lectura Realtime.")
                }
                AteneaVoiceRuntimeStateStore.update {
                    it.copy(
                        realtimeStatus = "connected",
                        outputPlaybackActive = false,
                        playbackCompletionCount = if (shouldAdvancePlayback) {
                            it.playbackCompletionCount + 1
                        } else {
                            it.playbackCompletionCount
                        },
                        lastEvent = "Respuesta Realtime completada."
                    )
                }
            }
            "response.output_audio_transcript.delta" -> {
                val delta = json.optString("delta").takeIf { it.isNotBlank() } ?: return
                AteneaVoiceRuntimeStateStore.update {
                    it.copy(lastAssistantTranscript = (it.lastAssistantTranscript.orEmpty() + delta).takeLast(600))
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = json.optString("transcript").takeIf { text -> text.isNotBlank() }
                awaitingBargeInTranscript = false
                AteneaVoiceRuntimeStateStore.update {
                    it.copy(
                        lastUserTranscript = transcript,
                        lastUserTranscriptSequence = if (transcript == null) {
                            it.lastUserTranscriptSequence
                        } else {
                            it.lastUserTranscriptSequence + 1
                        }
                    )
                }
            }
            "error" -> {
                val detail = json.optJSONObject("error")?.optString("message").orEmpty()
                AteneaDiagnostics.warn("voice-runtime", "realtime_event_error", mapOf("message" to detail))
                if (detail.contains("no active response found", ignoreCase = true)) {
                    AteneaVoiceRuntimeStateStore.event("Realtime: no habia respuesta activa que cancelar.")
                    return
                }
                AteneaVoiceRuntimeStateStore.update {
                    it.copy(
                        realtimeStatus = "error",
                        outputPlaybackActive = false,
                        lastErrorCode = "REALTIME_EVENT_ERROR",
                        lastErrorRecoverable = true,
                        lastEvent = if (detail.isBlank()) "Error Realtime." else "Error Realtime: $detail"
                    )
                }
            }
            else -> {
                if (type.isNotBlank() && type.endsWith(".done")) {
                    AteneaVoiceRuntimeStateStore.event("Realtime: $type")
                }
            }
        }
    }

    private fun sendRealtimeEvent(event: JSONObject) {
        val payload = event.toString()
        val client = synchronized(realtimeLock) { realtimeClient }
        if (client?.send(payload) == true) {
            return
        }
        synchronized(realtimeLock) {
            if (pendingRealtimeEvents.size >= MAX_PENDING_REALTIME_EVENTS) {
                pendingRealtimeEvents.removeFirst()
            }
            pendingRealtimeEvents.addLast(payload)
        }
        AteneaDiagnostics.warn("voice-runtime", "realtime_event_queued", mapOf("type" to event.optString("type")))
        if (!isRealtimeReady()) {
            AteneaVoiceRuntimeStateStore.event("Realtime WebRTC no tiene canal de eventos abierto.")
        }
    }

    private fun clearRealtimeInputBuffer(reason: String) {
        AteneaDiagnostics.info("voice-runtime", "clear_realtime_input_buffer", mapOf("reason" to reason))
        sendRealtimeEvent(JSONObject().put("type", "input_audio_buffer.clear"))
    }

    private fun flushRealtimeEvents() {
        val client = synchronized(realtimeLock) { realtimeClient }
        while (client?.isOpen == true) {
            val payload = synchronized(realtimeLock) {
                pendingRealtimeEvents.removeFirstOrNull()
            } ?: return
            if (client.send(payload) != true) {
                synchronized(realtimeLock) {
                    pendingRealtimeEvents.addFirst(payload)
                }
                AteneaDiagnostics.warn("voice-runtime", "realtime_event_flush_blocked")
                return
            }
        }
    }

    private fun isRealtimeReady(): Boolean =
        synchronized(realtimeLock) { realtimeConnected && realtimeClient?.isOpen == true }

    private fun setOutputVolume(volume: Float) {
        val safeVolume = volume.coerceIn(0.0f, MAX_OUTPUT_VOLUME)
        outputVolume = safeVolume
        synchronized(realtimeLock) {
            realtimeClient?.setOutputVolume(safeVolume)
        }
        audioRouteController.applyOutputVolume(safeVolume)
        AteneaVoiceRuntimeStateStore.event("Volumen de voz ${(safeVolume * 100).toInt()}%.")
    }

    private fun prepareRealtimeAudioMode() {
        audioRouteController.prepare()
    }

    private fun restoreAudioMode() {
        if (::audioRouteController.isInitialized) {
            audioRouteController.restore()
        }
    }

    private fun startForegroundCompat() {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Atenea voz")
            .setContentText("Runtime WebRTC activo")
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Atenea voz",
            NotificationManager.IMPORTANCE_LOW
        )
        manager?.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.atenea.android.voiceruntime.START"
        const val ACTION_STOP = "com.atenea.android.voiceruntime.STOP"
        const val ACTION_CONNECT_REALTIME = "com.atenea.android.voiceruntime.CONNECT_REALTIME"
        const val ACTION_DISCONNECT_REALTIME = "com.atenea.android.voiceruntime.DISCONNECT_REALTIME"
        const val ACTION_CANCEL_REALTIME_RESPONSE = "com.atenea.android.voiceruntime.CANCEL_REALTIME_RESPONSE"
        const val ACTION_SPEAK_TEXT = "com.atenea.android.voiceruntime.SPEAK_TEXT"
        const val ACTION_SET_OUTPUT_VOLUME = "com.atenea.android.voiceruntime.SET_OUTPUT_VOLUME"
        const val EXTRA_CLIENT_SECRET = "client_secret"
        const val EXTRA_MODEL = "model"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_SPEAK_TEXT = "speak_text"
        const val EXTRA_OUTPUT_VOLUME = "output_volume"
        private const val CHANNEL_ID = "atenea_voice_runtime"
        private const val NOTIFICATION_ID = 4101
        private const val MAX_OUTPUT_VOLUME = 2.5f
        private const val MAX_PENDING_REALTIME_EVENTS = 50
    }
}
