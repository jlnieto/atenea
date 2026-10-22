package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.ChangeHelp
import com.atenea.android.api.ChangeHelpDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class ChangeHelpTurn(val question: String?, val result: ChangeHelp)

@Composable
internal fun ChangeHelpScreen(
    apiClient: AteneaApiClient,
    sessionId: Long,
    changeKey: String?,
    onNavigate: (ChangeHelpDestination) -> Unit,
    runReader: CodexRunReader,
    onRunChanged: (Long) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var turns by remember(sessionId) { mutableStateOf<List<ChangeHelpTurn>>(emptyList()) }
    var question by rememberSaveable(sessionId) { mutableStateOf("") }
    var pending by remember(sessionId) { mutableStateOf(false) }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }
    var boundChangeKey by remember(sessionId) { mutableStateOf(changeKey) }
    var recovering by rememberSaveable(sessionId) { mutableStateOf(false) }

    if (recovering) {
        ValidationRecoveryScreen(apiClient, sessionId, changeKey, runReader,
            onBack = { recovering = false }, onNavigate = onNavigate, onRunChanged = onRunChanged)
        return
    }

    fun ask(text: String?, simple: Boolean = false) {
        if (pending) return
        scope.launch {
            pending = true; error = null
            try {
                val result = if (text == null) apiClient.fetchChangeHelp(sessionId)
                    else apiClient.askChangeHelp(sessionId, text, simple)
                val expectedChange = changeKey ?: boundChangeKey
                require((expectedChange == null || result.changeKey.toString() == expectedChange) && result.workSessionId == sessionId)
                boundChangeKey = result.changeKey.toString()
                turns = (turns + ChangeHelpTurn(text, result)).takeLast(16)
                if (text != null) question = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = if (failure is AteneaApiException && failure.status == 404)
                    "El backend todavía no ofrece esta ayuda. No se ha enviado ninguna tarea ni reintentado el cambio."
                else "No se pudo consultar la ayuda. El cambio no se ha modificado."
            } finally { pending = false }
        }
    }

    LaunchedEffect(sessionId, changeKey) { ask(null) }
    ChangeHelpPanel(turns, question, pending, error,
        onQuestionChange = { if (it.length <= 1000) question = it },
        onAsk = { text, simple -> ask(text, simple) }, onRefresh = { ask(null) }, onNavigate = {
            if (it == ChangeHelpDestination.VALIDATION_RECOVERY) recovering = true else onNavigate(it)
        })
}

@Composable
internal fun ChangeHelpPanel(
    turns: List<ChangeHelpTurn>, question: String, pending: Boolean, error: String?,
    onQuestionChange: (String) -> Unit, onAsk: (String, Boolean) -> Unit,
    onRefresh: () -> Unit, onNavigate: (ChangeHelpDestination) -> Unit
) {
    val scroll = rememberLazyListState()
    LaunchedEffect(turns.lastOrNull()) { if (turns.isNotEmpty()) scroll.scrollToItem(turns.lastIndex) }

    Column(Modifier.fillMaxSize().padding(12.dp).testTag("change-help"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ayuda con este cambio", style = MaterialTheme.typography.titleMedium)
        Text("Puedes preguntar cómo continuar. Esta ayuda no ejecuta tareas, pruebas ni publicaciones.",
            style = MaterialTheme.typography.bodySmall)
        if (error != null) IndependentRescueEntry()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll,
            contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(turns) { _, turn ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    turn.question?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    SelectionContainer { Text(turn.result.answer) }
                    Text("Estado registrado · revisión ${turn.result.sourceRevision}", style = MaterialTheme.typography.labelSmall)
                    turn.result.facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        // Keep the next step visible even when an explanation/history is long.
        turns.lastOrNull()?.result?.nextAction?.let { next ->
            AteneaButton(next.label, enabled = !pending, onClick = { onNavigate(next.destination) })
            Text(next.reason, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (pending) Text("Consultando el estado y preparando la explicación…")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AteneaTextButton("¿Qué hago ahora?", enabled = !pending, onClick = { onAsk("¿Qué tengo que hacer ahora?", false) })
            AteneaTextButton("Más fácil", enabled = !pending, onClick = { onAsk("No lo entiendo. Explícamelo más fácil", true) })
        }
        OutlinedTextField(value = question, onValueChange = onQuestionChange,
            modifier = Modifier.fillMaxWidth().testTag("change-help-question"),
            label = { Text("Pregunta sobre este cambio") }, minLines = 1, maxLines = 3, enabled = !pending)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AteneaButton("Preguntar", enabled = !pending && question.isNotBlank(), onClick = { onAsk(question, false) })
            AteneaTextButton("Actualizar estado", enabled = !pending, onClick = onRefresh)
        }
    }
}
