package com.atenea.android.coreconsole

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AteneaUpdateManager(
    private val context: Context,
    private val manifestUrl: String,
    private val currentVersionCode: Int
) {
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (manifestUrl.isBlank()) {
            return@withContext UpdateCheckResult.Unavailable("La app no tiene configurada URL de actualizaciones.")
        }

        val manifest = fetchJson(manifestUrl)
        val update = parseManifest(manifest)

        if (update.versionCode <= currentVersionCode) {
            UpdateCheckResult.UpToDate(update)
        } else {
            UpdateCheckResult.Available(update)
        }
    }

    suspend fun downloadAndOpenInstaller(
        update: AteneaUpdateManifest,
        onProgress: (UpdateDownloadProgress) -> Unit = {}
    ): UpdateInstallResult = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "atenea-update-${update.versionCode}.apk")
        download(update.apkUrl, target, update.sizeBytes, onProgress)

        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return@withContext UpdateInstallResult.NeedsInstallPermission
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            UpdateInstallResult.InstallerOpened
        }
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Atenea ha devuelto HTTP ${connection.responseCode} al comprobar actualización.")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseManifest(json: JSONObject): AteneaUpdateManifest =
        AteneaUpdateManifest(
            versionCode = json.getInt("versionCode"),
            versionName = json.optString("versionName", json.getInt("versionCode").toString()),
            apkUrl = json.getString("apkUrl"),
            sizeBytes = json.optLong("sizeBytes", 0L).takeIf { it > 0 },
            sha256 = json.optString("sha256", "").takeIf { it.isNotBlank() },
            createdAt = json.optString("createdAt", "").takeIf { it.isNotBlank() },
            previousRelease = json.optJSONObject("previousRelease")?.let(::parseRelease)
        )

    private fun parseRelease(json: JSONObject): AteneaReleaseManifest =
        AteneaReleaseManifest(
            versionCode = json.getInt("versionCode"),
            versionName = json.optString("versionName", json.getInt("versionCode").toString()),
            apkUrl = json.getString("apkUrl"),
            sizeBytes = json.optLong("sizeBytes", 0L).takeIf { it > 0 },
            sha256 = json.optString("sha256", "").takeIf { it.isNotBlank() },
            createdAt = json.optString("createdAt", "").takeIf { it.isNotBlank() }
        )

    private fun download(
        url: String,
        target: File,
        expectedSizeBytes: Long?,
        onProgress: (UpdateDownloadProgress) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 120000
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Atenea ha devuelto HTTP ${connection.responseCode} al descargar actualización.")
            }
            val totalBytes = expectedSizeBytes ?: connection.contentLengthLong.takeIf { it > 0 }
            onProgress(UpdateDownloadProgress(downloadedBytes = 0L, totalBytes = totalBytes))
            BufferedInputStream(connection.inputStream).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(UpdateDownloadProgress(downloadedBytes = downloaded, totalBytes = totalBytes))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

data class AteneaUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long?,
    val sha256: String?,
    val createdAt: String?,
    val previousRelease: AteneaReleaseManifest? = null
)

data class AteneaReleaseManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long?,
    val sha256: String?,
    val createdAt: String?
) {
    fun asUpdateManifest(): AteneaUpdateManifest =
        AteneaUpdateManifest(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            createdAt = createdAt
        )
}

data class UpdateDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
}

sealed interface UpdateCheckResult {
    data class Available(val update: AteneaUpdateManifest) : UpdateCheckResult
    data class UpToDate(val latest: AteneaUpdateManifest) : UpdateCheckResult
    data class Unavailable(val reason: String) : UpdateCheckResult
}

sealed interface UpdateInstallResult {
    data object InstallerOpened : UpdateInstallResult
    data object NeedsInstallPermission : UpdateInstallResult
}
