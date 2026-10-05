package com.atenea.android.api

import org.json.JSONObject
import java.util.UUID

data class DevelopmentChangeValidationEvidence(
    val changeKey: UUID,
    val workSessionId: Long,
    val sourceRevision: Long,
    val sourceFingerprintSha256: String?,
    val validationState: String,
    val passedOperations: Int,
    val requiredOperations: Int,
    val operations: List<DevelopmentChangeValidationAttempt>,
    val lastAttempt: DevelopmentChangeValidationAttempt?
)

data class DevelopmentChangeValidationAttempt(
    val id: UUID,
    val workSessionId: Long,
    val operation: String,
    val status: String,
    val sourceTreeFingerprintSha256: String,
    val definitionRevision: String,
    val exitCode: Int?,
    val summary: String,
    val startedAt: String?,
    val finishedAt: String?
) {
    val errorCode: String? get() = Regex("^\\[([A-Z][A-Z0-9_]+)]").find(summary)?.groupValues?.get(1)
}

internal fun parseDevelopmentChangeValidationEvidence(json: JSONObject): DevelopmentChangeValidationEvidence {
    val sessionId = json.getLong("workSessionId")
    val fingerprint = json.evidenceNullableString("sourceFingerprintSha256")
    require(fingerprint == null || fingerprint.matches(Regex("[0-9a-f]{64}")))
    val items = json.getJSONArray("operations")
    require(items.length() <= 4)
    val operations = List(items.length()) { parseValidationAttempt(items.getJSONObject(it), sessionId) }
    require(operations.map { it.operation }.distinct().size == operations.size)
    require(operations.all { it.sourceTreeFingerprintSha256 == fingerprint })
    val passed = json.getInt("passedOperations")
    val required = json.getInt("requiredOperations")
    require(required == 4 && passed in 0..required)
    require(operations.count { it.status == "SUCCEEDED" } == passed)
    return DevelopmentChangeValidationEvidence(
        changeKey = UUID.fromString(json.getString("changeKey")),
        workSessionId = sessionId,
        sourceRevision = json.getLong("sourceRevision").also { require(it >= 0) },
        sourceFingerprintSha256 = fingerprint,
        validationState = json.getString("validationState").also {
            require(it in setOf("NOT_STARTED", "CURRENT", "STALE", "BLOCKED"))
        },
        passedOperations = passed,
        requiredOperations = required,
        operations = operations,
        lastAttempt = json.optJSONObject("lastAttempt")?.let { parseValidationAttempt(it, sessionId) }
    )
}

private fun parseValidationAttempt(json: JSONObject, expectedSessionId: Long): DevelopmentChangeValidationAttempt =
    DevelopmentChangeValidationAttempt(
        id = UUID.fromString(json.getString("id")),
        workSessionId = json.getLong("workSessionId").also { require(it == expectedSessionId) },
        operation = json.getString("operation").also {
            require(it in setOf("BACKEND_TEST", "WEB_BUILD", "ANDROID_BUILD", "PLAYWRIGHT_ACCEPTANCE"))
        },
        status = json.getString("status").also {
            require(it in setOf("RUNNING", "SUCCEEDED", "FAILED", "BLOCKED"))
        },
        sourceTreeFingerprintSha256 = json.getString("sourceTreeFingerprintSha256").also {
            require(it.matches(Regex("[0-9a-f]{64}")))
        },
        definitionRevision = json.getString("definitionRevision"),
        exitCode = if (json.isNull("exitCode")) null else json.getInt("exitCode"),
        summary = json.optString("summary").take(500),
        startedAt = json.evidenceNullableString("startedAt"),
        finishedAt = json.evidenceNullableString("finishedAt")
    )

private fun JSONObject.evidenceNullableString(key: String): String? = if (isNull(key)) null else getString(key)
