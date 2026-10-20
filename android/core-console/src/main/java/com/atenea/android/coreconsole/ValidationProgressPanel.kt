package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.atenea.android.api.DevelopmentChangeValidationEvidence
import java.time.Instant
import kotlinx.coroutines.delay

/** The clock only formats persisted timestamps; it never polls or advances work. */
@Composable
internal fun ValidationProgressPanel(evidence: DevelopmentChangeValidationEvidence?, sessionId: Long,
    validationRequested: Boolean, observationConfirmed: Boolean) {
    val owner = LocalLifecycleOwner.current
    val ticking = validationRequested || (evidence?.workflow?.running ?: (evidence?.operations?.any { it.status == "RUNNING" } == true))
    var now by remember(sessionId) { mutableStateOf(Instant.now()) }
    LaunchedEffect(owner, ticking, evidence) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                now = Instant.now()
                if (ticking) delay(1_000)
            } while (ticking)
        }
    }
    val progress = validationProgressUi(evidence, sessionId, now, validationRequested, observationConfirmed) ?: return
    Column(Modifier.fillMaxWidth().testTag("validation-progress"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${progress.passed} de 4 comprobaciones correctas · revisión ${progress.revision}",
            style = MaterialTheme.typography.titleSmall)
        progress.totalTime?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        progress.steps.forEach { step ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (step.status) {
                    ValidationProgressStatus.PASSED -> "✓"
                    ValidationProgressStatus.STOPPED -> "!"
                    ValidationProgressStatus.PENDING -> "○"
                    else -> "◷"
                }, color = if (step.status == ValidationProgressStatus.STOPPED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(step.label, style = MaterialTheme.typography.bodyMedium)
                    if (step.status in setOf(ValidationProgressStatus.PASSED, ValidationProgressStatus.STOPPED)) {
                        Text(listOfNotNull(step.statusLabel, step.timing?.removePrefix("Duración: ")).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(step.statusLabel, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        step.timing?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        progress.confirmation?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (progress.uncertain) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
