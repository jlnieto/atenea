package com.atenea.android.coreconsole

import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.CoreClarificationOption
import com.atenea.android.api.CoreCommandResponse
import com.atenea.android.api.CoreScope
import com.atenea.android.api.MobileSessionEvent
import com.atenea.android.api.MobileSessionSummary
import com.atenea.android.api.SessionDeliverablesView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private var syncJob: Job? = null
    private var eventCursor: String? = null

    fun start() {
        if (syncJob != null) {
            return
        }
        syncJob = scope.launch {
            refreshSnapshot(initial = true)
            runCatching { refreshEvents() }
            var failures = 0
            while (true) {
                val current = mutableState.value
                val active = current.summary?.conversation?.runInProgress == true ||
                    current.pendingAction != null ||
                    current.activeCommand?.status in setOf("RUNNING", "PENDING")
                val delayMs = when {
                    active -> 3_000L
                    failures > 0 -> 15_000L
                    else -> 10_000L
                }
                delay(delayMs)
                try {
                    val changed = refreshEvents()
                    if (changed || active) {
                        refreshSnapshot(initial = false)
                    }
                    failures = 0
                } catch (syncError: Exception) {
                    failures += 1
                    mutableState.update {
                        it.copy(
                            stale = true,
                            syncLabel = "Sincronizacion degradada",
                            error = syncError.message ?: "No se pudo sincronizar la sesion."
                        )
                    }
                }
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
    }

    fun refreshNow() {
        scope.launch {
            refreshSnapshot(initial = false)
            try {
                refreshEvents()
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

    fun runCoreAction(label: String, input: String) {
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
        scope.launch {
            mutableState.update { it.copy(commandPending = true, error = null) }
            try {
                val command = apiClient.confirmCoreCommand(commandId, token)
                mutableState.update { it.copy(activeCommand = command, commandPending = false) }
                refreshAfterCommand(command)
            } catch (confirmError: Exception) {
                mutableState.update {
                    it.copy(
                        commandPending = false,
                        error = confirmError.message ?: "No se pudo confirmar el comando."
                    )
                }
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
            val deliverables = apiClient.fetchMobileSessionDeliverables(sessionId)
            mutableState.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    stale = false,
                    syncLabel = "Sincronizado",
                    summary = summary,
                    deliverables = deliverables,
                    lastSyncedAt = summary.conversation.session.lastActivityAt
                        ?: summary.conversation.session.openedAt
                        ?: it.lastSyncedAt
                )
            }
        } catch (loadError: Exception) {
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
        val response = apiClient.fetchMobileSessionEvents(
            sessionId = sessionId,
            after = eventCursor,
            limit = 50
        )
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
    val events: List<MobileSessionEvent> = emptyList(),
    val activeCommand: CoreCommandResponse? = null,
    val commandPending: Boolean = false,
    val pendingAction: String? = null,
    val error: String? = null,
    val lastSyncedAt: String? = null,
    val lastEventAt: String? = null,
    val lastEventsGeneratedAt: String? = null
)
