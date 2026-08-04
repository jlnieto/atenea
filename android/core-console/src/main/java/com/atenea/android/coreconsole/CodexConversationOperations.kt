package com.atenea.android.coreconsole

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.atenea.android.api.CodexCatalog
import com.atenea.android.api.CodexCatalogModel
import com.atenea.android.api.CodexProgressReplay
import com.atenea.android.api.CodexRecoveryAction
import com.atenea.android.api.CodexRunDetail
import com.atenea.android.api.CodexSettings

internal data class EffectiveCodexProfile(
    val catalog: CodexCatalog,
    val model: CodexCatalogModel,
    val reasoningEffort: String,
    val modelSource: String,
    val effortSource: String
)

internal fun resolveEffectiveCodexProfile(
    catalog: CodexCatalog,
    session: CodexSettings,
    project: CodexSettings?
): EffectiveCodexProfile {
    val available = catalog.models.filter { it.availability == "AVAILABLE" }
    val modelId = session.modelId ?: project?.modelId ?: available.firstOrNull()?.modelId
        ?: error("No hay un modelo Codex disponible.")
    val model = available.firstOrNull { it.modelId == modelId }
        ?: error("El modelo efectivo no está disponible en el catálogo actual.")
    val effort = session.reasoningEffort ?: project?.reasoningEffort ?: model.defaultEffort
    require(effort in model.efforts) { "El esfuerzo efectivo no es compatible con el modelo actual." }
    return EffectiveCodexProfile(
        catalog = catalog,
        model = model,
        reasoningEffort = effort,
        modelSource = if (session.modelId != null) "Sesión" else if (project?.modelId != null) "Proyecto" else "Worker",
        effortSource = if (session.reasoningEffort != null) "Sesión" else if (project?.reasoningEffort != null) "Proyecto" else "Worker"
    )
}

@Composable
internal fun CodexExecutionProfileStrip(
    profile: EffectiveCodexProfile?,
    draftModelId: String,
    draftEffort: String,
    pending: Boolean,
    error: String?,
    onModelChange: (String) -> Unit,
    onEffortChange: (String) -> Unit,
    onApply: () -> Unit
) {
    if (profile == null && error == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConversationColors.composerBar)
            .border(1.dp, ConversationColors.codeBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (profile == null) {
            Text(error ?: "Perfil no disponible", color = ConversationColors.error, style = ConversationTypography.meta)
            return@Column
        }
        val selectedModel = profile.catalog.models.firstOrNull { it.modelId == draftModelId }
        val dirty = draftModelId != profile.model.modelId || draftEffort != profile.reasoningEffort
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("PRÓXIMA EJECUCIÓN", color = ConversationColors.mutedText, style = ConversationTypography.meta)
                Text(if (dirty) "Cambios sin aplicar" else "Perfil listo", color = ConversationColors.primaryText, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Codex ${profile.catalog.codexVersion}", color = ConversationColors.primaryText, fontWeight = FontWeight.Bold)
                Text("Modelo ${profile.modelSource} · esfuerzo ${profile.effortSource}", color = ConversationColors.mutedText, style = ConversationTypography.meta)
            }
        }
        ChoiceRow(
            label = "Modelo",
            values = profile.catalog.models.filter { it.availability == "AVAILABLE" }.map { it.modelId to it.displayName },
            selected = draftModelId,
            enabled = !pending,
            onSelect = onModelChange
        )
        ChoiceRow(
            label = "Esfuerzo",
            values = selectedModel?.efforts.orEmpty().map { it to it },
            selected = draftEffort,
            enabled = !pending,
            onSelect = onEffortChange
        )
        error?.let { Text(it, color = ConversationColors.error, style = ConversationTypography.meta) }
        if (dirty) {
            ConversationPrimaryAction(if (pending) "Aplicando…" else "Aplicar perfil", !pending, onApply)
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    values: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = ConversationColors.mutedText, style = ConversationTypography.meta)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            values.forEach { (value, display) ->
                val chosen = value == selected
                Text(
                    display,
                    modifier = Modifier
                        .background(if (chosen) ConversationColors.action else ConversationColors.codeBackground, RoundedCornerShape(3.dp))
                        .border(1.dp, if (chosen) ConversationColors.action else ConversationColors.codeBorder, RoundedCornerShape(3.dp))
                        .clickable(enabled = enabled) { onSelect(value) }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    color = if (chosen) ConversationColors.background else ConversationColors.primaryText,
                    style = ConversationTypography.meta
                )
            }
        }
    }
}

