package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.atenea.android.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable
internal fun ValidationRecoveryScreen(api: AteneaApiClient, sessionId: Long, changeKey: String?,
    onBack: () -> Unit, onNavigate: (ChangeHelpDestination) -> Unit, onRunChanged: (Long) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val reads = remember(api, sessionId) { CoalescingRead<Long, Unit>(scope) }
    var generation by remember(sessionId) { mutableStateOf(0L) }
    var state by remember(sessionId) { mutableStateOf<ValidationRecovery?>(null) }
    var pending by remember(sessionId) { mutableStateOf(false) }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }
    var confirm by remember(sessionId) { mutableStateOf(false) }
    var detail by remember(sessionId) { mutableStateOf<CodexRunDetail?>(null) }
    var progress by remember(sessionId) { mutableStateOf<CodexProgressReplay?>(null) }
    var answer by remember(sessionId) { mutableStateOf<String?>(null) }
    var cursor by remember(sessionId) { mutableStateOf(0L) }
    suspend fun read(afterAction: Boolean = false) {
        if (pending && !afterAction) return
        val epoch = generation
        reads.read(epoch) {
            val value = api.fetchValidationRecovery(sessionId)
            if (epoch != generation) return@read
            require(value.workSessionId == sessionId && (changeKey == null || value.changeKey.toString() == changeKey))
            val previous = state?.investigation
            state = value
            val investigation = value.investigation
            val runId = investigation?.runId
            if (detail?.runId != runId) { detail = null; progress = null; answer = null; cursor = 0 }
            if (runId != null) {
                if (previous?.runId != runId || previous?.runStatus != investigation?.runStatus) onRunChanged(runId)
                val terminal = detail?.takeIf { it.runId == runId && it.status in setOf("SUCCEEDED", "FAILED", "CANCELLED") }
                val loaded = if (terminal != null && progress?.terminalOutcome != null) terminal else api.fetchCodexRunDetail(runId)
                if (epoch != generation) return@read
                require(loaded.runId == runId && loaded.workSessionId == sessionId)
                detail = loaded
                if (terminal == null || progress?.terminalOutcome == null) {
                    val replay = api.fetchCodexRunProgress(runId, cursor)
                    if (epoch != generation) return@read
                    progress = mergeCodexProgressReplay(progress, replay)
                    cursor = progress?.latestObservedSequence() ?: cursor
                }
                if (loaded.status in setOf("SUCCEEDED", "FAILED", "CANCELLED") && answer == null) {
                    val conversation = api.fetchMobileWorkSessionConversation(sessionId)
                    if (epoch != generation) return@read
                    answer = conversation.recentTurns.lastOrNull {
                        it.actor == "CODEX" && it.executionProfile?.runId == runId
                    }?.messageText
                }
            }
        }
    }
    fun action(mutates: Boolean = true, block: suspend () -> Unit) {
        if (pending) return
        pending = true; error = null
        if (mutates) generation++
        scope.launch {
            try { block(); read(afterAction = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "No se pudo confirmar el resultado. Consulta la misma operación; no se ha creado otro cambio." }
            finally { pending = false }
        }
    }
    LaunchedEffect(api, sessionId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { read() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "No se pudo consultar la recuperación. Tu cambio se conserva." }
            while (true) {
                snapshotFlow { state?.repair?.working == true || state?.investigation?.let {
                    it.runId != null && it.runStatus !in setOf("SUCCEEDED", "FAILED", "CANCELLED")
                } == true }.first { it }
                delay(1500)
                if (!pending) {
                    try { read(); error = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "No se pudo actualizar esta recuperación. Se conserva el último estado confirmado; no la repitas." }
                }
            }
        }
    }
    ValidationRecoveryPanel(state, pending, error, onBack,
        onRefresh = { action(mutates = false) { } }, onPrepare = { action { api.prepareValidationRepair(sessionId) } },
        onApply = { confirm = true },
        onRecover = { state?.repair?.let { entry -> action { api.recoverValidationRepair(sessionId, entry.id) } } },
        onCancel = { state?.repair?.let { entry -> action { api.cancelValidationRepair(sessionId, entry.id) } } },
        onInvestigate = { action { api.investigateValidation(sessionId) } },
        onChange = { onNavigate(ChangeHelpDestination.CHANGE) }, onActivity = { onNavigate(ChangeHelpDestination.ACTIVITY) },
        investigationDetail = detail, investigationProgress = progress, investigationAnswer = answer,
        onConversation = { onNavigate(ChangeHelpDestination.CHAT) })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Aplicar reparación") },
        text = { Text("Se sustituirá sólo el bloque de versionado Android reconocido. No cambia dependencias, publica ni borra tu trabajo. La revisión nueva necesitará sus propias pruebas.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            state?.repair?.let { entry -> action { api.applyValidationRepair(sessionId, entry.id) } }
        }) { Text("Aplicar reparación") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Volver") } })
}

