package com.atenea.android.coreconsole

import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.CoreClarificationOption
import com.atenea.android.api.CoreCommandResponse
import com.atenea.android.api.CoreScope
import com.atenea.android.api.MobileSessionEvent
import com.atenea.android.api.MobileSessionSummary
import com.atenea.android.api.MobileSessionPreview
import com.atenea.android.api.SessionDeliverable
import com.atenea.android.api.SessionDeliverablesView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class WorkSessionRepository(
    private val apiClient: AteneaApiClient,
    private val projectId: Long?,
    private val sessionId: Long,
    private val scope: CoroutineScope
) {
    private val mutableState = MutableStateFlow(WorkSessionUiState(sessionId = sessionId))
    val state: StateFlow<WorkSessionUiState> = mutableState
    private val remoteCloseCoordinator = RemoteCloseOperatorCoordinator(apiClient, sessionId)
    val remoteCloseState: StateFlow<RemoteCloseActionUiState> = remoteCloseCoordinator.state

    private val snapshotReads = CoalescingRead<Long, Unit>(scope)
    private val eventReads = CoalescingRead<Long, Boolean>(scope)
    private val detailReads = CoalescingRead<Pair<Long, Long>, SessionDeliverable>(scope)
    private var generation = 0L
    private var eventCursor: String? = null

    /** The screen's STARTED lifecycle is the sole owner of this periodic observer. */
    suspend fun observeWhileStarted() {
        refreshSnapshot(initial = mutableState.value.summary == null)
        refreshEventsQuietly()
        state.map { it.pollDelayMillis }.distinctUntilChanged().collectLatest { interval ->
            if (interval != null) while (true) {
                delay(interval)
                try {
                    val changed = refreshEvents()
                    val current = mutableState.value
                    if (changed || current.active || current.summary == null || current.stale) refreshSnapshot(initial = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (syncError: Exception) {
                    mutableState.update { it.copy(stale = true, syncLabel = "Sincronizacion degradada",
                        error = syncError.message ?: "No se pudo sincronizar la sesion.") }
                }
            }
        }
    }

    fun refreshNow() {
        scope.launch {
            refreshSnapshot(initial = false)
            try {
                refreshEvents()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (syncError: Exception) {
                mutableState.update {
                    it.copy(
                        stale = true,
                        syncLabel = "Eventos no disponibles",
                        error = syncError.message ?: "No se pudieron refrescar los eventos."
                    )
                }
            }
        }
    }

    fun clearCommand() {
        mutableState.update { it.copy(activeCommand = null) }
    }

    fun runRemoteClosePrimaryAction() {
        val operatorState = mutableState.value.summary?.operatorState ?: return
        beginEffectObservationEpoch()
        scope.launch {
            if (remoteCloseCoordinator.runPrimaryAction(operatorState)) {
                refreshSnapshot(initial = false)
                refreshEventsQuietly()
            }
        }
    }

    fun toggleDeliverableDetail(deliverableId: Long) {
        val current = mutableState.value
        if (current.openDeliverableId == deliverableId) {
            mutableState.update {
                it.copy(
                    openDeliverableId = null,
                    deliverableDetailError = null
                )
            }
            return
        }

        mutableState.update {
            it.copy(
                openDeliverableId = deliverableId,
                deliverableDetailError = null
            )
        }

        if (current.deliverableDetails.containsKey(deliverableId)) {
            return
        }

        val epoch = generation
        scope.launch {
            mutableState.update {
                it.copy(
                    deliverableDetailLoadingId = deliverableId,
                    deliverableDetailError = null
                )
            }
            try {
                val detail = detailReads.read(epoch to deliverableId) { apiClient.fetchMobileSessionDeliverable(sessionId, deliverableId) }
                if (epoch != generation) return@launch
                mutableState.update {
                    it.copy(
                        deliverableDetails = it.deliverableDetails + (deliverableId to detail),
                        deliverableDetailLoadingId = null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (detailError: Exception) {
                if (epoch != generation) return@launch
                mutableState.update {
                    it.copy(
                        deliverableDetailLoadingId = null,
                        deliverableDetailError = detailError.message ?: "No se pudo cargar el entregable completo."
                    )
                }
            }
        }
    }

    fun runCoreAction(label: String, input: String) {
        beginEffectObservationEpoch()
        scope.launch {
            mutableState.update {
                it.copy(
                    pendingAction = label,
                    commandPending = true,
                    error = null,
                    activeCommand = null
                )
            }
            try {
                val command = apiClient.runCoreCommand(
                    input = input,
                    scope = CoreScope.SESSION,
                    projectId = projectId,
                    workSessionId = sessionId
                )
                mutableState.update {
                    it.copy(
                        activeCommand = command,
                        commandPending = false,
                        pendingAction = null
                    )
                }
                refreshAfterCommand(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (actionError: Exception) {
                mutableState.update {
                    it.copy(
                        commandPending = false,
                        pendingAction = null,
                        error = actionError.message ?: "No se pudo ejecutar la accion."
                    )
                }
            }
        }
    }

    fun confirmActiveCommand(token: String) {
        val commandId = mutableState.value.activeCommand?.commandId ?: return
        beginEffectObservationEpoch()
        scope.launch {
            mutableState.update { it.copy(commandPending = true, error = null) }
            try {
                val command = apiClient.confirmCoreCommand(commandId, token)
                mutableState.update { it.copy(activeCommand = command, commandPending = false) }
                refreshAfterCommand(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (confirmError: Exception) {
                val refreshedCommand = try { apiClient.fetchCoreCommand(commandId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                mutableState.update {
                    it.copy(
                        commandPending = false,
                        activeCommand = refreshedCommand ?: it.activeCommand,
                        error = confirmError.message ?: "No se pudo confirmar el comando."
                    )
                }
                refreshSnapshot(initial = false)
                refreshEventsQuietly()
            }
        }
    }

    fun resolveClarification(option: CoreClarificationOption) {
        val request = CoreCommandRequestState(
            input = option.label,
            scope = CoreScope.SESSION,
            projectId = projectId,
            workSessionId = sessionId
        ).resolve(option)
        runCoreAction("Aclaracion", request.input)
    }

    private suspend fun refreshSnapshot(initial: Boolean) {
        val epoch = generation
        snapshotReads.read(epoch) { refreshSnapshotAt(epoch, initial) }
    }

    private suspend fun refreshSnapshotAt(epoch: Long, initial: Boolean) {
        if (epoch != generation) return
        mutableState.update {
            it.copy(
                loading = initial && it.summary == null,
                refreshing = !initial,
                error = null,
                syncLabel = if (initial) "Cargando sesion" else "Actualizando"
            )
        }
        try {
            val summary = apiClient.fetchMobileWorkSessionSummary(sessionId)
            if (epoch != generation) return
            require(summary.conversation.session.id == sessionId)
            val deliverables = apiClient.fetchMobileSessionDeliverables(sessionId)
            if (epoch != generation) return
            require(deliverables.sessionId == sessionId)
            val preview = try {
                apiClient.fetchMobileSessionPreview(sessionId)
            } catch (previewError: AteneaApiException) {
                if (previewError.status == 404) null else throw previewError
            }
            if (epoch != generation) return
            require(preview == null || preview.workSessionId == sessionId)
            remoteCloseCoordinator.accept(summary.operatorState)
            mutableState.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    stale = false,
                    syncLabel = "Sincronizado",
                    summary = summary,
                    deliverables = deliverables,
                    preview = preview,
                    lastSyncedAt = summary.conversation.session.lastActivityAt
                        ?: summary.conversation.session.openedAt
                        ?: it.lastSyncedAt
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (loadError: Exception) {
            if (epoch != generation) return
            mutableState.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    stale = it.summary != null,
                    syncLabel = if (it.summary == null) "Sin datos" else "Pendiente de reintento",
                    error = loadError.message ?: "No se pudo cargar la WorkSession."
                )
            }
        }
    }

    private suspend fun refreshEvents(): Boolean {
        val epoch = generation
        return eventReads.read(epoch) { refreshEventsAt(epoch) }
    }

    private suspend fun refreshEventsAt(epoch: Long): Boolean {
        if (epoch != generation) return false
        val response = apiClient.fetchMobileSessionEvents(
            sessionId = sessionId,
            after = eventCursor,
            limit = 50
        )
        if (epoch != generation) return false
        require(response.sessionId == sessionId)
        val incoming = response.events
        if (incoming.isEmpty()) {
            mutableState.update {
                it.copy(
                    syncLabel = if (it.stale) it.syncLabel else "Sin cambios",
                    lastEventsGeneratedAt = response.generatedAt ?: it.lastEventsGeneratedAt
                )
            }
            return false
        }
        val current = mutableState.value.events
        val merged = (incoming + current)
            .distinctBy { event -> "${event.type}:${event.at}:${event.runId}:${event.turnId}:${event.deliverableId}" }
            .sortedByDescending { it.at.orEmpty() }
            .take(80)
        eventCursor = merged.mapNotNull { it.at }.maxOrNull() ?: eventCursor
        mutableState.update {
            it.copy(
                events = merged,
                syncLabel = "Eventos recibidos",
                lastEventAt = eventCursor,
                lastEventsGeneratedAt = response.generatedAt ?: it.lastEventsGeneratedAt
            )
        }
        return true
    }

    private suspend fun refreshEventsQuietly() {
        try { refreshEvents() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preserve the last confirmed snapshot until the next observation. */ }
    }

    private fun beginEffectObservationEpoch() {
        generation++
        mutableState.update { it.copy(refreshing = false, deliverableDetailLoadingId = null) }
    }

    private suspend fun refreshAfterCommand(command: CoreCommandResponse) {
        if (command.status !in setOf("NEEDS_CONFIRMATION", "NEEDS_CLARIFICATION")) {
            refreshSnapshot(initial = false)
            refreshEvents()
        }
    }
}

internal data class WorkSessionUiState(
    val sessionId: Long,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val stale: Boolean = false,
    val syncLabel: String = "Sin sincronizar",
    val summary: MobileSessionSummary? = null,
    val deliverables: SessionDeliverablesView? = null,
    val preview: MobileSessionPreview? = null,
    val events: List<MobileSessionEvent> = emptyList(),
    val activeCommand: CoreCommandResponse? = null,
    val commandPending: Boolean = false,
    val pendingAction: String? = null,
    val error: String? = null,
    val openDeliverableId: Long? = null,
    val deliverableDetails: Map<Long, SessionDeliverable> = emptyMap(),
    val deliverableDetailLoadingId: Long? = null,
    val deliverableDetailError: String? = null,
    val lastSyncedAt: String? = null,
    val lastEventAt: String? = null,
    val lastEventsGeneratedAt: String? = null
)

private val WorkSessionUiState.active get() = summary?.conversation?.runInProgress == true ||
    pendingAction != null || activeCommand?.status in setOf("RUNNING", "PENDING")

internal val WorkSessionUiState.pollDelayMillis: Long? get() = when {
    stale || error != null -> 15_000L
    summary?.conversation?.session?.status == "CLOSED" -> null
    active -> 3_000L
    else -> 30_000L
}
