package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient

@Composable
internal fun SettingsScreen(
    apiClient: AteneaApiClient,
    updateState: UpdateCheckResult?,
    updateMessage: String?,
    updateProgress: UpdateDownloadProgress?,
    checkingUpdate: Boolean,
    currentVersionCode: Int,
    currentVersionName: String,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AteneaUpdateManifest) -> Unit
) {
    val latestManifest = when (updateState) {
        is UpdateCheckResult.Available -> updateState.update
        is UpdateCheckResult.UpToDate -> updateState.latest
        else -> null
    }
    val previousRelease = latestManifest?.previousRelease

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AteneaSpacing.medium)
    ) {
        AteneaPanel {
            Text("App", style = MaterialTheme.typography.titleMedium)
            MetricLine("Version name", currentVersionName)
            MetricLine("Version code", currentVersionCode.toString())
        }

        AteneaPanel {
            Text("Actualizaciones", style = MaterialTheme.typography.titleMedium)
            when (updateState) {
                is UpdateCheckResult.Available -> {
                    Text("Disponible ${updateState.update.versionName}.", style = MaterialTheme.typography.bodyMedium)
                    MetricLine("Version code", updateState.update.versionCode.toString())
                    updateState.update.sizeBytes?.let { MetricLine("Tamaño", it.formatBytes()) }
                    updateState.update.createdAt?.let { MetricLine("Publicada", it.formatDateTimeForDisplay()) }
                    updateState.update.sha256?.let { MetricLine("SHA-256", it.take(12) + "…") }
                    updateProgress?.let { progress ->
                        val fraction = progress.fraction
                        if (fraction == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { fraction })
                        }
                        Text(
                            "${progress.downloadedBytes.formatBytes()} / ${progress.totalBytes?.formatBytes() ?: "desconocido"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    AteneaButton(
                        text = if (checkingUpdate) "Preparando..." else "Instalar",
                        enabled = !checkingUpdate,
                        onClick = { onInstallUpdate(updateState.update) }
                    )
                }
                is UpdateCheckResult.UpToDate -> {
                    Text("La app está al día.")
                    MetricLine("Última versión", updateState.latest.versionName)
                    updateState.latest.sizeBytes?.let { MetricLine("Tamaño APK", it.formatBytes()) }
                    updateState.latest.createdAt?.let { MetricLine("Publicada", it.formatDateTimeForDisplay()) }
                }
                is UpdateCheckResult.Unavailable -> Text(updateState.reason)
                null -> Text("Sin comprobación reciente.")
            }
            updateMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            AteneaOutlinedButton(
                text = if (checkingUpdate) "Comprobando..." else "Comprobar",
                enabled = !checkingUpdate,
                onClick = onCheckUpdate
            )
            previousRelease?.let { release ->
                Text("Version anterior archivada ${release.versionName}.", style = MaterialTheme.typography.bodySmall)
                release.sizeBytes?.let { MetricLine("Tamaño anterior", it.formatBytes()) }
                release.createdAt?.let { MetricLine("Publicada anterior", it.formatDateTimeForDisplay()) }
                if (release.versionCode > currentVersionCode) {
                    AteneaOutlinedButton(
                        text = if (checkingUpdate) "Preparando..." else "Instalar recuperacion",
                        enabled = !checkingUpdate,
                        onClick = { onInstallUpdate(release.asUpdateManifest()) }
                    )
                } else {
                    Text(
                        "Android no permite instalar una versionCode inferior encima. Para volver, Atenea debe publicar una build de recuperacion con versionCode nuevo.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

internal object AteneaSpacing {
    val medium = 10.dp
}

internal fun Long.formatBytes(): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    return if (index == 0) "${value.toLong()} ${units[index]}" else "%.1f %s".format(value, units[index])
}

internal fun String.formatDateTimeForDisplay(): String =
    replace("T", " ").substringBefore(".").removeSuffix("+00:00")
