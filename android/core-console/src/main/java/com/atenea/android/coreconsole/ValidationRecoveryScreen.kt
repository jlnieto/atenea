package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atenea.android.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ValidationRecoveryScreen(api: AteneaApiClient, sessionId: Long, changeKey: String?,
    onBack: () -> Unit, onNavigate: (ChangeHelpDestination) -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember(sessionId) { mutableStateOf<ValidationRecovery?>(null) }
    var pending by remember(sessionId) { mutableStateOf(false) }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }
    var confirm by remember(sessionId) { mutableStateOf(false) }
    suspend fun read() {
        val value = api.fetchValidationRecovery(sessionId)
        require(value.workSessionId == sessionId && (changeKey == null || value.changeKey.toString() == changeKey))
        state = value
    }
    fun action(block: suspend () -> Unit) {
        if (pending) return
        scope.launch {
            pending = true; error = null
            try { block(); read() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "No se pudo confirmar el resultado. Consulta la misma operación; no se ha creado otro cambio." }
            finally { pending = false }
        }
    }
    LaunchedEffect(sessionId) {
        try { read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "No se pudo consultar la recuperación. Tu cambio se conserva." }
    }
    LaunchedEffect(state?.repair?.id, state?.repair?.state) {
        while (state?.repair?.working == true) {
            delay(1500)
            try { read() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                error = "La operación conserva su estado. Pulsa Consultar resultado cuando vuelva la conexión."
                break
            }
        }
    }
    ValidationRecoveryPanel(state, pending, error, onBack,
        onRefresh = { action { } }, onPrepare = { action { api.prepareValidationRepair(sessionId) } },
        onApply = { confirm = true },
        onRecover = { state?.repair?.let { entry -> action { api.recoverValidationRepair(sessionId, entry.id) } } },
        onCancel = { state?.repair?.let { entry -> action { api.cancelValidationRepair(sessionId, entry.id) } } },
        onInvestigate = { action { api.investigateValidation(sessionId) } },
        onChange = { onNavigate(ChangeHelpDestination.CHANGE) }, onActivity = { onNavigate(ChangeHelpDestination.ACTIVITY) })
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
    onInvestigate: () -> Unit, onChange: () -> Unit, onActivity: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("validation-recovery"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recuperar validación", style = MaterialTheme.typography.titleLarge)
        AteneaTextButton("Volver a la ayuda", onClick = onBack)
        val repair = state?.repair
        when {
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
            Text("La investigación ya tiene una tarea vinculada. Consultar no crea otra.")
            AteneaButton("Ver investigación", enabled = !pending, onClick = onActivity)
        } else if (state?.canInvestigate == true && repair?.working != true && repair?.state != "ATTENTION") {
            AteneaOutlinedButton("Investigar este bloqueo", enabled = !pending, onClick = onInvestigate)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (pending) Text("Enviando la acción. No hace falta pulsar otra vez.")
        AteneaTextButton("Consultar resultado", enabled = !pending, onClick = onRefresh)
        Text("Consultar no ejecuta ni reintenta pruebas.", style = MaterialTheme.typography.bodySmall)
    }
}