@Composable
internal fun CodexRunProgressCard(
    detail: CodexRunDetail?,
    progress: CodexProgressReplay?,
    pending: Boolean,
    error: String?,
    notice: String?,
    retryOverride: String? = null,
    onRecovery: (CodexRecoveryAction) -> Unit
) {
    if (detail == null) {
        error?.let { Text(it, color = ConversationColors.error, style = ConversationTypography.meta) }
        return
    }
    val state = progress?.currentState ?: detail.currentState ?: detail.status
    val nextAction = progress?.requiredNextAction ?: detail.requiredNextAction ?: "NONE"
    val action = codexRecoveryAction(detail, nextAction, state, suppressRetry = retryOverride != null)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConversationColors.codeBackground, RoundedCornerShape(5.dp))
            .border(1.dp, ConversationColors.action, RoundedCornerShape(5.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("EJECUCIÓN ACTUAL", color = ConversationColors.mutedText, style = ConversationTypography.meta)
                Text(codexProgressLabel(state), color = ConversationColors.primaryText, fontWeight = FontWeight.Bold)
            }
            Text(state, color = ConversationColors.action, style = ConversationTypography.meta, fontWeight = FontWeight.Bold)
        }
        Text("${formatCodexElapsed(progress?.elapsedMillis ?: detail.elapsedMillis)} · ${detail.modelId ?: "-"} · ${detail.reasoningEffort ?: "-"} · Codex ${detail.codexVersion ?: "-"}", color = ConversationColors.secondaryText, style = ConversationTypography.meta)
        progress?.latestEvent?.let {
            Text("Último evento: ${it.message}", color = ConversationColors.primaryText, style = ConversationTypography.meta)
        }
        Text(
            "Siguiente acción: ${retryOverride ?: codexNextActionLabel(nextAction)}",
            color = ConversationColors.primaryText,
            fontWeight = FontWeight.Bold,
            style = ConversationTypography.meta
        )
        action?.let {
            ConversationPrimaryAction(if (pending) "Solicitando…" else codexRecoveryActionLabel(it), !pending) { onRecovery(it) }
        }
        progress?.events.orEmpty().takeLast(4).forEach { event ->
            Text("• ${codexProgressLabel(event.category)} — ${event.message}", color = ConversationColors.secondaryText, style = ConversationTypography.meta)
        }
        error?.let { Text(it, color = ConversationColors.error, style = ConversationTypography.meta) }
        notice?.let { Text(it, color = ConversationColors.action, style = ConversationTypography.meta) }
    }
}

@Composable
private fun ConversationPrimaryAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (enabled) ConversationColors.action else ConversationColors.disabledAction, RoundedCornerShape(3.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        color = ConversationColors.primaryText,
        fontWeight = FontWeight.Bold,
        style = ConversationTypography.meta,
        textAlign = TextAlign.Center
    )
}

internal fun codexRecoveryAction(
    detail: CodexRunDetail,
    nextAction: String,
    state: String,
    suppressRetry: Boolean = false
): CodexRecoveryAction? = when {
    nextAction == "REQUEST_RECONCILIATION" -> CodexRecoveryAction.RECONCILE
    nextAction == "RETRY" && !suppressRetry -> CodexRecoveryAction.RETRY
    state !in setOf("COMPLETED", "SUCCEEDED", "FAILED", "CANCELLED", "RECONCILING") &&
        detail.status !in setOf("SUCCEEDED", "FAILED", "CANCELLED") -> CodexRecoveryAction.CANCEL
    else -> null
}

internal fun codexProgressLabel(state: String): String = mapOf(
    "ACCEPTED" to "Aceptada", "QUEUED" to "En cola", "PREPARING_WORKSPACE" to "Preparando workspace",
    "CODEX_STARTED" to "Codex iniciado", "INSPECTING_PROJECT" to "Revisando proyecto",
    "RUNNING_COMMAND" to "Ejecutando comprobación", "CHECKING" to "Comprobando cambios",
    "WAITING" to "Esperando", "RECONCILING" to "Reconciliando", "FINALIZING" to "Finalizando",
    "COMPLETED" to "Completada", "SUCCEEDED" to "Completada", "FAILED" to "Fallida", "CANCELLED" to "Cancelada"
)[state] ?: state

internal fun codexNextActionLabel(action: String): String = mapOf(
    "NONE" to "Ninguna; puedes continuar", "WAIT" to "Esperar la siguiente actualización",
    "RETRY" to "Reintentar de forma segura", "REQUEST_RECONCILIATION" to "Solicitar reconciliación",
    "CONTACT_PRIVILEGED_OPERATOR" to "Contactar con un operador autorizado",
    "CONTACT_PLATFORM_ADMINISTRATOR" to "Contactar con administración"
)[action] ?: action

internal fun codexRecoveryActionLabel(action: CodexRecoveryAction): String = when (action) {
    CodexRecoveryAction.CANCEL -> "Cancelar ejecución"
    CodexRecoveryAction.RETRY -> "Reintentar"
    CodexRecoveryAction.RECONCILE -> "Solicitar reconciliación"
}

internal fun formatCodexElapsed(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1_000)
    val minutes = seconds / 60
    val remainder = seconds % 60
    return if (minutes > 0) "$minutes min ${remainder.toString().padStart(2, '0')} s" else "$remainder s"
}

internal fun mergeCodexProgressReplay(
    current: CodexProgressReplay?,
    replay: CodexProgressReplay
): CodexProgressReplay {
    val retainedEvents = if (replay.cursorWasBelowRetainedFloor) {
        replay.events
    } else {
        current?.events.orEmpty() + replay.events
    }
    return replay.copy(
        events = retainedEvents
            .filter { it.sequence >= replay.retainedFloor }
            .associateBy { it.sequence }
            .toSortedMap()
            .values
            .toList()
            .takeLast(200),
        latestEvent = replay.latestEvent ?: current?.latestEvent
    )
}

internal fun CodexProgressReplay.latestObservedSequence(): Long = maxOf(
    requestedAfterSequence,
    latestEvent?.sequence ?: 0,
    events.maxOfOrNull { it.sequence } ?: 0
)
