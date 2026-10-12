package com.atenea.android.coreconsole

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.CodexRecoveryAction
import com.atenea.android.api.MobileSessionOperatorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class RemoteCloseActionUiState(
    val pending: Boolean = false,
    val requiresRefresh: Boolean = false,
    val error: String? = null,
    val notice: String? = null
)

internal interface RemoteCloseGateway {
    suspend fun resumeClose(sessionId: Long)
    suspend fun retryRun(runId: Long, workSessionId: Long): Boolean
}

private class ApiRemoteCloseGateway(private val apiClient: AteneaApiClient) : RemoteCloseGateway {
    override suspend fun resumeClose(sessionId: Long) = apiClient.resumeWorkSessionClose(sessionId)
    override suspend fun retryRun(runId: Long, workSessionId: Long): Boolean =
        apiClient.requestCodexRecovery(runId, workSessionId, CodexRecoveryAction.RETRY).state != "REJECTED"
}

internal class RemoteCloseOperatorCoordinator(
    apiClient: AteneaApiClient,
    private val currentWorkSessionId: Long,
    private val gateway: RemoteCloseGateway = ApiRemoteCloseGateway(apiClient),
    private val operatorRoleProvider: () -> String? = apiClient::currentOperatorRole
) {
    private val mutableState = MutableStateFlow(RemoteCloseActionUiState())
    val state: StateFlow<RemoteCloseActionUiState> = mutableState
    private var acceptedIdentity: RemoteCloseIdentity? = null

    fun accept(serverState: MobileSessionOperatorState) {
        val identity = RemoteCloseIdentity.from(serverState)
        if (identity == acceptedIdentity && !mutableState.value.requiresRefresh) return
        acceptedIdentity = identity
        mutableState.value = RemoteCloseActionUiState()
    }

    suspend fun runPrimaryAction(serverState: MobileSessionOperatorState): Boolean {
        if (!remoteCloseActionAllowed(serverState, operatorRoleProvider()) ||
            mutableState.value.pending || mutableState.value.requiresRefresh ||
            (serverState.primaryAction == "RECONCILE_REMOTE_CLOSE" &&
                serverState.targetWorkSessionId != currentWorkSessionId)
        ) return false
        mutableState.value = mutableState.value.copy(pending = true, error = null, notice = null)
        return try {
            when (serverState.primaryAction) {
                "RETRY_AGENT_RUN" -> {
                    if (!gateway.retryRun(requireNotNull(serverState.targetAgentRunId), currentWorkSessionId)) {
                        throw IllegalStateException("rejected")
                    }
                    mutableState.value = mutableState.value.copy(
                        notice = "Reintento solicitado. La tarea original y sus adjuntos permanecen conservados."
                    )
                    true
                }
                "RECONCILE_REMOTE_CLOSE" -> {
                    gateway.resumeClose(currentWorkSessionId)
                    mutableState.value = mutableState.value.copy(
                        notice = "Reconciliación solicitada. Se mantendrá la misma operación de cierre."
                    )
                    true
                }
                else -> false
            }
        } catch (actionError: Exception) {
            mutableState.value = mutableState.value.copy(
                requiresRefresh = actionError is AteneaApiException && actionError.status == 409,
                error = remoteCloseActionError(actionError)
            )
            false
        } finally {
            mutableState.value = mutableState.value.copy(pending = false)
        }
    }
}

private data class RemoteCloseIdentity(
    val state: String,
    val targetWorkSessionId: Long?,
    val targetAgentRunId: Long?,
    val primaryAction: String
) {
    companion object {
        fun from(state: MobileSessionOperatorState) = RemoteCloseIdentity(
            state.state,
            state.targetWorkSessionId,
            state.targetAgentRunId,
            state.primaryAction
        )
    }
}

internal fun remoteCloseActionAllowed(
    state: MobileSessionOperatorState,
    operatorRole: String?
): Boolean = state.surfaceEnabled && state.primaryActionAvailable &&
    remoteCloseActionMatchesState(state) && operatorHasRequiredRole(operatorRole, state.requiredRole)

private fun remoteCloseActionMatchesState(state: MobileSessionOperatorState): Boolean = when (state.primaryAction) {
    "RETRY_AGENT_RUN" -> state.state == "CAPACITY_RELEASED" && state.targetAgentRunId != null
    "RECONCILE_REMOTE_CLOSE" -> state.state == "CLOSING_REMOTE" && state.targetWorkSessionId != null
    else -> false
}

internal fun operatorHasRequiredRole(actualRole: String?, requiredRole: String?): Boolean {
    if (requiredRole == null) return true
    val rank = mapOf(
        "ROUTINE_OPERATOR" to 1,
        "PRIVILEGED_OPERATOR" to 2,
        "PLATFORM_ADMINISTRATOR" to 3
    )
    return (rank[actualRole] ?: 0) >= (rank[requiredRole] ?: Int.MAX_VALUE)
}

