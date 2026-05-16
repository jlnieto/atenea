package com.atenea.android.coreconsole

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileWorkSessionConversation
import com.atenea.android.voiceruntime.AteneaDiagnostics
import kotlinx.coroutines.launch

@Composable
internal fun WorkSessionConversationScreen(
    apiClient: AteneaApiClient,
    sessionId: Long?,
    onOpenCore: () -> Unit,
    onBackToProjects: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var conversation by remember { mutableStateOf<MobileWorkSessionConversation?>(null) }
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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

    fun send() {
        val id = sessionId ?: return
        val message = input.trim()
        if (message.isBlank()) {
            return
        }
        scope.launch {
            pending = true
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

    LaunchedEffect(sessionId) { refresh() }

    if (sessionId == null) {
        AteneaPanel {
            Text("No hay WorkSession seleccionada.")
            AteneaButton(text = "Abrir proyectos", onClick = onBackToProjects)
        }
        return
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
        onInputChange = { input = it },
        onSend = ::send,
        onBack = onBackToProjects,
        onOpenCore = onOpenCore,
        onRefresh = ::refresh,
        error = error
    )
}
