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
import com.atenea.android.api.MobileRescueConversation
import kotlinx.coroutines.launch

@Composable
internal fun RescueScreen(
    apiClient: AteneaApiClient,
    projectId: Long?,
    onOpenCore: () -> Unit,
    onBackToProjects: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var conversation by remember { mutableStateOf<MobileRescueConversation?>(null) }
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun resolveAndRefresh() {
        val id = projectId ?: return
        scope.launch {
            pending = true
            error = null
            try {
                val resolved = apiClient.resolveMobileRescueSession(id)
                conversation = resolved.view
            } catch (loadError: Exception) {
                error = loadError.message ?: "No se pudo abrir rescate."
            } finally {
                pending = false
            }
        }
    }

    fun refresh() {
        val id = conversation?.session?.id
        if (id == null) {
            resolveAndRefresh()
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                conversation = apiClient.fetchMobileRescueConversation(id)
            } catch (loadError: Exception) {
                error = loadError.message ?: "No se pudo actualizar rescate."
            } finally {
                pending = false
            }
        }
    }

    fun send() {
        val id = conversation?.session?.id ?: return
        val message = input.trim()
        if (message.isBlank()) {
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                conversation = apiClient.createMobileRescueTurn(id, message)
                input = ""
            } catch (sendError: Exception) {
                error = sendError.message ?: "No se pudo enviar el turno de rescate."
            } finally {
                pending = false
            }
        }
    }

    LaunchedEffect(projectId) { resolveAndRefresh() }

    if (projectId == null) {
        AteneaPanel {
            Text("Selecciona un proyecto antes de abrir rescate.")
            AteneaButton(text = "Abrir proyectos", onClick = onBackToProjects)
        }
        return
    }

    val current = conversation
    ConversationSurface(
        title = current?.session?.title ?: "Rescate",
        status = current?.session?.let {
            "${it.projectName} · ${it.status}${it.repoPath?.let { repo -> " · $repo" } ?: ""}"
        } ?: if (pending) "Preparando rescate..." else "Sin datos",
        turns = current?.turns.orEmpty(),
        input = input,
        pending = pending,
        placeholder = "Instrucción directa para el canal de rescate",
        onInputChange = { input = it },
        onSend = ::send,
        onBack = onBackToProjects,
        onOpenCore = onOpenCore,
        onRefresh = ::refresh,
        error = error
    )
}
