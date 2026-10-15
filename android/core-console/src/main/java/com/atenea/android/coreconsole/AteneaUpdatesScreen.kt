package com.atenea.android.coreconsole

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atenea.android.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AteneaUpdatesScreen(api: AteneaApiClient,currentVersionCode: Int,currentVersionName: String,onOpenUpdates: () -> Unit) {
    val scope=rememberCoroutineScope()
    var state by remember { mutableStateOf<AteneaProductUpdates?>(null) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load(page: Int) {
        if(pending)return
        scope.launch {
            pending=true;error=null
            try {
                val fresh=api.fetchAteneaUpdates(page)
                val previous=state
                val sameSources=previous?.backend?.sourceCommit==fresh.backend?.sourceCommit
                    && previous?.android?.sourceCommit==fresh.android?.sourceCommit && previous?.platform?.sourceCommit==fresh.platform?.sourceCommit
                state=if(page==1)fresh else if(!sameSources)api.fetchAteneaUpdates(1)
                    else fresh.copy(entries=((previous?.entries ?: emptyList())+fresh.entries).distinctBy { it.id })
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { error="No se pudieron consultar las novedades. No se ha publicado ni ejecutado nada." }
            finally { pending=false }
        }
    }
    LaunchedEffect(api) { load(1) }
    AteneaUpdatesContent(state,currentVersionCode,currentVersionName,pending,error,onRefresh={load(1)},
        onMore={state?.nextPage?.let(::load)},onOpenUpdates=onOpenUpdates)
}

@Composable
internal fun AteneaUpdatesContent(state: AteneaProductUpdates?,installedCode: Int,installedName: String,pending: Boolean,error: String?,
    onRefresh: () -> Unit,onMore: () -> Unit,onOpenUpdates: () -> Unit) {
    var filter by remember { mutableStateOf<MobileDeliveryTarget?>(null) }
    var details by remember { mutableStateOf<String?>(null) }
    val uri=LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp).testTag("atenea-updates"),
        contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Text("Novedades de Atenea",style=MaterialTheme.typography.headlineSmall) }
        item { Text("Mejoras del móvil, backend y worker. Integrado no significa publicado.",style=MaterialTheme.typography.bodySmall) }
        item { AteneaOutlinedButton(if(pending)"Consultando…" else "Actualizar información",enabled=!pending,onClick=onRefresh) }
        error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
        state?.let { value ->
            item { Card { Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("Qué tienes que hacer",style=MaterialTheme.typography.titleMedium)
                Text("Tu móvil: $installedName / $installedCode")
                value.android?.let { apk ->
                    Text("App disponible: ${apk.versionName} / ${apk.versionCode}")
                    Text(if(installedCode<apk.versionCode)"Hay una actualización para instalar. No necesitas abrir un Cambio."
                        else if(installedCode==apk.versionCode)"Tu aplicación coincide con la versión del canal."
                        else "La versión instalada no coincide con la del canal; comprueba la información antes de cambiarla.")
                    if(installedCode<apk.versionCode)AteneaButton("Actualizar Atenea",onClick=onOpenUpdates)
                } ?: Text("La versión del canal está pendiente de confirmar.")
                Text("Backend: "+when(value.backend?.healthy) {true -> "verificado y sano";false -> "necesita atención";null -> "sin confirmar"})
                Text("Worker: "+when(value.workerHealthy) {true -> "conectado";false -> "necesita atención";null -> "sin confirmar"})
                if(value.platform==null)Text("La versión instalada del worker no se ha confirmado.",style=MaterialTheme.typography.bodySmall)
            } } }
            if(value.historyState!="CURRENT")item { Text(if(value.historyState=="CACHED")"Historial guardado; no se pudo refrescar ahora."
                else "Historial temporalmente no disponible. Las versiones se comprueban por separado.") }
            item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(null to "Todo",MobileDeliveryTarget.ANDROID_STABLE to "Móvil",MobileDeliveryTarget.APP_PROD to "Backend",
                    MobileDeliveryTarget.AX42_PLATFORM to "Worker").forEach { (target,label) ->
                    FilterChip(selected=filter==target,onClick={filter=target},label={Text(label)})
                }
            } }
                items(value.entries.filter { filter==null || it.requiredTargets?.contains(filter)==true },key={it.id}) { entry ->
                    Card { Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(entry.title,style=MaterialTheme.typography.titleMedium)
                        Text(newsDate(entry.integratedAt)+" · Integrado",style=MaterialTheme.typography.labelSmall)
                        if(entry.summary.isNotBlank() && entry.summary!=entry.title)Text(entry.summary)
                        val targets=entry.requiredTargets
                        if(targets==null)Text("Destinos y publicación sin confirmar.")
                        else if(targets.isEmpty())Text("Documentación o cambio sin publicación de runtime.")
                        entry.publications.forEach { (target,status) -> Text("${target.label}: "+when(status) {
                            "PUBLISHED" -> "disponible";"NOT_INCLUDED" -> "integrado, aún no publicado";
                            "PUBLISHED_NEEDS_ATTENTION" -> "publicado, necesita atención";else -> "publicación sin confirmar"
                        },style=MaterialTheme.typography.bodySmall) }
                        AteneaTextButton(if(details==entry.id)"Ocultar detalle" else "Ver detalle",onClick={details=if(details==entry.id)null else entry.id})
                        if(details==entry.id) {
                            Text("Referencia ${entry.id} · ${entry.mergeCommit.take(12)}",style=MaterialTheme.typography.bodySmall)
                            AteneaTextButton("Revisar PR",onClick={uri.openUri(entry.pullRequestUrl)})
                        }
                    } }
                }
                if(value.nextPage!=null)item { AteneaOutlinedButton("Cargar más novedades",enabled=!pending,onClick=onMore) }
                if(value.entries.isEmpty() && value.historyState=="CURRENT")item { Text("No hay novedades en esta página.") }
        }
    }
}

private fun newsDate(value: String): String = runCatching {
    java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(
        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM,java.time.format.FormatStyle.SHORT))
}.getOrDefault(value)