@Composable
internal fun ValidationRecoveryPanel(state: ValidationRecovery?, pending: Boolean, error: String?, onBack: () -> Unit,
    onRefresh: () -> Unit, onPrepare: () -> Unit, onApply: () -> Unit, onRecover: () -> Unit, onCancel: () -> Unit,
    onInvestigate: () -> Unit, onChange: () -> Unit, onActivity: () -> Unit,
    investigationDetail: CodexRunDetail? = null, investigationProgress: CodexProgressReplay? = null,
    investigationAnswer: String? = null, onConversation: () -> Unit = onActivity) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("validation-recovery"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recuperar validación", style = MaterialTheme.typography.titleLarge)
        AteneaTextButton("Volver a la ayuda", onClick = onBack)
        val repair = state?.repair
        val investigation = state?.investigation
        when {
            investigation?.runId != null -> {
                val status = investigation.runStatus
                Text(investigationHeading(status), style = MaterialTheme.typography.titleMedium)
                Text("Investigación · tarea ${investigation.runId} · revisión ${investigation.sourceRevision}")
                Text(investigationExplanation(status))
                val exact = investigationDetail?.takeIf { it.runId == investigation.runId && it.workSessionId == state?.workSessionId }
                if (exact != null) {
                    Text("Estado confirmado: ${exact.status}", style = MaterialTheme.typography.bodySmall)
                    Text("Tiempo desde la solicitud: ${formatCodexElapsed(exact.elapsedMillis)} (incluye la espera)", style = MaterialTheme.typography.bodySmall)
                    investigationProgress?.latestEvent?.let { Text("Último evento: ${it.message}") }
                    if (investigationAnswer != null) {
                        Text("Resultado de esta investigación", style = MaterialTheme.typography.titleSmall)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(investigationAnswer) }
                    }
                }
                if (status == "SUCCEEDED") {
                    if (state != null && state.sourceRevision > investigation.sourceRevision && state.failureCode == null) {
                        Text("Hay una revisión nueva pendiente de validar. Las pruebas anteriores se conservan como historial.")
                        AteneaButton("Ir a Validar cambio", enabled = !pending, onClick = onChange)
                        AteneaTextButton("Ver respuesta en conversación", enabled = !pending, onClick = onConversation)
                    } else {
                        Text("Bloqueo pendiente: la investigación terminó, pero no hay una revisión corregida confirmada. Revisa su resultado; no repitas las pruebas ni la investigación.")
                        AteneaButton("Ver diagnóstico en conversación", enabled = !pending, onClick = onConversation)
                    }
                } else if (status in setOf("FAILED", "CANCELLED")) {
                    AteneaButton("Ver tarea y recuperación", enabled = !pending, onClick = onActivity)
                }
            }
            repair?.working == true -> {
                Text("Atenea está trabajando", style = MaterialTheme.typography.titleMedium)
                Text(if (repair.state.startsWith("PREPAR")) "Preparando la reparación. Todavía no se está modificando tu código."
                    else "Aplicando la reparación confirmada. Puedes salir de la app; no repitas la acción.")
            }
            repair?.state == "READY" -> {
                Text("Te toca a ti · reparación preparada", style = MaterialTheme.typography.titleMedium)
                Text("Atenea ha reconocido el bloque antiguo de versionado. Sólo sustituirá ese bloque; conservará tu funcionalidad y conversación. No publica ni valida automáticamente.")
                AteneaButton("Aplicar reparación", enabled = !pending, onClick = onApply)
                AteneaTextButton("Descartar preparación sin cambiar código", enabled = !pending, onClick = onCancel)
            }
            repair?.state == "APPLIED" -> {
                Text("Reparación aplicada", style = MaterialTheme.typography.titleMedium)
                Text("Tu trabajo sigue en este mismo cambio. Las pruebas anteriores son historial: ahora hay que validar la revisión reparada.")
                AteneaButton("Ir a Validar cambio", enabled = !pending, onClick = onChange)
            }
            repair?.state == "ATTENTION" -> {
                Text("La reparación necesita atención", style = MaterialTheme.typography.titleMedium)
                Text("No se da por terminada ni se fuerza otro cambio. Recupera la misma operación para comprobar qué quedó aplicado.")
                AteneaButton("Recuperar la misma operación", enabled = !pending, onClick = onRecover)
                AteneaTextButton("Descartar si no se llegó a aplicar", enabled = !pending, onClick = onCancel)
            }
            state?.canPrepareRepair == true -> {
                Text("Atenea está detenida · hay una reparación conocida", style = MaterialTheme.typography.titleMedium)
                Text("Android no empezó: la configuración heredada no coincide con el validador. Esto no demuestra un fallo de tu funcionalidad. Repetir las pruebas no lo arreglará.")
                AteneaButton("Preparar reparación", enabled = !pending, onClick = onPrepare)
                Text("Preparar no cambia código. Después podrás revisar y confirmar.", style = MaterialTheme.typography.bodySmall)
            }
            state?.failureCode != null -> {
                Text("El bloqueo necesita investigación", style = MaterialTheme.typography.titleMedium)
                Text("No hay una reparación automática confirmada para este caso. Puedes encargar su diagnóstico y corrección en esta misma conversación; no se repetirá el prompt funcional ni se publicará nada.")
            }
            state != null -> Text("No hay un fallo vigente que necesite reparación. Consulta el estado del cambio.")
            else -> Text("Consultando el estado guardado…")
        }
        if (state?.investigation?.runId != null) {
            Text("Esta pantalla se actualiza automáticamente. No hace falta volver atrás ni crear otra investigación.", style = MaterialTheme.typography.bodySmall)
        } else if (state?.canInvestigate == true && repair?.working != true && repair?.state != "ATTENTION") {
            AteneaOutlinedButton("Investigar este bloqueo", enabled = !pending, onClick = onInvestigate)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (pending) Text("Enviando la acción. No hace falta pulsar otra vez.")
        AteneaTextButton("Consultar resultado", enabled = !pending, onClick = onRefresh)
        Text("Consultar no ejecuta ni reintenta pruebas.", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun investigationHeading(status: String?): String = when (status) {
    "QUEUED" -> "Pendiente de arrancar"
    "STARTING" -> "Preparando la investigación"
    "RUNNING" -> "Investigación en curso"
    "SUCCEEDED" -> "Investigación terminada"
    "FAILED", "CANCELLED" -> "La investigación necesita atención"
    "RECONCILING" -> "Comprobando la ejecución"
    "CANCELLING" -> "Confirmando la cancelación"
    else -> "Comprobando el estado de la investigación"
}

internal fun investigationExplanation(status: String?): String = when (status) {
    "QUEUED" -> "La petición está guardada, pero Codex todavía no ha arrancado. Atenea comprueba el arranque de esta misma tarea; no la repitas."
    "STARTING" -> "Atenea está preparando esta tarea. Todavía no confirma que Codex haya empezado."
    "RUNNING" -> "La investigación está en curso. El último evento indica su fase confirmada. Puedes salir de la app; no repitas la petición."
    "SUCCEEDED" -> "Consulta el resultado debajo. Terminar la investigación no confirma una corrección, no valida ni publica el cambio."
    "FAILED", "CANCELLED" -> "La tarea no terminó correctamente. Tu cambio se conserva; consulta esta misma tarea antes de hacer otro intento."
    "RECONCILING", "CANCELLING" -> "Atenea está confirmando qué ocurrió con esta misma tarea. No está afirmando que Codex siga trabajando."
    else -> "No se ha confirmado todavía el estado actual. No se muestra una ejecución anterior como si fuera esta investigación."
}
