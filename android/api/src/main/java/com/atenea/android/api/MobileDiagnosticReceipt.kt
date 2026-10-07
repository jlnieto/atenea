package com.atenea.android.api

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

data class MobileDiagnosticReceipt(
    val id: UUID,
    val receivedAt: String,
    val generatedAt: String,
    val appVersionName: String,
    val appVersionCode: Int,
    val deviceModel: String,
    val sizeBytes: Int,
    val sha256: String,
    val contentPath: String
)

internal const val MAX_DIAGNOSTIC_REPORT_BYTES = 4 * 1024 * 1024

internal fun parseMobileDiagnosticReceipt(json: JSONObject): MobileDiagnosticReceipt {
    val id = UUID.fromString(json.getString("id"))
    val contentPath = json.getString("contentPath")
    require(contentPath == "/api/mobile/diagnostics/$id/content")
    val receivedAt = json.getString("receivedAt").also { Instant.parse(it) }
    val generatedAt = json.getString("generatedAt").also { Instant.parse(it) }
    val versionName = json.getString("appVersionName").also { require(it.isNotBlank() && it.length <= 80) }
    val versionCode = json.getInt("appVersionCode").also { require(it > 0) }
    val model = json.getString("deviceModel").also { require(it.isNotBlank() && it.length <= 150) }
    val size = json.getLong("sizeBytes").also { require(it in 1..MAX_DIAGNOSTIC_REPORT_BYTES.toLong()) }.toInt()
    val checksum = json.getString("sha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    return MobileDiagnosticReceipt(id, receivedAt, generatedAt, versionName, versionCode, model,
        size, checksum, contentPath)
}

internal fun MobileDiagnosticReceipt.verifyContent(bytes: ByteArray) {
    require(bytes.size == sizeBytes && bytes.size in 1..MAX_DIAGNOSTIC_REPORT_BYTES) {
        "El tamaño del diagnóstico no coincide con el recibo."
    }
    val checksum = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    require(checksum == sha256) { "El checksum del diagnóstico no coincide con el recibo." }
}
