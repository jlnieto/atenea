package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atenea.android.api.CoreClarificationOption
import com.atenea.android.api.CoreCommandResponse
import com.atenea.android.api.CoreCommandSummary
import com.atenea.android.api.CoreScope

@Composable
internal fun CommandCard(
    command: CoreCommandResponse,
    pending: Boolean,
    preferSpeakable: Boolean = false,
    onConfirm: (String) -> Unit,
    onClarification: (CoreClarificationOption) -> Unit
) {
    AteneaPanel {
        Text("Comando #${command.commandId} · ${command.status}", style = MaterialTheme.typography.labelLarge)
        FormattedCommandMessage(
            if (preferSpeakable) {
                command.speakableMessage
                    ?: command.operatorMessage
                    ?: command.resultSummary
                    ?: command.errorMessage
                    ?: command.confirmation?.message
                    ?: command.clarification?.message
                    ?: "Atenea no devolvió mensaje visible."
            } else {
                command.operatorMessage
                    ?: command.speakableMessage
                    ?: command.resultSummary
                    ?: command.errorMessage
                    ?: command.confirmation?.message
                    ?: command.clarification?.message
                    ?: "Atenea no devolvió mensaje visible."
            }
        )
        if (command.hasFailureDiagnostics()) {
            CommandDiagnostics(command)
        }
        command.confirmation?.let { confirmation ->
            AteneaButton(
                text = if (pending) "Confirmando..." else "Confirmar",
                modifier = Modifier.fillMaxWidth(),
                enabled = !pending,
                onClick = { onConfirm(confirmation.confirmationToken) }
            )
        }
        command.clarification?.let { clarification ->
            clarification.options.forEach { option ->
                AteneaButton(
                    text = option.label,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !pending,
                    onClick = { onClarification(option) }
                )
            }
        }
    }
}

@Composable
private fun CommandDiagnostics(command: CoreCommandResponse) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Diagnostico tecnico",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error
        )
        command.errorCode?.takeIf { it.isNotBlank() }?.let {
            DiagnosticLine(label = "Codigo", value = it.displayLabel())
        }
        command.rawInput?.takeIf { it.isNotBlank() }?.let {
            DiagnosticLine(label = "Entrada", value = it)
        }
        command.errorMessage?.takeIf { it.isNotBlank() }?.let {
            DiagnosticLine(label = "Detalle", value = cleanCommandDiagnostic(it))
        }
        command.finishedAt?.takeIf { it.isNotBlank() }?.let {
            DiagnosticLine(label = "Registrado", value = it)
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun CoreCommandResponse.hasFailureDiagnostics(): Boolean =
    status.equals("FAILED", ignoreCase = true) ||
        !errorCode.isNullOrBlank() ||
        !errorMessage.isNullOrBlank()

private fun cleanCommandDiagnostic(message: String): String =
    message
        .replace(" *** ", "\n")
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

@Composable
internal fun FormattedCommandMessage(message: String) {
    val lines = message.lines()
        .map { it.trimEnd() }
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        lines.forEach { line ->
            when {
                line.isBlank() -> SpacerLine()
                line.endsWith(":") -> Text(
                    line.removeSuffix(":"),
                    style = MaterialTheme.typography.labelLarge
                )
                line.startsWith("- ") -> Text(
                    "• ${line.removePrefix("- ").trim()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                line.startsWith("  - ") -> Text(
                    "  • ${line.removePrefix("  - ").trim()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                line.startsWith("Resultado:") -> Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                else -> Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SpacerLine() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 2.dp))
}

@Composable
internal fun HistoryCard(item: CoreCommandSummary) {
    AteneaPanel {
        Column(
            modifier = Modifier.padding(0.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("#${item.commandId} · ${item.status}", style = MaterialTheme.typography.labelMedium)
            Text(item.rawInput, style = MaterialTheme.typography.bodyMedium)
            item.bestMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun CoreScope.label(): String = when (this) {
    CoreScope.GLOBAL -> "Global"
    CoreScope.PROJECT -> "Proyecto"
    CoreScope.SESSION -> "Sesión"
}

internal data class CoreCommandRequestState(
    val input: String,
    val scope: CoreScope,
    val projectId: Long? = null,
    val workSessionId: Long? = null
) {
    fun resolve(option: CoreClarificationOption): CoreCommandRequestState = when {
        option.type == "PROJECT" && option.targetId != null -> copy(
            scope = CoreScope.PROJECT,
            projectId = option.targetId,
            workSessionId = null
        )
        option.type == "WORK_SESSION" && option.targetId != null -> copy(
            scope = CoreScope.SESSION,
            workSessionId = option.targetId
        )
        option.type == "DELIVERABLE_TYPE" -> copy(input = "genera ${option.label}")
        else -> this
    }
}

internal fun String?.displayLabel(): String {
    if (this.isNullOrBlank()) {
        return "Paso"
    }
    val spaced = this
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .lowercase()
    return spaced.replaceFirstChar { it.titlecase() }
}

internal fun String.looksLikeJson(): Boolean {
    val trimmed = trim()
    return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
        (trimmed.startsWith("[") && trimmed.endsWith("]"))
}
