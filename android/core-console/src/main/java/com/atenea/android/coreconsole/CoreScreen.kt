package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.CoreCommandResponse
import com.atenea.android.api.CoreCommandSummary
import com.atenea.android.api.CoreScope
import kotlinx.coroutines.launch

@Composable
internal fun CoreScreen(
    apiClient: AteneaApiClient
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var input by remember { mutableStateOf("") }
    var selectedScope by remember { mutableStateOf(CoreScope.GLOBAL) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var response by remember { mutableStateOf<CoreCommandResponse?>(null) }
    var lastRequest by remember { mutableStateOf<CoreCommandRequestState?>(null) }
    var history by remember { mutableStateOf<List<CoreCommandSummary>>(emptyList()) }

    fun sendCurrentInput() {
        if (pending || input.isBlank()) {
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                val request = CoreCommandRequestState(input.trim(), selectedScope)
                response = apiClient.runCoreCommand(request.input, request.scope)
                lastRequest = request
                input = ""
                history = apiClient.fetchCoreCommandHistory()
            } catch (commandError: Exception) {
                error = commandError.message ?: "No se pudo ejecutar el comando."
            } finally {
                pending = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoreScope.entries.forEach { scopeOption ->
                FilterChip(
                    selected = selectedScope == scopeOption,
                    onClick = { selectedScope = scopeOption },
                    shape = RoundedCornerShape(2.dp),
                    label = { Text(scopeOption.label()) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            error?.let { ErrorPanel(it) }

            response?.let { command ->
                CommandCard(
                    command = command,
                    pending = pending,
                    onConfirm = { token ->
                        scope.launch {
                            pending = true
                            error = null
                            try {
                                response = apiClient.confirmCoreCommand(command.commandId, token)
                                history = apiClient.fetchCoreCommandHistory()
                            } catch (confirmError: Exception) {
                                error = confirmError.message ?: "No se pudo confirmar el comando."
                            } finally {
                                pending = false
                            }
                        }
                    },
                    onClarification = { option ->
                        val request = lastRequest
                        if (request == null) {
                            error = "No hay contexto de aclaración disponible."
                        } else {
                            scope.launch {
                                pending = true
                                error = null
                                try {
                                    val nextRequest = request.resolve(option)
                                    response = apiClient.runCoreCommand(
                                        input = nextRequest.input,
                                        scope = nextRequest.scope,
                                        projectId = nextRequest.projectId,
                                        workSessionId = nextRequest.workSessionId
                                    )
                                    lastRequest = nextRequest
                                    history = apiClient.fetchCoreCommandHistory()
                                } catch (clarificationError: Exception) {
                                    error = clarificationError.message ?: "No se pudo aplicar la aclaración."
                                } finally {
                                    pending = false
                                }
                            }
                        }
                    }
                )
            }

            if (history.isNotEmpty()) {
                Text("Reciente", style = MaterialTheme.typography.titleMedium)
                history.take(6).forEach { item -> HistoryCard(item) }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            label = { Text("Escribe a Atenea") },
            enabled = !pending,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { sendCurrentInput() })
        )

        Spacer(modifier = Modifier.height(8.dp))

        AteneaButton(
            text = if (pending) "Ejecutando..." else "Enviar",
            modifier = Modifier.fillMaxWidth(),
            enabled = !pending && input.isNotBlank(),
            onClick = { sendCurrentInput() }
        )
    }
}
