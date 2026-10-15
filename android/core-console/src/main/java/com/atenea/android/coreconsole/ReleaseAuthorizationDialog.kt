package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.ReleasePasskeyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.UUID

internal class ReleaseAuthorizationCancelled : RuntimeException()
internal class ReleasePasskeyUnavailable : RuntimeException()
internal class ReleasePasskeySetupRequired : RuntimeException()

/** No proof, factor or grant is retained in saved state. Lost confirms are observed, not resent. */
internal class ReleaseAuthorizationUiState(
    private val scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val authorize: suspend (String?) -> UUID,
    private val confirm: suspend (UUID) -> Unit,
    private val refresh: suspend () -> Unit,
    private val accepted: () -> Unit
) {
    var busy by mutableStateOf(false)
        private set
    var confirmationAttempted by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var needsSetup by mutableStateOf(false)
        private set

    fun setupCompleted() { needsSetup = false; message = null }
    fun requireSetup() {
        needsSetup = true
        message = "Activa esta confirmación una sola vez con Authenticator. Después podrás publicar con huella o PIN."
    }

    fun submit(totp: String? = null) {
        if (busy || confirmationAttempted || !isCurrent()) return
        busy = true
        message = null
        scope.launch {
            try {
                val authorization = authorize(totp)
                currentCoroutineContext().ensureActive()
                check(isCurrent())
                confirmationAttempted = true
                confirm(authorization)
                refresh()
                accepted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ReleaseAuthorizationCancelled) {
                message = "Has cancelado. No se ha solicitado la publicación."
            } catch (_: ReleasePasskeyUnavailable) {
                message = "No hay una passkey disponible en este móvil. Puedes usar Authenticator. No se ha publicado."
            } catch (_: ReleasePasskeySetupRequired) {
                needsSetup = true
                message = "La primera vez debes activar la confirmación del móvil con Authenticator. Después podrás usar huella o PIN. No se ha publicado."
            } catch (_: Exception) {
                message = if (confirmationAttempted) {
                    "No se pudo confirmar el resultado. Consulta esta misma publicación; no se enviará otra solicitud."
                } else if (!isCurrent()) {
                    "El plan ya no está vigente. Consulta el estado antes de preparar otra publicación."
                } else {
                    "No se pudo verificar la confirmación. Puedes volver a intentarlo o usar Authenticator. No se ha publicado."
                }
                refresh()
            } finally {
                busy = false
            }
        }
    }
}

internal fun sameConfirmableRelease(shown: MobileDeliveryOperation, current: MobileDeliveryOperation?, now: Long): Boolean =
    current != null && shown.id == current.id && shown.sessionId == current.sessionId
        && shown.kind == "RELEASE" && current.kind == shown.kind && current.target == shown.target
        && current.sourceCommit == shown.sourceCommit && current.planSha256 == shown.planSha256
        && current.versionCode == shown.versionCode && current.versionName == shown.versionName
        && current.expiresAt == shown.expiresAt && shown.canConfirm(now) && current.canConfirm(now)

