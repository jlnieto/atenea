package com.atenea.android.coreconsole

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.atenea.android.api.CoreCommandResponse
import com.atenea.android.api.CoreScope
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileWorkSessionConversation
import com.atenea.android.voiceruntime.AteneaDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun WorkSessionConversationScreen(
    apiClient: AteneaApiClient,
    projectId: Long?,
    sessionId: Long?,
    onOpenCore: () -> Unit,
    onBackToSession: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val promptRecorder = remember(context) { ConversationPromptRecorder(context.applicationContext) }
    var conversation by remember { mutableStateOf<MobileWorkSessionConversation?>(null) }
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeCommand by remember { mutableStateOf<CoreCommandResponse?>(null) }
    var recording by remember { mutableStateOf(false) }
    var audioLevels by remember { mutableStateOf(List(18) { 0.06f }) }

    fun refresh() {
        val id = sessionId ?: return
        scope.launch {
            pending = true
            error = null
            try {
                conversation = apiClient.fetchMobileWorkSessionConversation(id).also { loaded ->
                    AteneaDiagnostics.info(
                        area = "conversation",
                        event = "loaded",
                        details = mapOf(
                            "sessionId" to id,
                            "turns" to loaded.recentTurns.size,
                            "characters" to loaded.recentTurns.sumOf { it.messageText.length }
                        )
                    )
                }
            } catch (loadError: Exception) {
                error = loadError.message ?: "No se pudo cargar la conversación."
            } finally {
                pending = false
            }
        }
    }

    fun startVoicePrompt() {
        if (pending || recording) {
            return
        }
        try {
            promptRecorder.start()
            input = ""
            error = null
            audioLevels = List(18) { 0.06f }
            recording = true
        } catch (recordingError: Exception) {
            promptRecorder.release()
            error = recordingError.message ?: "No se pudo iniciar la grabación."
            recording = false
        }
    }

    val voicePromptPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoicePrompt()
        } else {
            error = "Atenea necesita permiso de micrófono para dictar prompts."
        }
    }

    fun requestVoicePrompt() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoicePrompt()
        } else {
            voicePromptPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun sendTextPrompt() {
        val id = sessionId ?: return
        val message = input.trim()
        if (message.isBlank()) {
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                activeCommand = apiClient.runCoreCommand(
                    input = message,
                    scope = CoreScope.SESSION,
                    projectId = projectId,
                    workSessionId = id
                )
                conversation = apiClient.fetchMobileWorkSessionConversation(id)
                input = ""
            } catch (sendError: Exception) {
                error = sendError.message ?: "No se pudo enviar el turno."
            } finally {
                pending = false
            }
        }
    }

    fun sendRecordedPrompt() {
        val id = sessionId ?: return
        recording = false
        val recordingFile = promptRecorder.stop()
        if (recordingFile == null) {
            error = "No se pudo usar la grabación. Inténtalo de nuevo."
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                val transcript = apiClient.transcribeCoreVoiceAudio(
                    fileName = recordingFile.file.name,
                    contentType = recordingFile.contentType,
                    bytes = recordingFile.file.readBytes()
                )
                if (transcript.isBlank()) {
                    error = "La transcripción llegó vacía. Prueba a grabar de nuevo."
                    return@launch
                }
                input = transcript
                activeCommand = apiClient.runVoiceCommand(
                    input = transcript,
                    scope = CoreScope.SESSION,
                    projectId = projectId,
                    workSessionId = id
                )
                conversation = apiClient.fetchMobileWorkSessionConversation(id)
                input = ""
            } catch (sendError: Exception) {
                error = sendError.message ?: "No se pudo transcribir y enviar el audio."
            } finally {
                recordingFile.file.delete()
                pending = false
            }
        }
    }

    fun send() {
        if (recording) {
            sendRecordedPrompt()
        } else {
            sendTextPrompt()
        }
    }

    LaunchedEffect(sessionId) { refresh() }

    LaunchedEffect(recording) {
        while (recording) {
            audioLevels = (audioLevels + promptRecorder.normalizedAmplitude()).takeLast(34)
            delay(70)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            promptRecorder.release()
        }
    }

    if (sessionId == null) {
        AteneaPanel {
            Text("No hay WorkSession seleccionada.")
            AteneaButton(text = "Volver", onClick = onBackToSession)
        }
        return
    }

    fun confirmCommand(token: String) {
        val commandId = activeCommand?.commandId ?: return
        val id = sessionId ?: return
        scope.launch {
            pending = true
            error = null
            try {
                activeCommand = apiClient.confirmCoreCommand(commandId, token)
                conversation = apiClient.fetchMobileWorkSessionConversation(id)
            } catch (confirmError: Exception) {
                error = confirmError.message ?: "No se pudo confirmar el comando."
            } finally {
                pending = false
            }
        }
    }

    fun resolveClarification(option: com.atenea.android.api.CoreClarificationOption) {
        val id = sessionId ?: return
        scope.launch {
            pending = true
            error = null
            try {
                val request = CoreCommandRequestState(
                    input = option.label,
                    scope = CoreScope.SESSION,
                    projectId = projectId,
                    workSessionId = id
                ).resolve(option)
                activeCommand = apiClient.runCoreCommand(
                    input = request.input,
                    scope = CoreScope.SESSION,
                    projectId = projectId,
                    workSessionId = id
                )
                conversation = apiClient.fetchMobileWorkSessionConversation(id)
            } catch (clarificationError: Exception) {
                error = clarificationError.message ?: "No se pudo resolver la aclaracion."
            } finally {
                pending = false
            }
        }
    }

    val current = conversation
    ConversationSurface(
        title = current?.session?.title ?: "WorkSession $sessionId",
        status = buildString {
            current?.let {
                append(if (it.runInProgress) "Codex trabajando" else it.session.status)
                it.latestRun?.status?.let { runStatus -> append(" · run $runStatus") }
                it.lastError?.let { lastError -> append(" · $lastError") }
            } ?: append(if (pending) "Cargando..." else "Sin datos")
        },
        turns = current?.recentTurns.orEmpty(),
        input = input,
        pending = pending,
        placeholder = "Escribe o dicta la siguiente instrucción para Codex",
        recording = recording,
        audioLevels = audioLevels,
        onInputChange = { input = it },
        onSend = ::send,
        onMicrophoneClick = ::requestVoicePrompt,
        onBack = onBackToSession,
        onOpenCore = onOpenCore,
        onRefresh = ::refresh,
        error = error,
        commandContent = activeCommand?.let { command ->
            {
                CommandCard(
                    command = command,
                    pending = pending,
                    preferSpeakable = true,
                    onConfirm = ::confirmCommand,
                    onClarification = ::resolveClarification
                )
            }
        }
    )
}
