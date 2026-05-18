package com.atenea.android.coreconsole

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.CoreCommandResponse
import com.atenea.android.api.CoreScope
import com.atenea.android.api.MobileVoiceAudio
import com.atenea.android.api.MobileVoiceCodexStatus
import com.atenea.android.api.MobileVoiceFocus
import com.atenea.android.api.MobileVoiceNote
import com.atenea.android.api.MobileVoiceNoteSendIntent
import com.atenea.android.api.VoiceDomain
import com.atenea.android.coreconsole.voice.VoiceCommandInterpreter
import com.atenea.android.coreconsole.voice.VoiceBlockControl
import com.atenea.android.coreconsole.voice.VoiceBlockType
import com.atenea.android.coreconsole.voice.VoiceIntent
import com.atenea.android.coreconsole.voice.VoiceRuntimeState
import com.atenea.android.voiceruntime.AteneaVoiceRuntimeController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun VoiceScreen(
    apiClient: AteneaApiClient
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val nativeVoiceRuntime = remember { AteneaVoiceRuntimeController(context.applicationContext) }
    val speechPlayer = remember { VoiceSpeechPlayer(context.applicationContext) }
    val voicePreferences = remember {
        context.getSharedPreferences("atenea_voice_preferences", Context.MODE_PRIVATE)
    }
    val nativeVoiceState by nativeVoiceRuntime.state.collectAsState()
    var focus by remember { mutableStateOf<MobileVoiceFocus?>(null) }
    var notes by remember { mutableStateOf<List<MobileVoiceNote>>(emptyList()) }
    var noteSendIntent by remember { mutableStateOf<MobileVoiceNoteSendIntent?>(null) }
    var codexStatus by remember { mutableStateOf<MobileVoiceCodexStatus?>(null) }
    var noteInput by remember { mutableStateOf("") }
    var response by remember { mutableStateOf<CoreCommandResponse?>(null) }
    var playbackSegments by remember { mutableStateOf<List<String>>(emptyList()) }
    var playbackIndex by remember { mutableStateOf(0) }
    var playbackSourceType by remember { mutableStateOf<String?>(null) }
    var playbackSourceId by remember { mutableStateOf<String?>(null) }
    var resumePlayback by remember { mutableStateOf<VoicePlaybackResume?>(null) }
    var pendingLocalAction by remember { mutableStateOf<VoiceLocalAction?>(null) }
    var voiceState by remember { mutableStateOf(VoiceRuntimeState.IDLE) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var realtimeSessionMessage by remember { mutableStateOf<String?>(null) }
    var lastRealtimeTranscriptHandledSequence by remember { mutableStateOf(0L) }
    var realtimeRecoveryEvent by remember { mutableStateOf<String?>(null) }
    var realtimeRecoveryAttempts by remember { mutableStateOf(0) }
    var voiceOutputVolume by remember { mutableStateOf(voicePreferences.getFloat("output_volume", 1.35f)) }
    var selectedVoice by remember { mutableStateOf(voicePreferences.getString("speech_voice", "marin") ?: "marin") }
    var voiceSpeed by remember { mutableStateOf(voicePreferences.getFloat("speech_speed", 1.05f)) }
    var queuedRealtimeSpeech by remember { mutableStateOf<String?>(null) }
    var continuousPlayback by remember { mutableStateOf(false) }
    var playbackGeneration by remember { mutableStateOf(0L) }
    var lastPlaybackCompletionHandledCount by remember { mutableStateOf(0L) }
    var localSpeechCompletionCount by remember { mutableStateOf(0L) }
    var lastLocalSpeechCompletionHandledCount by remember { mutableStateOf(0L) }
    var localSpeechActive by remember { mutableStateOf(false) }
    var localSpeechRequestId by remember { mutableStateOf(0L) }
    var ignoreLocalSpeechBargeInUntilMillis by remember { mutableStateOf(0L) }
    var lastBargeInHandledCount by remember { mutableStateOf(0L) }
    var pendingBargeInResume by remember { mutableStateOf<VoicePlaybackResume?>(null) }
    var activeBlock by remember { mutableStateOf<VoiceCaptureBlock?>(null) }
    var pendingBlock by remember { mutableStateOf<VoiceCaptureBlock?>(null) }
    var blockInputArmedAtMillis by remember { mutableStateOf(0L) }
    var nextBlockId by remember { mutableStateOf(1L) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showFullNotes by remember { mutableStateOf(false) }
    var heardStatus by remember { mutableStateOf("sin comando recibido") }

    DisposableEffect(Unit) {
        onDispose {
            speechPlayer.release()
        }
    }

    val speechAudioCache = remember { linkedMapOf<String, MobileVoiceAudio>() }

    fun speechMessage(text: String?): String? = text
        ?.trim()
        ?.forRealtimeSpeech()
        ?.takeIf { it.isNotBlank() }

    fun speechCacheKey(message: String): String =
        "${selectedVoice}|${"%.2f".format(voiceSpeed)}|$message"

    fun cacheSpeechAudio(key: String, audio: MobileVoiceAudio) {
        speechAudioCache[key] = audio
        while (speechAudioCache.size > 6) {
            val oldestKey = speechAudioCache.keys.firstOrNull() ?: break
            speechAudioCache.remove(oldestKey)
        }
    }

    fun preloadSpeech(text: String?) {
        val message = speechMessage(text) ?: return
        val key = speechCacheKey(message)
        if (speechAudioCache.containsKey(key)) {
            return
        }
        scope.launch {
            runCatching {
                apiClient.synthesizeMobileVoiceSpeech(
                    text = message,
                    voice = selectedVoice,
                    speed = voiceSpeed.toDouble()
                )
            }.onSuccess { audio ->
                cacheSpeechAudio(key, audio)
            }
        }
    }

    fun speakText(text: String?) {
        val message = text
            ?.trim()
            ?.forRealtimeSpeech()
            ?.takeIf { it.isNotBlank() }
            ?: return
        localSpeechRequestId += 1
        val requestId = localSpeechRequestId
        speechPlayer.stop()
        localSpeechActive = false
        nativeVoiceRuntime.cancelRealtimeResponse()
        voiceState = VoiceRuntimeState.PROCESSING
        statusMessage = "Preparando voz de Atenea."
        scope.launch {
            try {
                val key = speechCacheKey(message)
                val audio = speechAudioCache.remove(key) ?: apiClient.synthesizeMobileVoiceSpeech(
                        text = message,
                        voice = selectedVoice,
                        speed = voiceSpeed.toDouble()
                    )
                if (requestId != localSpeechRequestId) {
                    return@launch
                }
                speechPlayer.play(
                    audio = audio,
                    volume = voiceOutputVolume,
                    onStarted = {
                        if (requestId == localSpeechRequestId) {
                            localSpeechActive = true
                            ignoreLocalSpeechBargeInUntilMillis = SystemClock.elapsedRealtime() + LOCAL_SPEECH_BARGE_IN_GRACE_MS
                            voiceState = VoiceRuntimeState.SPEAKING
                            statusMessage = "Atenea esta hablando."
                        }
                    },
                    onCompletion = {
                        if (requestId == localSpeechRequestId) {
                            localSpeechActive = false
                            voiceState = VoiceRuntimeState.IDLE
                            localSpeechCompletionCount += 1
                        }
                    },
                    onError = { messageError ->
                        if (requestId == localSpeechRequestId) {
                            localSpeechActive = false
                            voiceState = VoiceRuntimeState.IDLE
                            error = messageError
                        }
                    }
                )
            } catch (speechError: Exception) {
                if (requestId == localSpeechRequestId) {
                    localSpeechActive = false
                    voiceState = VoiceRuntimeState.IDLE
                    error = speechError.message ?: "No se pudo preparar la voz de Atenea."
                }
            }
        }
    }

    fun persistPlaybackCursor() {
        val sourceType = playbackSourceType
        val sourceId = playbackSourceId
        if (sourceType.isNullOrBlank() || sourceId.isNullOrBlank() || playbackSegments.isEmpty()) {
            return
        }
        val focusSnapshot = focus
        val index = playbackIndex
        val count = playbackSegments.size
        scope.launch {
            try {
                focus = apiClient.updateVoicePlayback(
                    focus = focusSnapshot,
                    playback = com.atenea.android.api.MobileVoicePlayback(
                        sourceType = sourceType,
                        sourceId = sourceId,
                        segmentIndex = index,
                        segmentCount = count
                    ),
                    activeCommandId = if (sourceType == "CORE_COMMAND") sourceId.toLongOrNull() else focusSnapshot?.activeCommandId
                )
            } catch (_: Exception) {
                // El cursor local debe seguir funcionando aunque falle la persistencia temporal.
            }
        }
    }

    fun setPlayback(
        message: String?,
        sourceType: String? = null,
        sourceId: String? = null,
        keepResume: Boolean = false
    ) {
        playbackSegments = message.toVoiceSegments()
        playbackIndex = 0
        playbackSourceType = sourceType
        playbackSourceId = sourceId
        playbackGeneration += 1
        continuousPlayback = false
        speechAudioCache.clear()
        if (!keepResume) {
            resumePlayback = null
        }
        persistPlaybackCursor()
    }

    fun restorePlaybackFromFocus(command: CoreCommandResponse, currentFocus: MobileVoiceFocus) {
        response = command
        playbackSegments = command.bestVoiceMessage().toVoiceSegments()
        playbackSourceType = "CORE_COMMAND"
        playbackSourceId = command.commandId.toString()
        playbackGeneration += 1
        speechAudioCache.clear()
        playbackIndex = currentFocus.playback
            ?.takeIf { it.sourceType == "CORE_COMMAND" && it.sourceId == command.commandId.toString() }
            ?.segmentIndex
            ?.coerceIn(0, playbackSegments.lastIndex.coerceAtLeast(0))
            ?: 0
    }

    fun segmentSpeechText(segment: String, index: Int, total: Int): String =
        if (total > 1) {
            "Segmento ${index + 1} de $total. $segment"
        } else {
            segment
        }

    fun preloadNextSegmentIfNeeded(segments: List<String>, index: Int, continuous: Boolean) {
        if (continuous && index < segments.lastIndex) {
            preloadSpeech(segmentSpeechText(segments[index + 1], index + 1, segments.size))
        }
    }

    fun speakCurrentSegment(continuous: Boolean = false) {
        val segments = if (playbackSegments.isNotEmpty()) playbackSegments else response.bestPlaybackSegments()
        if (segments.isEmpty()) {
            statusMessage = "No hay respuesta para leer."
            return
        }
        if (segments !== playbackSegments) {
            playbackSegments = segments
            playbackIndex = 0
        }
        val safeIndex = playbackIndex.coerceIn(0, segments.lastIndex)
        playbackIndex = safeIndex
        continuousPlayback = continuous && safeIndex < segments.lastIndex
        playbackGeneration += 1
        persistPlaybackCursor()
        val text = segmentSpeechText(segments[safeIndex], safeIndex, segments.size)
        speakText(text)
        preloadNextSegmentIfNeeded(segments, safeIndex, continuousPlayback)
    }

    fun speakNextSegment(continuous: Boolean = false) {
        if (playbackSegments.isEmpty()) {
            speakCurrentSegment(continuous = continuous)
            return
        }
        if (playbackIndex < playbackSegments.lastIndex) {
            playbackIndex += 1
            speakCurrentSegment(continuous = continuous)
        } else {
            continuousPlayback = false
            statusMessage = "No hay mas segmentos."
        }
    }

    fun speakContinuePlayback(continuous: Boolean = true) {
        resumePlayback?.let { resume ->
            playbackSegments = resume.segments
            playbackIndex = resume.index.coerceIn(0, resume.segments.lastIndex)
            playbackSourceType = resume.sourceType
            playbackSourceId = resume.sourceId
            resumePlayback = null
            speakCurrentSegment(continuous = continuous)
            return
        }
        speakCurrentSegment(continuous = continuous)
    }

    fun speakPreviousSegment() {
        if (playbackSegments.isEmpty()) {
            speakCurrentSegment(continuous = true)
            return
        }
        playbackIndex = (playbackIndex - 1).coerceAtLeast(0)
        speakCurrentSegment(continuous = true)
    }

    fun speakSegment(number: Int) {
        val segments = if (playbackSegments.isNotEmpty()) playbackSegments else response.bestPlaybackSegments()
        if (segments.isEmpty()) {
            statusMessage = "No hay lectura activa."
            return
        }
        val index = number - 1
        if (index !in segments.indices) {
            val message = "No existe el segmento $number. Hay ${segments.size} segmento(s)."
            setPlayback(message, sourceType = "LOCAL_PLAYBACK", sourceId = "missing-segment")
            speakText(message)
            return
        }
        if (segments !== playbackSegments) {
            playbackSegments = segments
        }
        playbackIndex = index
        speakCurrentSegment(continuous = true)
    }

    fun speakFromBeginning() {
        val segments = if (playbackSegments.isNotEmpty()) playbackSegments else response.bestPlaybackSegments()
        if (segments.isEmpty()) {
            statusMessage = "No hay respuesta para leer desde el principio."
            return
        }
        if (segments !== playbackSegments) {
            playbackSegments = segments
        }
        playbackIndex = 0
        speakCurrentSegment(continuous = true)
    }

    fun currentPlaybackResume(): VoicePlaybackResume? =
        playbackSegments.takeIf { it.isNotEmpty() }?.let { segments ->
            VoicePlaybackResume(
                segments = segments,
                index = playbackIndex.coerceIn(0, segments.lastIndex),
                sourceType = playbackSourceType,
                sourceId = playbackSourceId
            )
        }

    fun stopVoice(releaseMicrophone: Boolean = false) {
        queuedRealtimeSpeech = null
        continuousPlayback = false
        playbackGeneration += 1
        pendingBargeInResume = null
        resumePlayback = currentPlaybackResume()
        localSpeechRequestId += 1
        speechPlayer.stop()
        localSpeechActive = false
        nativeVoiceRuntime.cancelRealtimeResponse()
        if (releaseMicrophone) {
            nativeVoiceRuntime.stop()
        }
        voiceState = VoiceRuntimeState.IDLE
        statusMessage = if (releaseMicrophone) {
            "Voz detenida. Microfono liberado."
        } else {
            "Lectura detenida. Voz conectada."
        }
    }

    fun reportUnrecognizedCommand() {
        val message = "Comando no entendido."
        statusMessage = message
        setPlayback(message, sourceType = "LOCAL_COMMAND", sourceId = "unknown")
        speakText(message)
    }

    fun startBlock(type: VoiceBlockType, initialText: String? = null) {
        if (activeBlock != null || pendingBlock != null) {
            statusMessage = "Ya hay un bloque abierto. Di Atenea, fin para procesarlo o Atenea, descarta bloque."
            return
        }
        queuedRealtimeSpeech = null
        continuousPlayback = false
        if (nativeVoiceState.outputPlaybackActive || localSpeechActive || voiceState == VoiceRuntimeState.SPEAKING) {
            localSpeechRequestId += 1
            speechPlayer.stop()
            localSpeechActive = false
            nativeVoiceRuntime.cancelRealtimeResponse()
            voiceState = VoiceRuntimeState.IDLE
        }
        val chunks = initialText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::listOf)
            ?: emptyList()
        val block = VoiceCaptureBlock(
            id = nextBlockId,
            type = type,
            chunks = chunks,
            playbackSnapshot = currentPlaybackResume()
        )
        pendingBlock = block
        blockInputArmedAtMillis = 0L
        nextBlockId += 1
        statusMessage = "${type.label()} preparado. Empieza despues de la confirmacion y cierra con Atenea, fin."
        speakText("${type.label()} abierta. Empieza ahora. Para terminar, usa la orden de cierre.")
    }

    fun appendBlockChunk(transcript: String) {
        val text = transcript.trim().takeIf { it.isNotBlank() } ?: return
        val block = activeBlock ?: return
        activeBlock = block.copy(chunks = block.chunks + text)
        statusMessage = "${block.type.label()} capturando: ${block.chunks.size + 1} fragmento(s)."
    }

    fun cancelBlock() {
        val block = activeBlock
        activeBlock = null
        statusMessage = if (block == null) "No hay bloque abierto." else "${block.type.label()} descartado."
    }

    fun readBlock() {
        val block = activeBlock
        val message = when {
            block == null -> "No hay bloque abierto."
            block.text().isBlank() -> "${block.type.label()} abierto, sin contenido todavia."
            else -> "${block.type.label()} actual. ${block.text()}"
        }
        setPlayback(message, sourceType = "LOCAL_BLOCK", sourceId = "active-block")
        speakText(message)
    }

    fun summarizeBlock() {
        val block = activeBlock
        val text = block?.text().orEmpty()
        val message = when {
            block == null -> "No hay bloque abierto."
            text.isBlank() -> "${block.type.label()} abierto, sin contenido todavia."
            text.length <= 240 -> "${block.type.label()} actual. $text"
            else -> "${block.type.label()} actual con ${block.chunks.size} fragmentos. Empieza por: ${text.take(240)}"
        }
        setPlayback(message, sourceType = "LOCAL_BLOCK", sourceId = "active-block-summary")
        speakText(message)
    }

    fun blockStatus() {
        val block = activeBlock
        val message = if (block == null) {
            "No hay bloque abierto."
        } else {
            "${block.type.label()} abierto con ${block.chunks.size} fragmento(s). Se cierra con la orden de cierre."
        }
        setPlayback(message, sourceType = "LOCAL_BLOCK", sourceId = "active-block-status")
        speakText(message)
    }

    fun describeFocus() {
        scope.launch {
            val currentFocus = runCatching { apiClient.fetchVoiceFocus() }
                .onSuccess { focus = it }
                .getOrElse { focus }
            val description = if (currentFocus == null) {
                "No tengo foco cargado ahora mismo."
            } else {
                buildString {
                    append("Estoy en ${currentFocus.domain.label()}.")
                    currentFocus.projectName?.let { append(" Proyecto: $it.") }
                    currentFocus.workSessionTitle?.let { append(" Sesion: $it.") }
                    currentFocus.managedHostName?.let { append(" Servidor: $it.") }
                    currentFocus.activity?.let { append(" Actividad: $it.") }
                    currentFocus.activeCommandId?.let { append(" Comando activo: $it.") }
                    currentFocus.latestCommandId?.let { append(" Ultimo comando Core: $it.") }
                    when (currentFocus.focusUpToDate) {
                        true -> append(" Estoy en el comando mas nuevo.")
                        false -> append(" No estoy en el comando mas nuevo; al refrescar el foco intento sincronizarlo automaticamente.")
                        null -> Unit
                    }
                    response?.let { command ->
                        append(" La ultima respuesta local es el comando ${command.commandId}, estado ${command.status.displayLabel()}.")
                        if (command.confirmation != null) {
                            append(" Hay una confirmacion pendiente.")
                        }
                    }
                    if (playbackSegments.isNotEmpty()) {
                        append(" Estoy en el segmento ${playbackIndex + 1} de ${playbackSegments.size}.")
                    }
                }
            }
            setPlayback(description, sourceType = "LOCAL_FOCUS", sourceId = "focus")
            speakText(description)
        }
    }

    fun refreshVoiceState() {
        scope.launch {
            pending = true
            error = null
            try {
                val state = apiClient.fetchVoiceNotesState()
                val refreshedFocus = state.focus
                focus = refreshedFocus
                notes = state.notes
                noteSendIntent = state.pendingSendIntent
                val activeCommandId = refreshedFocus?.activeCommandId
                if (response == null && activeCommandId != null) {
                    runCatching { apiClient.fetchCoreCommand(activeCommandId) }
                        .onSuccess { command -> restorePlaybackFromFocus(command, refreshedFocus ?: return@onSuccess) }
                }
                statusMessage = null
            } catch (refreshError: Exception) {
                error = refreshError.message ?: "No se pudo actualizar voz."
            } finally {
                pending = false
            }
        }
    }

    fun changeFocus(domain: VoiceDomain, activity: String) {
        scope.launch {
            pending = true
            error = null
            response = null
            try {
                focus = apiClient.updateVoiceFocus(domain = domain, activity = activity)
                val state = apiClient.fetchVoiceNotesState()
                notes = state.notes
                noteSendIntent = state.pendingSendIntent
                statusMessage = "Foco cambiado a ${domain.label()}."
            } catch (focusError: Exception) {
                error = focusError.message ?: "No se pudo cambiar el foco."
            } finally {
                pending = false
            }
        }
    }

    fun createNote(text: String, clearInput: Boolean, speakConfirmation: Boolean = false) {
        if (pending || text.isBlank()) {
            return
        }
        scope.launch {
            pending = true
            error = null
            response = null
            try {
                apiClient.createVoiceNote(text)
                val state = apiClient.fetchVoiceNotesState()
                notes = state.notes
                focus = state.focus
                noteSendIntent = state.pendingSendIntent
                if (clearInput) {
                    noteInput = ""
                }
                statusMessage = "Nota guardada."
                if (speakConfirmation) {
                    speakText("Nota guardada.")
                }
            } catch (noteError: Exception) {
                error = noteError.message ?: "No se pudo guardar la nota."
            } finally {
                pending = false
            }
        }
    }

    fun saveNote() {
        createNote(noteInput.trim(), clearInput = true)
    }

    fun readNotesFromSnapshot(currentNotes: List<MobileVoiceNote>) {
        val message = when {
            currentNotes.isEmpty() -> "No hay notas activas."
            currentNotes.size == 1 -> "Tienes una nota activa. ${currentNotes.first().text}"
            else -> buildString {
                append("Tienes ${currentNotes.size} notas activas.")
                currentNotes.take(10).forEachIndexed { index, note ->
                    append(" Nota ${index + 1}: ${note.text}.")
                }
                if (currentNotes.size > 10) {
                    append(" Hay ${currentNotes.size - 10} mas.")
                }
            }
        }
        setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "active-notes")
        speakText(message)
    }

    fun readNotes() {
        scope.launch {
            pending = true
            error = null
            try {
                val state = apiClient.fetchVoiceNotesState()
                focus = state.focus
                notes = state.notes
                noteSendIntent = state.pendingSendIntent
                readNotesFromSnapshot(state.notes)
            } catch (readError: Exception) {
                val message = readError.message ?: "No se pudieron cargar las notas."
                error = message
                setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "read-notes-error")
                speakText(message)
            } finally {
                pending = false
            }
        }
    }

    fun readNoteByNumberFromSnapshot(number: Int, currentNotes: List<MobileVoiceNote>) {
        val note = currentNotes.getOrNull(number - 1)
        val message = when {
            note != null -> "Nota $number. ${note.text}"
            currentNotes.isEmpty() -> "No hay notas activas."
            currentNotes.size == 1 -> "No existe la nota $number. Hay una nota activa."
            else -> "No existe la nota $number. Hay ${currentNotes.size} notas activas."
        }
        statusMessage = message
        setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "note-$number")
        speakText(message)
    }

    fun readNoteByNumber(number: Int) {
        scope.launch {
            pending = true
            error = null
            try {
                val state = apiClient.fetchVoiceNotesState()
                focus = state.focus
                notes = state.notes
                noteSendIntent = state.pendingSendIntent
                readNoteByNumberFromSnapshot(number, state.notes)
            } catch (readError: Exception) {
                val message = readError.message ?: "No se pudo cargar la nota $number."
                error = message
                setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "read-note-error-$number")
                speakText(message)
            } finally {
                pending = false
            }
        }
    }

    fun countNotes() {
        val message = when (notes.size) {
            0 -> "No hay notas activas."
            1 -> "Tienes una nota activa."
            else -> "Tienes ${notes.size} notas activas."
        }
        setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "count")
        speakText(message)
    }

    fun archiveNoteById(noteId: Long) {
        if (pending) {
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                val noteNumber = notes.indexOfFirst { it.id == noteId }
                    .takeIf { it >= 0 }
                    ?.plus(1)
                apiClient.archiveVoiceNote(noteId)
                val state = apiClient.fetchVoiceNotesState()
                notes = state.notes
                focus = state.focus
                noteSendIntent = state.pendingSendIntent
                val message = noteNumber
                    ?.let { "Nota $it eliminada. Quedan ${state.notes.size} nota(s) activas." }
                    ?: "Nota eliminada. Quedan ${state.notes.size} nota(s) activas."
                statusMessage = message
                setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "archived-$noteId")
                speakText(message)
            } catch (archiveError: Exception) {
                val message = archiveError.message ?: "No se pudo eliminar la nota."
                error = message
                setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "archive-error-$noteId")
                speakText(message)
            } finally {
                pending = false
            }
        }
    }

    fun archiveNoteByNumber(number: Int) {
        val note = notes.getOrNull(number - 1)
        if (note == null) {
            val message = "No existe la nota $number. Hay ${notes.size} nota(s) activas."
            setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "archive-missing")
            speakText(message)
            return
        }
        archiveNoteById(note.id)
    }

    fun archiveLastNote(speakResult: Boolean = true) {
        if (pending) {
            return
        }
        if (notes.isEmpty()) {
            val message = "No hay notas activas para archivar."
            setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "archive-last-empty")
            if (speakResult) {
                speakText(message)
            } else {
                statusMessage = message
            }
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                val archived = apiClient.archiveLastVoiceNote()
                val state = apiClient.fetchVoiceNotesState()
                notes = state.notes
                focus = state.focus
                noteSendIntent = state.pendingSendIntent
                val message = "He archivado la ultima nota: ${archived.text}"
                statusMessage = "Ultima nota archivada."
                if (speakResult) {
                    setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "archive-last")
                    speakText(message)
                }
            } catch (archiveError: Exception) {
                error = archiveError.message ?: "No se pudo archivar la ultima nota."
            } finally {
                pending = false
            }
        }
    }

    fun archiveAllNotes() {
        if (pending) {
            return
        }
        if (notes.isEmpty()) {
            statusMessage = "No hay notas activas para limpiar."
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                val archived = apiClient.archiveActiveVoiceNotes()
                val state = apiClient.fetchVoiceNotesState()
                notes = state.notes
                focus = state.focus
                noteSendIntent = state.pendingSendIntent
                val message = "He archivado ${archived.size} nota(s)."
                statusMessage = message
                setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "archive-all")
                speakText(message)
            } catch (archiveError: Exception) {
                error = archiveError.message ?: "No se pudieron archivar las notas."
            } finally {
                pending = false
            }
        }
    }

    fun requestArchiveAllNotes() {
        if (notes.isEmpty()) {
            val message = "No hay notas activas para limpiar."
            setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "archive-all-empty")
            speakText(message)
            return
        }
        pendingLocalAction = VoiceLocalAction.ArchiveAllNotes
        val message = "Hay ${notes.size} nota(s) activas. Di Atenea confirmo para archivarlas todas."
        setPlayback(message, sourceType = "LOCAL_CONFIRMATION", sourceId = "archive-all-notes")
        speakText(message)
    }

    fun requestSendNotes(instructionOverride: String? = null) {
        if (pending) {
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                val refreshed = apiClient.fetchVoiceNotesState()
                focus = refreshed.focus
                notes = refreshed.notes
                noteSendIntent = refreshed.pendingSendIntent
                if (refreshed.notes.isEmpty()) {
                    val message = "No hay notas activas para enviar."
                    statusMessage = message
                    setPlayback(message, sourceType = "LOCAL_NOTES", sourceId = "send-empty")
                    speakText(message)
                    return@launch
                }
                val intent = apiClient.createVoiceNoteSendIntent(instructionOverride?.takeIf { it.isNotBlank() })
                noteSendIntent = intent
                pendingLocalAction = null
                val state = apiClient.fetchVoiceNotesState()
                focus = state.focus
                notes = state.notes
                noteSendIntent = state.pendingSendIntent ?: intent
                val message = intent.confirmationPrompt ?: "Confirmacion requerida. Di Atenea confirmo para enviar las notas."
                statusMessage = "Confirmacion pendiente: ${intent.projectName.orEmpty()} ${intent.workSessionTitle.orEmpty()}".trim()
                setPlayback(message, sourceType = "LOCAL_CONFIRMATION", sourceId = "send-notes-${intent.id}")
                speakText(message)
            } catch (focusError: Exception) {
                val message = focusError.message ?: "No se pudo preparar el envio de notas."
                error = message
                setPlayback(message, sourceType = "LOCAL_CONFIRMATION", sourceId = "send-notes-error")
                speakText(message)
            } finally {
                pending = false
            }
        }
    }

    fun confirmNoteSendIntent() {
        val intent = noteSendIntent?.takeIf { it.status == "PENDING" }
        if (intent == null) {
            statusMessage = "No hay envio de notas pendiente."
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                val result = apiClient.confirmVoiceNoteSendIntent(intent.id)
                noteSendIntent = result.intent
                codexStatus = MobileVoiceCodexStatus(
                    projectId = result.intent.projectId,
                    projectName = result.intent.projectName,
                    workSessionId = result.intent.workSessionId,
                    workSessionTitle = result.intent.workSessionTitle,
                    agentRunId = result.agentRunId ?: result.intent.agentRunId,
                    runStatus = "RUNNING",
                    responseReady = false,
                    failed = false,
                    message = result.message.ifBlank { "Notas enviadas a Codex. Estoy esperando respuesta." },
                    updatedAt = result.intent.updatedAt
                )
                val state = apiClient.fetchVoiceNotesState()
                focus = state.focus
                notes = state.notes
                noteSendIntent = state.pendingSendIntent
                val message = result.message.ifBlank { "Notas enviadas a Codex. Estoy esperando respuesta." }
                statusMessage = message
                setPlayback(message, sourceType = "LOCAL_CONFIRMATION", sourceId = "send-notes-confirmed-${intent.id}")
                speakText(message)
            } catch (sendError: Exception) {
                val message = sendError.message ?: "No se pudieron enviar las notas."
                error = message
                setPlayback(message, sourceType = "LOCAL_CONFIRMATION", sourceId = "send-notes-confirm-error")
                speakText(message)
            } finally {
                pending = false
            }
        }
    }

    fun checkCodexStatus() {
        scope.launch {
            pending = true
            error = null
            try {
                val status = apiClient.fetchMobileVoiceCodexStatus()
                codexStatus = status
                val message = status.message.ifBlank { "No tengo estado nuevo de Codex." }
                statusMessage = message
                setPlayback(message, sourceType = "LOCAL_CODEX_STATUS", sourceId = status.agentRunId?.toString() ?: "latest")
                speakText(message)
            } catch (statusError: Exception) {
                val message = statusError.message ?: "No he podido consultar el estado de Codex."
                error = message
                setPlayback(message, sourceType = "LOCAL_CODEX_STATUS", sourceId = "error")
                speakText(message)
            } finally {
                pending = false
            }
        }
    }

    fun cancelPendingAction() {
        val pendingIntent = noteSendIntent?.takeIf { it.status == "PENDING" }
        if (pendingIntent != null) {
            scope.launch {
                pending = true
                error = null
                try {
                    apiClient.cancelVoiceNoteSendIntent(pendingIntent.id)
                    val state = apiClient.fetchVoiceNotesState()
                    focus = state.focus
                    notes = state.notes
                    noteSendIntent = state.pendingSendIntent
                    val message = "Envio cancelado. Las notas siguen guardadas."
                    statusMessage = message
                    setPlayback(message, sourceType = "LOCAL_CONFIRMATION", sourceId = "cancel-send-notes")
                    speakText(message)
                } catch (cancelError: Exception) {
                    error = cancelError.message ?: "No se pudo cancelar el envio."
                } finally {
                    pending = false
                }
            }
            return
        }
        if (pendingLocalAction == null) {
            val message = "No hay accion pendiente que cancelar."
            statusMessage = message
            setPlayback(message, sourceType = "LOCAL_CONFIRMATION", sourceId = "cancel-empty")
            speakText(message)
            return
        }
        pendingLocalAction = null
        val message = "Accion pendiente cancelada. Las notas siguen guardadas."
        statusMessage = message
        setPlayback(message, sourceType = "LOCAL_CONFIRMATION", sourceId = "cancel-pending")
        speakText(message)
    }

    fun confirmPendingCommand() {
        if (noteSendIntent?.status == "PENDING") {
            confirmNoteSendIntent()
            return
        }
        when (val localAction = pendingLocalAction) {
            is VoiceLocalAction.SendNotes -> {
                requestSendNotes(localAction.instruction)
                return
            }
            VoiceLocalAction.ArchiveAllNotes -> {
                pendingLocalAction = null
                archiveAllNotes()
                return
            }
            null -> Unit
        }
        val command = response
        val confirmation = command?.confirmation
        if (command == null || confirmation == null || pending) {
            statusMessage = "No hay confirmacion pendiente."
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                val confirmed = apiClient.confirmCoreCommand(command.commandId, confirmation.confirmationToken)
                response = confirmed
                setPlayback(
                    message = confirmed.bestVoiceMessage(),
                    sourceType = "CORE_COMMAND",
                    sourceId = confirmed.commandId.toString()
                )
                speakCurrentSegment(continuous = true)
            } catch (confirmError: Exception) {
                error = confirmError.message ?: "No se pudo confirmar el comando."
            } finally {
                pending = false
            }
        }
    }

    fun runVoiceCommand(input: String) {
        if (pending || input.isBlank()) {
            return
        }
        scope.launch {
            pending = true
            error = null
            response = null
            try {
                val currentFocus = focus
                val command = apiClient.runVoiceCommand(
                    input = input,
                    scope = currentFocus.coreScope(),
                    projectId = currentFocus?.projectId,
                    workSessionId = currentFocus?.workSessionId
                )
                response = command
                setPlayback(
                    message = command.bestVoiceMessage(),
                    sourceType = "CORE_COMMAND",
                    sourceId = command.commandId.toString()
                )
                statusMessage = "Comando de voz ejecutado."
                speakCurrentSegment(continuous = true)
            } catch (commandError: Exception) {
                error = commandError.message ?: "No se pudo ejecutar el comando de voz."
            } finally {
                pending = false
            }
        }
    }

    fun speakCurrentOrLatestSessionResponse() {
        val segments = if (playbackSegments.isNotEmpty()) playbackSegments else response.bestPlaybackSegments()
        if (segments.isNotEmpty()) {
            speakCurrentSegment(continuous = true)
            return
        }
        if (focus?.workSessionId == null) {
            speakCurrentSegment()
            return
        }
        runVoiceCommand("lee la ultima respuesta de Codex")
    }

    fun runClarification(request: String) {
        val currentSegment = playbackSegments.getOrNull(playbackIndex)
        if (currentSegment.isNullOrBlank()) {
            runVoiceCommand(request)
            return
        }
        if (playbackSegments.isNotEmpty()) {
            resumePlayback = VoicePlaybackResume(
                segments = playbackSegments,
                index = playbackIndex.coerceIn(0, playbackSegments.lastIndex),
                sourceType = playbackSourceType,
                sourceId = playbackSourceId
            )
        }
        val prompt = buildString {
            appendLine("Aclara el fragmento que Atenea estaba leyendo.")
            appendLine()
            appendLine("Peticion del operador:")
            appendLine(request)
            appendLine()
            appendLine("Fragmento actual:")
            appendLine(currentSegment)
            appendLine()
            append("Responde de forma breve, clara y accionable. Despues el operador podra decir `Atenea, sigue` para continuar la lectura anterior.")
        }
        scope.launch {
            pending = true
            error = null
            response = null
            try {
                val currentFocus = focus
                val command = apiClient.runVoiceCommand(
                    input = prompt,
                    scope = currentFocus.coreScope(),
                    projectId = currentFocus?.projectId,
                    workSessionId = currentFocus?.workSessionId
                )
                response = command
                setPlayback(
                    message = command.bestVoiceMessage(),
                    sourceType = "CORE_COMMAND",
                    sourceId = command.commandId.toString(),
                    keepResume = true
                )
                statusMessage = "Aclaracion sobre el segmento ${playbackIndex + 1}."
                speakCurrentSegment(continuous = true)
            } catch (commandError: Exception) {
                error = commandError.message ?: "No se pudo ejecutar la aclaracion."
            } finally {
                pending = false
            }
        }
    }

    fun runBlockClarification(block: VoiceCaptureBlock, text: String) {
        val snapshot = block.playbackSnapshot
        val currentSegment = snapshot?.segments?.getOrNull(snapshot.index)
        if (snapshot != null) {
            resumePlayback = snapshot
        }
        val prompt = buildString {
            appendLine("Aclara la duda capturada por voz durante una lectura de Atenea.")
            appendLine()
            appendLine("Duda completa del operador:")
            appendLine(text)
            if (!currentSegment.isNullOrBlank()) {
                appendLine()
                appendLine("Fragmento que Atenea estaba leyendo al abrir el bloque:")
                appendLine(currentSegment)
            }
            appendLine()
            append("Responde de forma breve, clara y accionable. Despues el operador podra decir `Atenea, continua` para volver a la lectura anterior.")
        }
        scope.launch {
            pending = true
            error = null
            response = null
            try {
                val currentFocus = focus
                val command = apiClient.runVoiceCommand(
                    input = prompt,
                    scope = currentFocus.coreScope(),
                    projectId = currentFocus?.projectId,
                    workSessionId = currentFocus?.workSessionId
                )
                response = command
                setPlayback(
                    message = command.bestVoiceMessage(),
                    sourceType = "CORE_COMMAND",
                    sourceId = command.commandId.toString(),
                    keepResume = true
                )
                statusMessage = "Bloque de pregunta procesado."
                speakCurrentSegment(continuous = true)
            } catch (commandError: Exception) {
                error = commandError.message ?: "No se pudo procesar el bloque."
            } finally {
                pending = false
            }
        }
    }

    fun finishBlock(extraChunk: String? = null) {
        val currentBlock = activeBlock
        val block = if (!extraChunk.isNullOrBlank() && currentBlock != null) {
            currentBlock.copy(chunks = currentBlock.chunks + extraChunk)
        } else {
            currentBlock
        }
        if (block == null) {
            statusMessage = "No hay bloque abierto."
            return
        }
        val text = block.text()
        if (text.isBlank()) {
            activeBlock = null
            statusMessage = "${block.type.label()} cerrado sin contenido."
            return
        }
        activeBlock = null
        when (block.type) {
            VoiceBlockType.NOTE -> {
                block.playbackSnapshot?.let { resumePlayback = it }
                createNote(text, clearInput = false, speakConfirmation = true)
            }
            VoiceBlockType.QUESTION -> runBlockClarification(block, text)
            VoiceBlockType.PROMPT,
            VoiceBlockType.GENERIC -> runVoiceCommand(text)
        }
    }

    fun handleBlockControl(control: VoiceBlockControl) {
        when (control) {
            VoiceBlockControl.None -> Unit
            is VoiceBlockControl.Finish -> finishBlock(control.leadingContent)
            VoiceBlockControl.Cancel -> cancelBlock()
            VoiceBlockControl.Read -> readBlock()
            VoiceBlockControl.Summarize -> summarizeBlock()
            VoiceBlockControl.Status -> blockStatus()
        }
    }

    fun handleVoiceTranscript(transcript: String) {
        when (val intent = VoiceCommandInterpreter.interpret(transcript)) {
            VoiceIntent.Empty -> Unit
            is VoiceIntent.StartBlock -> startBlock(intent.type, intent.initialText)
            VoiceIntent.FinishBlock -> finishBlock()
            VoiceIntent.CancelBlock -> cancelBlock()
            VoiceIntent.ReadBlock -> readBlock()
            VoiceIntent.SummarizeBlock -> summarizeBlock()
            VoiceIntent.BlockStatus -> blockStatus()
            is VoiceIntent.SaveNote -> startBlock(VoiceBlockType.NOTE, intent.text)
            VoiceIntent.SavePendingNote -> startBlock(VoiceBlockType.NOTE, noteInput.takeIf { it.isNotBlank() })
            VoiceIntent.ReadNotes -> readNotes()
            is VoiceIntent.ReadNote -> readNoteByNumber(intent.number)
            VoiceIntent.CountNotes -> countNotes()
            is VoiceIntent.ArchiveNote -> archiveNoteByNumber(intent.number)
            VoiceIntent.ArchiveLastNote -> archiveLastNote()
            VoiceIntent.ArchiveAllNotes -> requestArchiveAllNotes()
            is VoiceIntent.SendNotes -> {
                requestSendNotes(intent.instruction)
            }
            is VoiceIntent.RunCommand -> runVoiceCommand(intent.input)
            is VoiceIntent.ClarifyCurrentSegment -> runClarification(intent.request)
            is VoiceIntent.ChangeFocus -> changeFocus(intent.domain, intent.activity)
            VoiceIntent.DescribeFocus -> describeFocus()
            VoiceIntent.CheckCodexStatus -> checkCodexStatus()
            VoiceIntent.ReadPlayback -> speakCurrentOrLatestSessionResponse()
            VoiceIntent.StartPlayback -> speakFromBeginning()
            VoiceIntent.RepeatPlayback -> speakCurrentSegment(continuous = true)
            VoiceIntent.ContinuePlayback -> speakContinuePlayback(continuous = true)
            VoiceIntent.NextPlayback -> speakNextSegment(continuous = true)
            VoiceIntent.PreviousPlayback -> speakPreviousSegment()
            is VoiceIntent.GoToSegment -> speakSegment(intent.number)
            VoiceIntent.StopPlayback -> stopVoice(releaseMicrophone = false)
            VoiceIntent.ConfirmPending -> confirmPendingCommand()
            VoiceIntent.CancelPending -> cancelPendingAction()
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            nativeVoiceRuntime.start()
        } else {
            error = "Atenea necesita permiso de microfono para iniciar el runtime nativo."
        }
    }

    fun requestRealtimeSession() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runtimePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                val refreshedFocus = apiClient.fetchVoiceFocus()
                val refreshedNotes = apiClient.fetchActiveVoiceNotes()
                focus = refreshedFocus
                notes = refreshedNotes
                val session = apiClient.createMobileVoiceRealtimeSession(
                    clientContext = buildRealtimeOperatorContext(
                        focus = refreshedFocus,
                        notes = refreshedNotes,
                        response = response,
                        playbackIndex = playbackIndex,
                        playbackSegments = playbackSegments
                    ),
                    voice = selectedVoice,
                    speed = voiceSpeed.toDouble()
                )
                selectedVoice = session.voice
                voicePreferences.edit().putString("speech_voice", session.voice).apply()
                nativeVoiceRuntime.connectRealtime(
                    clientSecret = session.clientSecret,
                    model = session.model,
                    voice = session.voice
                )
                nativeVoiceRuntime.setOutputVolume(voiceOutputVolume)
                realtimeSessionMessage = "${session.provider} ${session.sessionType} ${session.model}."
                realtimeRecoveryAttempts = 0
                realtimeRecoveryEvent = null
            } catch (sessionError: Exception) {
                realtimeSessionMessage = null
                error = sessionError.message ?: "No se pudo crear la sesion Realtime."
            } finally {
                pending = false
            }
        }
    }

    fun requestNativeRuntimeStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            nativeVoiceRuntime.start()
        } else {
            runtimePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        refreshVoiceState()
    }

    LaunchedEffect(voiceOutputVolume, nativeVoiceState.serviceActive) {
        if (nativeVoiceState.serviceActive) {
            nativeVoiceRuntime.setOutputVolume(voiceOutputVolume)
        }
    }

    LaunchedEffect(nativeVoiceState.lastErrorCode, nativeVoiceState.realtimeConnected) {
        val event = nativeVoiceState.lastEvent
        if (nativeVoiceState.realtimeConnected || !nativeVoiceState.lastErrorRecoverable) {
            return@LaunchedEffect
        }
        if (event == realtimeRecoveryEvent || realtimeRecoveryAttempts >= 2) {
            return@LaunchedEffect
        }
        realtimeRecoveryEvent = event
        realtimeRecoveryAttempts += 1
        statusMessage = "Realtime ha perdido la conexion. Reintentando (${realtimeRecoveryAttempts}/2)."
        delay(1200)
        requestRealtimeSession()
    }

    suspend fun handleSpeechPlaybackCompleted() {
        val generation = playbackGeneration
        pendingBlock?.let { block ->
            pendingBlock = null
            activeBlock = block
            blockInputArmedAtMillis = SystemClock.elapsedRealtime() + BLOCK_CAPTURE_ARMING_DELAY_MS
            statusMessage = "${block.type.label()} capturando. Cierra con Atenea, fin."
            return
        }
        if (continuousPlayback && playbackSegments.isNotEmpty() && playbackIndex < playbackSegments.lastIndex) {
            delay(40)
            if (generation == playbackGeneration
                && continuousPlayback
                && playbackSegments.isNotEmpty()
                && playbackIndex < playbackSegments.lastIndex
            ) {
                speakNextSegment(continuous = true)
            }
        } else if (playbackSegments.isNotEmpty() && playbackIndex >= playbackSegments.lastIndex) {
            continuousPlayback = false
        }
    }

    LaunchedEffect(nativeVoiceState.playbackCompletionCount) {
        val completionCount = nativeVoiceState.playbackCompletionCount
        if (completionCount <= 0L || completionCount == lastPlaybackCompletionHandledCount) {
            return@LaunchedEffect
        }
        lastPlaybackCompletionHandledCount = completionCount
        handleSpeechPlaybackCompleted()
    }

    LaunchedEffect(localSpeechCompletionCount) {
        val completionCount = localSpeechCompletionCount
        if (completionCount <= 0L || completionCount == lastLocalSpeechCompletionHandledCount) {
            return@LaunchedEffect
        }
        lastLocalSpeechCompletionHandledCount = completionCount
        handleSpeechPlaybackCompleted()
    }

    LaunchedEffect(nativeVoiceState.bargeInTriggerCount) {
        val count = nativeVoiceState.bargeInTriggerCount
        if (count <= 0L || count == lastBargeInHandledCount) {
            return@LaunchedEffect
        }
        lastBargeInHandledCount = count
        if (localSpeechActive) {
            return@LaunchedEffect
        }
        currentPlaybackResume()?.let { snapshot ->
            resumePlayback = snapshot
            pendingBargeInResume = snapshot
        }
        queuedRealtimeSpeech = null
        continuousPlayback = false
        voiceState = VoiceRuntimeState.IDLE
        statusMessage = "Te escucho."
    }

    LaunchedEffect(nativeVoiceState.outputPlaybackActive) {
        voiceState = when {
            localSpeechActive -> VoiceRuntimeState.SPEAKING
            nativeVoiceState.outputPlaybackActive -> VoiceRuntimeState.SPEAKING
            voiceState == VoiceRuntimeState.SPEAKING -> VoiceRuntimeState.IDLE
            else -> voiceState
        }
    }

    LaunchedEffect(nativeVoiceState.inputSpeechActive) {
        if (!nativeVoiceState.inputSpeechActive || !localSpeechActive) {
            return@LaunchedEffect
        }
        if (SystemClock.elapsedRealtime() < ignoreLocalSpeechBargeInUntilMillis) {
            return@LaunchedEffect
        }
    }

    LaunchedEffect(nativeVoiceState.lastUserTranscriptSequence) {
        val sequence = nativeVoiceState.lastUserTranscriptSequence
        if (sequence <= 0L || sequence == lastRealtimeTranscriptHandledSequence) {
            return@LaunchedEffect
        }
        val transcript = nativeVoiceState.lastUserTranscript
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return@LaunchedEffect
        activeBlock?.let {
            lastRealtimeTranscriptHandledSequence = sequence
            heardStatus = transcript
            if (SystemClock.elapsedRealtime() < blockInputArmedAtMillis || transcript.looksLikeLocalVoicePromptEcho()) {
                statusMessage = "${it.type.label()} preparada. Te escucho."
                return@LaunchedEffect
            }
            val control = VoiceCommandInterpreter.interpretBlockControl(transcript)
            if (control == VoiceBlockControl.None) {
                appendBlockChunk(transcript)
            } else {
                handleBlockControl(control)
            }
            return@LaunchedEffect
        }
        if (!VoiceCommandInterpreter.startsWithWakeWord(transcript)) {
            lastRealtimeTranscriptHandledSequence = sequence
            heardStatus = "Ignorado."
            if (localSpeechActive || voiceState == VoiceRuntimeState.SPEAKING) {
                return@LaunchedEffect
            }
            if (pendingBargeInResume != null) {
                val snapshot = pendingBargeInResume
                pendingBargeInResume = null
                resumePlayback = snapshot
                statusMessage = "Ignorado. Reanudo lectura."
                delay(550)
                speakContinuePlayback(continuous = true)
            } else {
                statusMessage = "Ignorado."
            }
            return@LaunchedEffect
        }
        heardStatus = transcript
        val intent = VoiceCommandInterpreter.interpret(transcript)
        val realtimeRoutable = intent.isRealtimeRoutable()
        if (intent == VoiceIntent.Empty && pendingBargeInResume != null) {
            lastRealtimeTranscriptHandledSequence = sequence
            val snapshot = pendingBargeInResume
            pendingBargeInResume = null
            resumePlayback = snapshot
            statusMessage = "No he detectado orden Atenea; reanudo la lectura."
            delay(550)
            speakContinuePlayback(continuous = true)
            return@LaunchedEffect
        }
        if (intent == VoiceIntent.Empty || (!realtimeRoutable && !transcript.isRealtimeOperationalRequest())) {
            lastRealtimeTranscriptHandledSequence = sequence
            pendingBargeInResume = null
            if (nativeVoiceState.outputPlaybackActive || localSpeechActive || voiceState == VoiceRuntimeState.SPEAKING) {
                continuousPlayback = false
                localSpeechRequestId += 1
                speechPlayer.stop()
                localSpeechActive = false
                nativeVoiceRuntime.cancelRealtimeResponse()
                voiceState = VoiceRuntimeState.IDLE
            }
            reportUnrecognizedCommand()
            return@LaunchedEffect
        }
        if ((pending && intent != VoiceIntent.StopPlayback) || (!realtimeRoutable && !transcript.isRealtimeOperationalRequest())) {
            return@LaunchedEffect
        }
        lastRealtimeTranscriptHandledSequence = sequence
        pendingBargeInResume = null
        if (nativeVoiceState.outputPlaybackActive || localSpeechActive || voiceState == VoiceRuntimeState.SPEAKING) {
            continuousPlayback = false
            localSpeechRequestId += 1
            speechPlayer.stop()
            localSpeechActive = false
            nativeVoiceRuntime.cancelRealtimeResponse()
            voiceState = VoiceRuntimeState.IDLE
        }
        statusMessage = if (realtimeRoutable) "Control de voz recibido." else "Enviando a Atenea Core desde Realtime."
        handleVoiceTranscript(transcript)
    }

    LaunchedEffect(focus?.activeCommandId, response?.commandId) {
        val activeCommandId = focus?.activeCommandId ?: return@LaunchedEffect
        if (response != null || pending) {
            return@LaunchedEffect
        }
        runCatching { apiClient.fetchCoreCommand(activeCommandId) }
            .onSuccess { command -> restorePlaybackFromFocus(command, focus ?: return@onSuccess) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            error?.let { ErrorPanel(it) }
            statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            AteneaPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (nativeVoiceState.realtimeConnected) "Voz conectada" else "Voz desconectada",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            buildString {
                                append(voiceState.label)
                                if (playbackSegments.isNotEmpty()) {
                                    append(" · segmento ${playbackIndex + 1}/${playbackSegments.size}")
                                }
                                if (activeBlock != null) {
                                    append(" · ${activeBlock?.type?.label()} abierta")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        if (nativeVoiceState.inputSpeechActive) "Escuchando" else nativeVoiceState.audioRouteLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AteneaOutlinedButton(
                        text = if (nativeVoiceState.realtimeConnected) "Reconectar" else "Conectar voz",
                        modifier = Modifier.weight(1f),
                        enabled = !pending,
                        onClick = { requestRealtimeSession() }
                    )
                    AteneaOutlinedButton(
                        text = "Parar",
                        modifier = Modifier.weight(1f),
                        enabled = nativeVoiceState.realtimeConnected ||
                            nativeVoiceState.outputPlaybackActive ||
                            localSpeechActive ||
                            nativeVoiceState.captureActive ||
                            voiceState == VoiceRuntimeState.SPEAKING,
                        onClick = {
                            if (nativeVoiceState.outputPlaybackActive || localSpeechActive || voiceState == VoiceRuntimeState.SPEAKING) {
                                stopVoice(releaseMicrophone = true)
                            } else {
                                nativeVoiceRuntime.stop()
                                voiceState = VoiceRuntimeState.IDLE
                                statusMessage = "Voz detenida. Microfono liberado."
                            }
                        }
                    )
                }
                Text(
                    "Escuchado: $heardStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                codexStatus?.let { status ->
                    Text(
                        "Codex: ${status.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AteneaPanel {
                Text("Foco", style = MaterialTheme.typography.titleSmall)
                val currentFocus = focus
                if (currentFocus == null) {
                    MetricLine("Estado", if (pending) "Cargando" else "Sin foco")
                } else {
                    MetricLine("Dónde", currentFocus.domain.label())
                    currentFocus.projectName?.let { MetricLine("Proyecto", it) }
                    currentFocus.workSessionTitle?.let { MetricLine("Sesión", it) }
                    currentFocus.activity?.let { MetricLine("Actividad", it) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AteneaOutlinedButton(
                        text = "Trabajo",
                        modifier = Modifier.weight(1f),
                        enabled = !pending,
                        onClick = { changeFocus(VoiceDomain.DEVELOPMENT, "Trabajo activo") }
                    )
                    AteneaOutlinedButton(
                        text = "Comunicaciones",
                        modifier = Modifier.weight(1f),
                        enabled = !pending,
                        onClick = { changeFocus(VoiceDomain.COMMUNICATIONS, "Comunicaciones") }
                    )
                }
            }

            AteneaPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Notas", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${notes.size} activas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (notes.isEmpty()) {
                    Text("Sin notas activas.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val visibleNotes = if (showFullNotes) notes else notes.take(4)
                    visibleNotes.forEachIndexed { index, note ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "${index + 1}. ${note.text}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = if (showFullNotes) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            AteneaTextButton(
                                text = "Quitar",
                                enabled = !pending,
                                onClick = { archiveNoteById(note.id) }
                            )
                        }
                    }
                    if (!showFullNotes && notes.size > 4) {
                        Text(
                            "+${notes.size - 4} notas más.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AteneaTextButton(
                        text = if (showFullNotes) "Ocultar completas" else "Ver completas",
                        enabled = notes.isNotEmpty(),
                        onClick = { showFullNotes = !showFullNotes }
                    )
                }
                noteSendIntent?.takeIf { it.status == "PENDING" }?.let { intent ->
                    Text(
                        "Pendiente: ${intent.confirmationPrompt ?: "envio de notas preparado"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AteneaOutlinedButton(
                            text = "Confirmar",
                            modifier = Modifier.weight(1f),
                            enabled = !pending,
                            onClick = { confirmNoteSendIntent() }
                        )
                        AteneaOutlinedButton(
                            text = "Cancelar",
                            modifier = Modifier.weight(1f),
                            enabled = !pending,
                            onClick = { cancelPendingAction() }
                        )
                    }
                }
            }

            response?.let { command ->
                CommandCard(
                    command = command,
                    pending = pending,
                    preferSpeakable = true,
                    onConfirm = { token ->
                        scope.launch {
                            pending = true
                            error = null
                            try {
                                val confirmed = apiClient.confirmCoreCommand(command.commandId, token)
                                response = confirmed
                                setPlayback(
                                    message = confirmed.bestVoiceMessage(),
                                    sourceType = "CORE_COMMAND",
                                    sourceId = confirmed.commandId.toString()
                                )
                            } catch (confirmError: Exception) {
                                error = confirmError.message ?: "No se pudo confirmar el comando."
                            } finally {
                                pending = false
                            }
                        }
                    },
                    onClarification = { runClarification(it.label) }
                )
            }

            AteneaTextButton(
                text = if (showAdvanced) "Ocultar avanzado" else "Avanzado",
                onClick = { showAdvanced = !showAdvanced }
            )

            if (showAdvanced) {
                AteneaPanel {
                    Text("Motor", style = MaterialTheme.typography.titleSmall)
                    MetricLine("Servicio", if (nativeVoiceState.serviceActive) "Activo" else "Parado")
                    MetricLine("Realtime", nativeVoiceState.realtimeStatus)
                    MetricLine("Ruta audio", nativeVoiceState.audioRouteLabel)
                    MetricLine("Entrada", if (nativeVoiceState.inputSpeechActive) "Voz detectada" else "En espera")
                    MetricLine("Salida", if (nativeVoiceState.outputPlaybackActive) "Atenea hablando" else "En espera")
                    MetricLine("Barge-in", nativeVoiceState.bargeInStatus)
                    nativeVoiceState.lastUserTranscript?.let { MetricLine("Último operador", it) }
                    realtimeSessionMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        nativeVoiceState.lastEvent,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AteneaOutlinedButton(
                            text = "Iniciar runtime",
                            modifier = Modifier.weight(1f),
                            enabled = !nativeVoiceState.serviceActive,
                            onClick = { requestNativeRuntimeStart() }
                        )
                        AteneaOutlinedButton(
                            text = "Parar runtime",
                            modifier = Modifier.weight(1f),
                            enabled = nativeVoiceState.serviceActive,
                            onClick = { nativeVoiceRuntime.stop() }
                        )
                    }
                    Text(
                        "Volumen voz ${(voiceOutputVolume * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = voiceOutputVolume,
                        onValueChange = {
                            voiceOutputVolume = it
                            voicePreferences.edit().putFloat("output_volume", it).apply()
                        },
                        valueRange = 0.4f..2.5f
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("marin", "cedar", "coral", "nova").forEach { voice ->
                            AteneaOutlinedButton(
                                text = voice.replaceFirstChar { it.titlecase() },
                                modifier = Modifier.weight(1f),
                                enabled = selectedVoice != voice && !localSpeechActive,
                                onClick = {
                                    selectedVoice = voice
                                    voicePreferences.edit().putString("speech_voice", voice).apply()
                                    if (nativeVoiceState.realtimeConnected) {
                                        requestRealtimeSession()
                                    }
                                }
                            )
                        }
                    }
                    Text(
                        "Velocidad %.2fx".format(voiceSpeed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = voiceSpeed,
                        onValueChange = {
                            voiceSpeed = it
                            voicePreferences.edit().putFloat("speech_speed", it).apply()
                        },
                        valueRange = 0.75f..1.5f
                    )
                    AteneaOutlinedButton(
                        text = "Probar voz",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !localSpeechActive && !pending,
                        onClick = {
                            speakText("Soy Atenea. Esta es la voz ${selectedVoice.replaceFirstChar { it.titlecase() }} a velocidad %.2f.".format(voiceSpeed))
                        }
                    )
                    AteneaOutlinedButton(
                        text = "Desconectar Realtime",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = nativeVoiceState.realtimeConnected,
                        onClick = { nativeVoiceRuntime.disconnectRealtime() }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = noteInput,
            onValueChange = { noteInput = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            label = { Text("Nota manual") },
            enabled = !pending,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { saveNote() })
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AteneaOutlinedButton(
                text = "Leer",
                modifier = Modifier.weight(1f),
                enabled = response?.bestVoiceMessage()?.isNotBlank() == true || playbackSegments.isNotEmpty(),
                onClick = { speakCurrentSegment(continuous = true) }
            )
            AteneaOutlinedButton(
                text = "Repetir",
                modifier = Modifier.weight(1f),
                enabled = playbackSegments.isNotEmpty(),
                onClick = { speakCurrentSegment(continuous = true) }
            )
            AteneaButton(
                text = if (pending) "Procesando..." else "Preparar envío",
                modifier = Modifier.weight(1f),
                enabled = !pending && notes.isNotEmpty(),
                onClick = { requestSendNotes() }
            )
        }
    }
}

private fun MobileVoiceFocus?.coreScope(): CoreScope = when {
    this?.workSessionId != null -> CoreScope.SESSION
    this?.projectId != null -> CoreScope.PROJECT
    else -> CoreScope.GLOBAL
}

private fun MobileVoiceFocus?.voiceNotesDestination(): VoiceNotesDestination {
    val current = this ?: return VoiceNotesDestination.Blocked(
        "No puedo enviar las notas porque no tengo foco cargado. Actualiza voz o abre una WorkSession."
    )
    val sessionId = current.workSessionId
    if (sessionId != null) {
        val project = current.projectName?.takeIf { it.isNotBlank() } ?: "proyecto ${current.projectId ?: "-"}"
        val session = current.workSessionTitle?.takeIf { it.isNotBlank() } ?: "WorkSession $sessionId"
        return VoiceNotesDestination.Ready(
            workSessionId = sessionId,
            label = "$project, sesion $session"
        )
    }
    if (current.projectId != null) {
        val project = current.projectName?.takeIf { it.isNotBlank() } ?: "proyecto ${current.projectId}"
        return VoiceNotesDestination.Blocked(
            "No puedo enviar las notas: estoy en $project, pero no hay WorkSession activa. Abre o selecciona una sesion antes de enviarlas."
        )
    }
    return VoiceNotesDestination.Blocked(
        "No puedo enviar las notas porque el foco actual no apunta a una WorkSession. Cambia el foco al proyecto y sesion correctos."
    )
}

private sealed interface VoiceLocalAction {
    data class SendNotes(
        val instruction: String?,
        val expectedWorkSessionId: Long,
        val destination: String
    ) : VoiceLocalAction
    data object ArchiveAllNotes : VoiceLocalAction
}

private sealed interface VoiceNotesDestination {
    data class Ready(val workSessionId: Long, val label: String) : VoiceNotesDestination
    data class Blocked(val reason: String) : VoiceNotesDestination
}

private data class VoicePlaybackResume(
    val segments: List<String>,
    val index: Int,
    val sourceType: String?,
    val sourceId: String?
)

private data class VoiceCaptureBlock(
    val id: Long,
    val type: VoiceBlockType,
    val chunks: List<String>,
    val playbackSnapshot: VoicePlaybackResume?
) {
    fun text(): String = chunks
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private fun VoiceBlockType.label(): String = when (this) {
    VoiceBlockType.GENERIC -> "Bloque"
    VoiceBlockType.QUESTION -> "Pregunta"
    VoiceBlockType.PROMPT -> "Prompt"
    VoiceBlockType.NOTE -> "Nota"
}

private fun CoreCommandResponse?.bestPlaybackSegments(): List<String> =
    this?.bestVoiceMessage().toVoiceSegments()

internal fun String.forRealtimeSpeech(): String {
    return replace(
        Regex("\\b(?:Di\\s+)?(?:Atenea|Athenea|Antena|Antenea|Atenia|Aterea)\\s*,?\\s+confirmo\\b", RegexOption.IGNORE_CASE),
        "confirma"
    ).replace(
        Regex("\\b(?:o\\s+)?(?:Atenea|Athenea|Antena|Antenea|Atenia|Aterea)\\s*,?\\s+cancela(?:\\s+el\\s+envio)?\\b", RegexOption.IGNORE_CASE),
        "o cancela"
    ).replace(
        Regex("\\b(?:con|di|diciendo)?\\s*(?:Atenea|Athenea|Antena|Antenea|Atenia|Aterea)\\s*,?\\s+fin\\b", RegexOption.IGNORE_CASE),
        "con la orden de cierre"
    ).replace(
        Regex("\\b(?:Atenea|Athenea|Antena|Antenea|Atenia|Aterea)\\s*,?\\s+(para|repite|continua|sigue|siguiente|anterior)\\b", RegexOption.IGNORE_CASE),
        "la orden de control"
    ).replace(Regex("\\s+"), " ").trim()
}

private fun String.looksLikeLocalVoicePromptEcho(): Boolean {
    val text = lowercase()
        .replace("á", "a")
        .replace("é", "e")
        .replace("í", "i")
        .replace("ó", "o")
        .replace("ú", "u")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (text.isBlank()) {
        return false
    }
    return text == "nota abierta" ||
        text == "nota abierta cierra con atenea fin" ||
        text == "nota abierta cierra con antena fin" ||
        text.contains("nota abierta") && text.contains("orden de cierre") ||
        text.contains("para terminar") && text.contains("orden de cierre")
}

private const val BLOCK_CAPTURE_ARMING_DELAY_MS = 900L
private const val LOCAL_SPEECH_BARGE_IN_GRACE_MS = 900L