internal fun remoteCloseActionError(error: Exception): String = when {
    error is AteneaApiException && error.status == 403 ->
        "No tienes el permiso requerido para esta acción."
    error is AteneaApiException && error.status == 404 ->
        "La sesión ya no está disponible. Actualiza antes de continuar."
    error is AteneaApiException && error.status == 409 ->
        "El estado cambió. Actualiza antes de continuar."
    error is AteneaApiException && error.status == 422 ->
        "La reconciliación no cumple las condiciones seguras. Actualiza el estado."
    else ->
        "La acción no pudo confirmarse. El estado se conserva; actualiza antes de volver a intentarlo."
}

@Composable
internal fun RemoteCloseOperatorPanel(
    serverState: MobileSessionOperatorState,
    actionState: RemoteCloseActionUiState,
    operatorRole: String?,
    onPrimaryAction: () -> Unit,
    dark: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (!serverState.surfaceEnabled) return

    val roleAllowed = operatorHasRequiredRole(operatorRole, serverState.requiredRole)
    val actionAvailable = remoteCloseActionAllowed(serverState, operatorRole) && !actionState.requiresRefresh
    val foreground = if (dark) ConversationColors.primaryText else MaterialTheme.colorScheme.onSurface
    val secondary = if (dark) ConversationColors.secondaryText else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = when (remoteCloseStateLevel(serverState.state)) {
        OperationalLevel.OK -> if (dark) ConversationColors.action else MaterialTheme.colorScheme.tertiary
        OperationalLevel.CRITICAL -> if (dark) ConversationColors.error else MaterialTheme.colorScheme.error
        else -> if (dark) ConversationColors.action else MaterialTheme.colorScheme.secondary
    }
    val background = if (dark) ConversationColors.codeBackground else Color.Transparent

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("remote-close-operator-state")
            .background(background, RoundedCornerShape(5.dp))
            .border(1.dp, accent, RoundedCornerShape(5.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("ESTADO OPERATIVO", color = secondary, style = MaterialTheme.typography.labelSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                serverState.title,
                modifier = Modifier.weight(1f),
                color = foreground,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                remoteCloseStateLabel(serverState.state),
                modifier = Modifier.padding(start = 8.dp),
                color = accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        serverState.blocker?.let { Text(it, color = secondary, style = MaterialTheme.typography.bodySmall) }
        val requiredRole = serverState.requiredRole
        if (!roleAllowed && requiredRole != null) {
            Text(
                "Requiere ${operatorRoleLabel(requiredRole)}.",
                color = accent,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (serverState.state == "CAPACITY_RELEASED") {
            Text(
                "El reintento es una decisión explícita: no se ha vuelto a enviar ninguna instrucción.",
                color = secondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (serverState.primaryAction !in setOf("NONE", "WAIT")) {
            AteneaButton(
                text = if (actionState.pending) "Comprobando…" else
                    serverState.primaryActionLabel ?: remoteCloseFallbackActionLabel(serverState.primaryAction),
                modifier = Modifier.fillMaxWidth().testTag("remote-close-primary-action"),
                enabled = actionAvailable && !actionState.pending,
                onClick = onPrimaryAction
            )
        }
        if (serverState.primaryAction == "WAIT") {
            Text("Esperando confirmación segura", color = foreground, fontWeight = FontWeight.Bold)
        }
        actionState.error?.let { Text(it, color = if (dark) ConversationColors.error else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        actionState.notice?.let { Text(it, color = accent, style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun remoteCloseStateLevel(state: String): OperationalLevel = when (state) {
    "CAPACITY_RELEASED" -> OperationalLevel.OK
    "REMOTE_CLOSE_BLOCKED", "OWNERSHIP_REVIEW_REQUIRED" -> OperationalLevel.CRITICAL
    else -> OperationalLevel.WARNING
}

internal fun remoteCloseStateLabel(state: String): String = mapOf(
    "CLOSING_REMOTE" to "EN CURSO",
    "REMOTE_CLOSE_BLOCKED" to "BLOQUEADO",
    "CLOSED_OWNER_BLOCKS_CAPACITY" to "BLOQUEO",
    "CLOSED_OWNER_RECONCILING" to "EN CURSO",
    "CAPACITY_RELEASED" to "LISTA",
    "OWNERSHIP_REVIEW_REQUIRED" to "REVISIÓN",
    "DEFAULT" to "LISTA",
    "RUNNING" to "EN CURSO",
    "CLOSED" to "CERRADA"
)[state] ?: "ATENCIÓN"

internal fun remoteCloseFallbackActionLabel(action: String): String = mapOf(
    "RECONCILE_REMOTE_CLOSE" to "Reconciliar cierre",
    "RETRY_AGENT_RUN" to "Reintentar tarea",
    "CONTACT_PLATFORM_ADMINISTRATOR" to "Contactar con administración",
    "WAIT" to "Esperar actualización",
    "NONE" to ""
)[action] ?: "Actualizar"

internal fun operatorRoleLabel(role: String): String = mapOf(
    "ROUTINE_OPERATOR" to "permiso de operador",
    "PRIVILEGED_OPERATOR" to "permiso de operador privilegiado",
    "PLATFORM_ADMINISTRATOR" to "administración de plataforma"
)[role] ?: "un permiso superior"
