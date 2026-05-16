package com.atenea.android.coreconsole

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.atenea.android.api.CoreScope
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(
    apiClient: AteneaApiClient
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var response by remember { mutableStateOf<CoreCommandResponse?>(null) }
    var lastRequest by remember { mutableStateOf<CoreCommandRequestState?>(null) }

    fun sendCommand() {
        if (input.isBlank() || pending) {
            return
        }
        scope.launch {
            pending = true
            error = null
            try {
                val request = CoreCommandRequestState(input.trim(), CoreScope.GLOBAL)
                response = apiClient.runCoreCommand(request.input, request.scope)
                lastRequest = request
                input = ""
            } catch (commandError: Exception) {
                error = commandError.message ?: "No se pudo ejecutar el comando."
            } finally {
                pending = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(top = 10.dp),
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
        }

        HomeCommandDock(
            input = input,
            pending = pending,
            onInputChange = { input = it },
            onSend = { sendCommand() }
        )
    }
}

@Composable
private fun HomeCommandDock(
    input: String,
    pending: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val canSend = input.isNotBlank() && !pending
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                minLines = 1,
                maxLines = 4,
                placeholder = {
                    Text(
                        text = "Mensaje a Atenea",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                enabled = !pending,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(1.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
            AteneaButton(
                text = if (pending) "..." else "Enviar",
                modifier = Modifier.widthIn(min = 86.dp),
                enabled = canSend,
                onClick = onSend
            )
        }
    }
}