@Composable
internal fun ReleaseAuthorizationDialog(
    plan: MobileDeliveryOperation,
    canPublish: () -> Boolean,
    passkeyAvailable: Boolean,
    authorizePasskey: suspend () -> UUID,
    authorizeTotp: suspend (String) -> UUID,
    configurePasskey: suspend (String) -> Unit,
    confirm: suspend (UUID) -> Unit,
    refresh: suspend () -> Unit,
    onAccepted: () -> Unit,
    onDismiss: () -> Unit,
    loadPasskeyStatus: (suspend () -> ReleasePasskeyStatus)? = null
) {
    val scope = rememberCoroutineScope()
    val currentCheck by rememberUpdatedState(canPublish)
    val currentPasskey by rememberUpdatedState(authorizePasskey)
    val currentTotp by rememberUpdatedState(authorizeTotp)
    val currentConfirm by rememberUpdatedState(confirm)
    val currentRefresh by rememberUpdatedState(refresh)
    val currentAccepted by rememberUpdatedState(onAccepted)
    val state = remember(plan.id) {
        ReleaseAuthorizationUiState(scope, { currentCheck() },
            { code -> if (code == null) currentPasskey() else currentTotp(code) },
            { grant -> currentConfirm(grant) }, { currentRefresh() }, { currentAccepted() })
    }
    var useTotp by remember(plan.id) { mutableStateOf(!passkeyAvailable) }
    var code by remember(plan.id) { mutableStateOf("") }
    var configuring by remember(plan.id) { mutableStateOf(false) }
    var setupBusy by remember(plan.id) { mutableStateOf(false) }
    var setupMessage by remember(plan.id) { mutableStateOf<String?>(null) }
    var loadingStatus by remember(plan.id) { mutableStateOf(passkeyAvailable && loadPasskeyStatus!=null) }
    LaunchedEffect(plan.id) {
        if (passkeyAvailable && loadPasskeyStatus!=null) {
            try {
                val status=loadPasskeyStatus()
                if (!status.enabled || !status.hasRegisteredPasskey) useTotp=true
                else if (status.setupRequired) state.requireSetup()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { useTotp=true }
            finally { loadingStatus=false }
        }
    }
    val busy = state.busy || setupBusy || loadingStatus
    AlertDialog(
        onDismissRequest = { if (!busy) { code = ""; onDismiss() } },
        title = { Text(if (configuring) "Confirmación del móvil" else "Publicar ${plan.target.label}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!configuring) {
                    Text("Commit ${plan.sourceCommit?.take(12)}" +
                        (plan.versionName?.let { " · $it /${plan.versionCode}" } ?: ""))
                    plan.releaseRecovery?.let { Text("Incluye este cambio y las versiones posteriores aprobadas en main.") }
                    Text("Esta confirmación autoriza sólo esta publicación. Después continuará aunque cierres Atenea.")
                }
                if (configuring) Text("Sólo la primera vez: verifica tu passkey y el código de Authenticator. " +
                    "Activar esta confirmación no publica ni cambia tu contraseña.")
                if (useTotp || configuring) {
                    OutlinedTextField(value = code, onValueChange = { code = it.filter { c -> c in '0'..'9' }.take(6) },
                        label = { Text("Código de Authenticator") }, singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation())
                } else {
                    Text("Usa tu passkey con la huella, el PIN o el desbloqueo seguro del móvil.")
                }
                if (busy) {
                    LinearProgressIndicator()
                    Text(when {
                        loadingStatus -> "Comprobando confirmación disponible…"
                        setupBusy -> "Configurando confirmación…"
                        else -> "Confirmando publicación…"
                    })
                }
                setupMessage?.let { Text(it) }
                if (!configuring) state.message?.let { Text(it, color = if (state.needsSetup)
                    MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                if (!configuring && !state.confirmationAttempted && passkeyAvailable) TextButton(enabled = !busy,
                    onClick = { useTotp = !useTotp; code = "" }) {
                    Text(if (useTotp) "Usar confirmación del móvil" else "Usar Authenticator")
                }
            }
        },
        confirmButton = {
            if (configuring) TextButton(enabled = !busy && code.length == 6, onClick = {
                if (!setupBusy) {
                    setupBusy = true
                    val supplied = code; code = ""; setupMessage = null
                    scope.launch {
                        try {
                            configurePasskey(supplied)
                            state.setupCompleted()
                            configuring = false; useTotp = false
                            setupMessage = "Configuración completada. Ahora puedes confirmar la publicación con el móvil."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: ReleaseAuthorizationCancelled) { setupMessage = "Has cancelado. No se ha activado ni publicado nada." }
                        catch (_: Exception) { setupMessage = "No se pudo confirmar la activación. Comprueba el código y la passkey. Puedes volver y probar la confirmación del móvil o seguir con Authenticator." }
                        finally { setupBusy = false }
                    }
                }
            }) { Text("Activar confirmación del móvil") }
            else if (state.needsSetup && !useTotp) TextButton(enabled = !busy,
                onClick = { configuring = true; code = ""; setupMessage = null }) { Text("Configurar confirmación del móvil") }
            else if (!state.confirmationAttempted) TextButton(
                enabled = !busy && canPublish() && (!useTotp || code.length == 6),
                onClick = { val supplied = if (useTotp) code else null; code = ""; state.submit(supplied) }
            ) { Text(if (useTotp) "Confirmar con Authenticator" else "Confirmar con el móvil") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = {
            code = ""; if (configuring) configuring = false else onDismiss()
        }) {
            Text(if (configuring) "Volver" else if (state.confirmationAttempted) "Consultar publicación" else "Cancelar")
        } }
    )
}
