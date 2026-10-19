package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.atenea.android.api.MobileGitHubProgress
import com.atenea.android.api.MobileStorageProgress
import java.time.Instant
import kotlinx.coroutines.delay

internal fun storageProgressTitle(storage: MobileStorageProgress): String = when(storage.phase) {
    "CHECKING_SPACE" -> "Comprobando espacio para publicar"
    "RECLAIMING" -> "Retirando artefactos antiguos de Atenea"
    "SPACE_READY" -> "Espacio preparado"
    "DOWNLOADING" -> "Descargando la versión verificada"
    "EXTRACTING" -> "Verificando y extrayendo el artefacto"
    "STAGING" -> "Preparando la versión para publicar"
    "READY" -> "Versión preparada · falta confirmar la publicación"
    "INSUFFICIENT_SPACE" -> "No hay suficiente espacio seguro para preparar la versión"
    else -> "La retención necesita revisión · los datos se conservan"
}
internal fun storageCapacityDetail(storage: MobileStorageProgress): String {
    fun gb(bytes: Long)=String.format(java.util.Locale.forLanguageTag("es"),"%.1f GB",bytes/1_000_000_000.0)
    return if(storage.phase=="INSUFFICIENT_SPACE") "Faltan ${gb(storage.missingBytes)}. No se ha desplegado ni se han borrado datos protegidos."
        else "Espacio libre observado: ${gb(storage.freeBytes)}."
}

internal fun githubProgressTitle(progress: MobileGitHubProgress, now: Long = Instant.now().epochSecond): String = when {
    progress.observedAt !in (now-90)..(now+5) -> "Último estado de GitHub · pendiente de confirmar"
    else -> when(progress.state) {
    "NOT_STARTED" -> "Esperando a que GitHub registre la comprobación"
    "QUEUED" -> "Esperando turno en GitHub"
    "RUNNING" -> "GitHub está ejecutando las comprobaciones"
    "CLOSING" -> "Pruebas correctas · esperando confirmación de GitHub"
    "PASSED" -> "Comprobaciones correctas · confirmando el siguiente paso"
    "FAILED" -> "GitHub ha registrado una comprobación fallida"
    "BASE_CHANGED" -> "Main cambió: hay que actualizar la base de la misma PR"
    else -> "Estado de GitHub por confirmar"
    }
}

internal fun progressAge(observedAt: Long?, now: Long): String = when {
    observedAt==null -> "Sin una lectura confirmada todavía."
    observedAt>now+5 -> "La hora del dispositivo no coincide con la observación."
    now-observedAt>90 -> "Sin confirmación reciente (última: hace ${validationDuration(now-observedAt)}). Esto no demuestra un fallo; no repitas la acción."
    else -> "Última consulta confirmada: hace ${validationDuration(now-observedAt)}."
}

/** A passive presentation. The local clock never polls, starts work or estimates completion. */
@Composable
internal fun OperationProgressPanel(state: MobileDeliveryUiState, step: DeliveryStepUi) {
    val progress=state.progress?.takeIf { it.operationId==step.operation?.id || it.kind=="BUILD" && step.operation==null }
    val storage=progress?.storage
    if(step.status !in setOf(DeliveryStepStatus.WORKING,DeliveryStepStatus.WAITING,DeliveryStepStatus.CHECKING)
        && !(step.status==DeliveryStepStatus.ATTENTION && storage!=null)) return
    val owner=LocalLifecycleOwner.current
    var now by remember { mutableStateOf(Instant.now().epochSecond) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) { now=Instant.now().epochSecond;delay(1_000) }
        }
    }
    val github=progress?.github
    val start=progress?.startedAt ?: step.operation?.createdAt?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
    Column(Modifier.testTag("operation-progress"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(start!=null && start<=now) Text((if(progress?.kind=="BUILD") "Desde la integración: " else "Desde la solicitud: ")+validationDuration(now-start),style=MaterialTheme.typography.bodySmall)
        if(storage!=null) {
            Text((if(storage.observedAt in (now-90)..(now+5)) "" else "Última fase confirmada: ")+storageProgressTitle(storage),style=MaterialTheme.typography.titleSmall)
            Text(storageCapacityDetail(storage),style=MaterialTheme.typography.bodySmall)
            Text(progressAge(storage.observedAt,now),style=MaterialTheme.typography.bodySmall)
        } else if(github!=null) {
            val fresh=github.state!="UNAVAILABLE" && github.observedAt in (now-90)..(now+5)
            Text(githubProgressTitle(github,now),style=MaterialTheme.typography.titleSmall)
            if(github.checks.isNotEmpty()) Text((if(fresh) "" else "Último detalle: ")+"${github.checks.count { it.state=="PASSED" }} de ${github.checks.size} comprobaciones correctas",style=MaterialTheme.typography.bodySmall)
            github.checks.forEach { check ->
                val label=(if(fresh) "" else "Último estado: ")+when(check.state) { "PASSED" -> "✓ Correcta";"FAILED" -> "! Fallida";"RUNNING" -> "En ejecución";
                    "QUEUED" -> "En cola"; "NOT_STARTED" -> "Todavía no registrada"; "BASE_CHANGED" -> "Base anterior a main"; else -> "Por confirmar" }
                val end=check.finishedAt ?: (if(fresh) now else github.observedAt).takeIf { check.state=="RUNNING" }
                val duration=check.startedAt?.let { first -> end?.takeIf { it>=first }?.let { " · ${validationDuration(it-first)}" } }.orEmpty()
                Text("${check.label}: $label$duration",style=MaterialTheme.typography.bodySmall)
            }
            if(github.state!="UNAVAILABLE") Text(progressAge(github.observedAt,now),style=MaterialTheme.typography.bodySmall)
            if(github.serviceState=="DEGRADED") Text("GitHub informa de una incidencia en Actions que puede provocar retrasos. Tu cambio se conserva; Atenea seguirá consultando.",
                style=MaterialTheme.typography.bodySmall)
            if(github.state=="UNAVAILABLE") Text("No se pudo confirmar el estado de GitHub. No se presupone un fallo ni se repite la operación.",style=MaterialTheme.typography.bodySmall)
        } else {
            if(state.build?.state=="PENDING") Text("Fase de construcción: ${buildPhaseLabel(state.build?.phase)}",style=MaterialTheme.typography.bodySmall)
            Text(progressAge(state.lastObservedAt?.epochSecond,now),style=MaterialTheme.typography.bodySmall)
        }
    }
}
