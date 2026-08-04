package com.atenea.android.coreconsole

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.CoreCommandResponse
import com.atenea.android.api.CoreScope
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.CodexProgressReplay
import com.atenea.android.api.CodexRecoveryAction
import com.atenea.android.api.CodexRunDetail
import com.atenea.android.api.MobileWorkSessionConversation
import com.atenea.android.api.MobileSessionOperatorState
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val promptRecorder = remember(context) { ConversationPromptRecorder(context.applicationContext) }
    val remoteCloseCoordinator = remember(apiClient, sessionId) {
        sessionId?.let { RemoteCloseOperatorCoordinator(apiClient, it) }
    }
    val remoteCloseActionState by remoteCloseCoordinator?.state?.collectAsState()
        ?: remember { mutableStateOf(RemoteCloseActionUiState()) }
    var conversation by remember { mutableStateOf<MobileWorkSessionConversation?>(null) }
    var operatorState by remember { mutableStateOf<MobileSessionOperatorState?>(null) }
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeCommand by remember { mutableStateOf<CoreCommandResponse?>(null) }
    var recording by remember { mutableStateOf(false) }
    var audioLevels by remember { mutableStateOf(List(18) { 0.06f }) }
    var recordingStartedAtMs by remember { mutableStateOf<Long?>(null) }
    var profile by remember { mutableStateOf<EffectiveCodexProfile?>(null) }
    var profileUnavailable by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var draftModelId by remember { mutableStateOf("") }
    var draftEffort by remember { mutableStateOf("") }
    var runDetail by remember { mutableStateOf<CodexRunDetail?>(null) }
    var runProgress by remember { mutableStateOf<CodexProgressReplay?>(null) }
    var progressRunId by remember { mutableStateOf<Long?>(null) }
    var progressCursor by remember { mutableStateOf(0L) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var operationNotice by remember { mutableStateOf<String?>(null) }
    var operationPending by remember { mutableStateOf(false) }
    var wasBackgrounded by remember { mutableStateOf(false) }

    suspend fun refreshCodexState(loaded: MobileWorkSessionConversation, includeProfile: Boolean) {
        val id = sessionId ?: return
        if (includeProfile && !profileUnavailable) {
            try {
                val catalog = apiClient.fetchCodexCatalog()
                val sessionSettings = apiClient.fetchSessionCodexSettings(id)
                val projectSettings = projectId?.let { apiClient.fetchProjectCodexSettings(it) }
                val resolved = resolveEffectiveCodexProfile(catalog, sessionSettings, projectSettings)
                profile = resolved
                draftModelId = resolved.model.modelId
                draftEffort = resolved.reasoningEffort
                profileError = null
            } catch (profileLoadError: AteneaApiException) {
                if (profileLoadError.status == 404) {
                    profileUnavailable = true
                    profile = null
                    profileError = null
                } else {
                    profileError = "No se pudo confirmar el perfil. ${profileLoadError.message}"
                }
            } catch (profileLoadError: Exception) {
                profileError = "No se pudo confirmar el perfil. ${profileLoadError.message ?: "Actualiza e inténtalo de nuevo."}"
            }
        }
        val runId = loaded.latestRun?.id
        if (runId == null) {
            runDetail = null
            runProgress = null
            progressRunId = null
            progressCursor = 0
            operationError = null
            return
        }
        try {
            runDetail = apiClient.fetchCodexRunDetail(runId)
            if (progressRunId != runId) {
                progressRunId = runId
                progressCursor = 0
                runProgress = null
            }
            val replay = apiClient.fetchCodexRunProgress(runId, progressCursor)
            runProgress = mergeCodexProgressReplay(runProgress, replay)
            progressCursor = runProgress?.latestObservedSequence() ?: progressCursor
            operationError = null
        } catch (runLoadError: AteneaApiException) {
            if (runLoadError.status != 404) {
                operationError = "No se pudo actualizar la ejecución. ${runLoadError.message}"
            }
        } catch (runLoadError: Exception) {
            operationError = "No se pudo actualizar la ejecución. ${runLoadError.message ?: "Reintenta la sincronización."}"
        }
    }

    fun refresh(silent: Boolean = false, includeProfile: Boolean = true) {
        val id = sessionId ?: return
        scope.launch {
            if (!silent) pending = true
            error = null
            try {
                val summary = apiClient.fetchMobileWorkSessionSummary(id)
                operatorState = summary.operatorState
                remoteCloseCoordinator?.accept(summary.operatorState)
                conversation = summary.conversation.also { loaded ->
                    AteneaDiagnostics.info(
                        area = "conversation",
                        event = "loaded",
                        details = mapOf(
                            "sessionId" to id,
                            "turns" to loaded.recentTurns.size,
                            "characters" to loaded.recentTurns.sumOf { it.messageText.length }
                        )
                    )
                    refreshCodexState(loaded, includeProfile)
                }
            } catch (loadError: Exception) {
                error = loadError.message ?: "No se pudo cargar la conversación."
            } finally {
                if (!silent) pending = false
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
            recordingStartedAtMs = SystemClock.elapsedRealtime()
            recording = true
            AteneaDiagnostics.info(
                area = "conversation-voice",
                event = "recording_started",
                details = mapOf("sessionId" to sessionId)
            )
        } catch (recordingError: Exception) {
            promptRecorder.release()
            error = recordingError.message ?: "No se pudo iniciar la grabación."
            recording = false
            recordingStartedAtMs = null
            AteneaDiagnostics.error(
                area = "conversation-voice",
                event = "recording_start_failed",
                throwable = recordingError,
                details = mapOf("sessionId" to sessionId)
            )
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
        if (pending || message.isBlank()) {
            return
        }
        pending = true
        scope.launch {
            error = null
            try {
                conversation = apiClient.createMobileWorkSessionTurn(id, message)
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
        if (pending) {
            return
        }
        pending = true
        recording = false
        val recordingDurationMs = recordingStartedAtMs?.let { SystemClock.elapsedRealtime() - it }
        recordingStartedAtMs = null
        val recordingFile = promptRecorder.stop()
        if (recordingFile == null) {
            pending = false
            error = "No se pudo usar la grabación. Inténtalo de nuevo."
            AteneaDiagnostics.warn(
                area = "conversation-voice",
                event = "recording_unusable",
                details = mapOf("sessionId" to id, "durationMs" to recordingDurationMs)
            )
            return
        }
        AteneaDiagnostics.info(
            area = "conversation-voice",
            event = "recording_stopped",
            details = mapOf(
                "sessionId" to id,
                "durationMs" to recordingDurationMs,
                "bytes" to recordingFile.file.length(),
                "contentType" to recordingFile.contentType
            )
        )
        scope.launch {
            error = null
            try {
                val transcript = apiClient.transcribeCoreVoiceAudio(
                    fileName = recordingFile.file.name,
                    contentType = recordingFile.contentType,
                    bytes = recordingFile.file.readBytes()
                )
                AteneaDiagnostics.info(
                    area = "conversation-voice",
                    event = "transcription_received",
                    details = mapOf(
                        "sessionId" to id,
                        "characters" to transcript.length,
                        "blank" to transcript.isBlank()
                    )
                )
                if (transcript.isBlank()) {
                    error = "La transcripción llegó vacía. Prueba a grabar de nuevo."
                    return@launch
                }
                conversation = apiClient.createMobileWorkSessionTurn(id, transcript)
                input = ""
            } catch (sendError: Exception) {
                error = sendError.message ?: "No se pudo transcribir y enviar el audio."
                AteneaDiagnostics.error(
                    area = "conversation-voice",
                    event = "transcription_or_send_failed",
                    throwable = sendError,
                    details = mapOf("sessionId" to id)
                )
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

    LaunchedEffect(conversation?.latestRun?.id, conversation?.runInProgress) {
        while (conversation?.runInProgress == true) {
            delay(3_000)
            refresh(silent = true, includeProfile = false)
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            audioLevels = (audioLevels + promptRecorder.normalizedAmplitude()).takeLast(34)
            delay(70)
        }
        audioLevels = List(18) { 0.06f }
    }

    DisposableEffect(Unit) {
        onDispose {
            promptRecorder.release()
        }
    }

    DisposableEffect(lifecycleOwner, sessionId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> wasBackgrounded = true
                Lifecycle.Event.ON_RESUME -> if (wasBackgrounded) {
                    wasBackgrounded = false
                    refresh(silent = true, includeProfile = true)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                val summary = apiClient.fetchMobileWorkSessionSummary(id)
                operatorState = summary.operatorState
                remoteCloseCoordinator?.accept(summary.operatorState)
                conversation = summary.conversation
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
                val summary = apiClient.fetchMobileWorkSessionSummary(id)
                operatorState = summary.operatorState
                remoteCloseCoordinator?.accept(summary.operatorState)
                conversation = summary.conversation
            } catch (clarificationError: Exception) {
                error = clarificationError.message ?: "No se pudo resolver la aclaracion."
            } finally {
                pending = false
            }
        }
    }

    fun changeModel(modelId: String) {
        draftModelId = modelId
        val model = profile?.catalog?.models?.firstOrNull { it.modelId == modelId }
        if (model != null && draftEffort !in model.efforts) {
            draftEffort = model.defaultEffort
        }
    }

    fun applyProfile() {
        val id = sessionId ?: return
        val currentProfile = profile ?: return
        if (operationPending || draftModelId.isBlank() || draftEffort.isBlank()) return
        scope.launch {
            operationPending = true
            profileError = null
            try {
                apiClient.updateSessionCodexSettings(
                    sessionId = id,
                    modelId = draftModelId,
                    reasoningEffort = draftEffort,
                    catalogRevision = currentProfile.catalog.catalogRevision
                )
                conversation?.let { refreshCodexState(it, includeProfile = true) }
            } catch (saveError: Exception) {
                profileError = "El perfil no se aplicó. ${saveError.message ?: "Revisa la selección e inténtalo de nuevo."}"
            } finally {
                operationPending = false
            }
        }
    }

    fun requestRecovery(action: CodexRecoveryAction) {
        val id = sessionId ?: return
        val detail = runDetail ?: return
        if (operationPending) return
        scope.launch {
            operationPending = true
            operationError = null
            operationNotice = null
            try {
                val response = apiClient.requestCodexRecovery(detail.runId, id, action)
                if (response.state == "REJECTED") {
                    operationError = "${response.summary ?: "La operación fue rechazada."} ${codexNextActionLabel(response.requiredNextAction ?: "NONE")}."
                } else {
                    operationNotice = when (action) {
                        CodexRecoveryAction.CANCEL -> "Cancelación solicitada. El estado se actualizará al confirmarse."
                        CodexRecoveryAction.RETRY -> "Reintento solicitado. Espera a que aparezca la nueva ejecución."
                        CodexRecoveryAction.RECONCILE -> "Reconciliación solicitada. Espera la actualización del estado."
                    }
                }
                refresh(silent = true, includeProfile = false)
            } catch (recoveryError: AteneaApiException) {
                operationError = when (recoveryError.status) {
                    403 -> "No tienes permiso para esta acción. Solicítala a un operador autorizado."
                    404 -> "La ejecución ya no está disponible. Actualiza la conversación."
                    409 -> "El estado cambió. Actualiza y vuelve a elegir la acción aplicable."
                    else -> "La acción no se pudo solicitar. ${recoveryError.message}"
                }
            } catch (recoveryError: Exception) {
                operationError = "La acción no se pudo solicitar. Actualiza e inténtalo de nuevo."
            } finally {
                operationPending = false
            }
        }
    }

    fun runRemoteClosePrimaryAction() {
        val currentState = operatorState ?: return
        val coordinator = remoteCloseCoordinator ?: return
        scope.launch {
            if (coordinator.runPrimaryAction(currentState)) {
                refresh(silent = true, includeProfile = false)
            }
        }
    }

    fun confirmLegacyRemoteClose() {
        val currentState = operatorState ?: return
        val coordinator = remoteCloseCoordinator ?: return
        scope.launch {
            if (coordinator.confirmLegacyReconciliation(currentState)) {
                refresh(silent = true, includeProfile = false)
            }
        }
    }

    val current = conversation
    val profileDirty = profile?.let {
        draftModelId != it.model.modelId || draftEffort != it.reasoningEffort
    } == true
    val profileReady = profileUnavailable || profile != null
    val composerEnabled = current?.canCreateTurn == true && profileReady && !profileDirty && profileError == null
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
        onRefresh = { refresh() },
        error = error,
        commandContent = activeCommand?.takeIf { it.isVisibleConversationCommand() }?.let { command ->
            {
                CommandCard(
                    command = command,
                    pending = pending,
                    preferSpeakable = true,
                    onConfirm = ::confirmCommand,
                    onClarification = ::resolveClarification
                )
            }
        },
        runContent = if (operatorState?.surfaceEnabled == true || runDetail != null || operationError != null) {
            {
                operatorState?.let { currentState ->
                    RemoteCloseOperatorPanel(
                        serverState = currentState,
                        actionState = remoteCloseActionState,
                        operatorRole = apiClient.currentOperatorRole(),
                        onPrimaryAction = ::runRemoteClosePrimaryAction,
                        onConfirm = ::confirmLegacyRemoteClose,
                        onCancel = { remoteCloseCoordinator?.cancelConfirmation() },
                        dark = true
                    )
                }
                if (runDetail != null || operationError != null) {
                    CodexRunProgressCard(
                        detail = runDetail,
                        progress = runProgress,
                        pending = operationPending,
                        error = operationError,
                        notice = operationNotice,
                        retryOverride = operatorState?.takeIf { it.surfaceEnabled }?.let {
                            if (it.state == "CAPACITY_RELEASED") null
                            else "Espera a que Atenea confirme la liberación de capacidad"
                        },
                        onRecovery = ::requestRecovery
                    )
                }
            }
        } else null,
        profileContent = if (!profileUnavailable) {
            {
                CodexExecutionProfileStrip(
                    profile = profile,
                    draftModelId = draftModelId,
                    draftEffort = draftEffort,
                    pending = operationPending,
                    error = profileError,
                    onModelChange = ::changeModel,
                    onEffortChange = { draftEffort = it },
                    onApply = ::applyProfile
                )
            }
        } else null,
        composerEnabled = composerEnabled
    )
}

private fun CoreCommandResponse.isVisibleConversationCommand(): Boolean =
    confirmation != null ||
        clarification != null ||
        status.equals("FAILED", ignoreCase = true) ||
        !errorCode.isNullOrBlank() ||
        !errorMessage.isNullOrBlank()
