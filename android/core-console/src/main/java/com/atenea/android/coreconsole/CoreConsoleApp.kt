package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.secure.AteneaSessionStore
import kotlinx.coroutines.launch

@Composable
fun CoreConsoleApp(
    apiClient: AteneaApiClient,
    sessionStore: AteneaSessionStore,
    apiBaseUrl: String,
    updateManifestUrl: String,
    currentVersionCode: Int,
    currentVersionName: String
) {
    var session by remember { mutableStateOf(sessionStore.load()) }

    if (session == null) {
        LoginScreen(
            apiBaseUrl = apiBaseUrl,
            onLogin = { email, password ->
                val nextSession = apiClient.login(email, password)
                sessionStore.save(nextSession)
                session = nextSession
            }
        )
    } else {
        AteneaShell(
            apiClient = apiClient,
            operatorName = session?.operator?.displayName?.takeIf { it.isNotBlank() }
                ?: session?.operator?.email.orEmpty(),
            updateManifestUrl = updateManifestUrl,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            onLogout = {
                sessionStore.clear()
                session = null
            }
        )
    }
}

@Composable
private fun LoginScreen(
    apiBaseUrl: String,
    onLogin: suspend (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Atenea", style = MaterialTheme.typography.headlineLarge)
        Text("Consola nativa", style = MaterialTheme.typography.bodyMedium)
        Text(apiBaseUrl, style = MaterialTheme.typography.bodySmall)

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!loading && email.isNotBlank() && password.isNotBlank()) {
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                onLogin(email.trim(), password)
                            } catch (loginError: Exception) {
                                error = loginError.message ?: "No se pudo iniciar sesión."
                            } finally {
                                loading = false
                            }
                        }
                    }
                }
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        AteneaButton(
            text = if (loading) "Entrando..." else "Entrar",
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && email.isNotBlank() && password.isNotBlank(),
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    try {
                        onLogin(email.trim(), password)
                    } catch (loginError: Exception) {
                        error = loginError.message ?: "No se pudo iniciar sesión."
                    } finally {
                        loading = false
                    }
                }
            }
        )

        error?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
