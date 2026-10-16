package com.atenea.android.api

import org.json.JSONObject
import java.util.UUID

data class ValidationRecoveryOperation(val id: UUID, val kind: String, val state: String,
    val sourceRevision: Long, val runId: Long?, val errorCode: String?) {
    val working: Boolean get() = state in setOf("PREPARE_REQUESTED", "PREPARING", "APPLY_REQUESTED", "APPLYING")
}
data class ValidationRecovery(val workSessionId: Long, val changeKey: UUID, val sourceRevision: Long,
    val failureCode: String?, val canPrepareRepair: Boolean, val canInvestigate: Boolean,
    val repair: ValidationRecoveryOperation?, val investigation: ValidationRecoveryOperation?)

internal fun parseValidationRecoveryOperation(value: JSONObject): ValidationRecoveryOperation {
    require(value.getString("protocolVersion") == "validation-recovery/v1")
    val kind = value.getString("kind"); val state = value.getString("state")
    require(kind in setOf("REPAIR", "INVESTIGATION"))
    require(state in setOf("PREPARE_REQUESTED", "PREPARING", "READY", "APPLY_REQUESTED", "APPLYING", "APPLIED",
        "ATTENTION", "CANCELLED", "INVESTIGATING", "INVESTIGATED"))
    require(value.getLong("sourceRevision") >= 0)
    return ValidationRecoveryOperation(UUID.fromString(value.getString("id")), kind, state,
        value.getLong("sourceRevision"), if (value.isNull("runId")) null else value.getLong("runId"),
        if (value.isNull("errorCode")) null else value.getString("errorCode"))
}
internal fun parseValidationRecovery(value: JSONObject, sessionId: Long): ValidationRecovery {
    require(value.getString("protocolVersion") == "validation-recovery/v1")
    require(value.getLong("workSessionId") == sessionId && value.getLong("sourceRevision") >= 0)
    return ValidationRecovery(sessionId, UUID.fromString(value.getString("changeKey")), value.getLong("sourceRevision"),
        if (value.isNull("failureCode")) null else value.getString("failureCode"), value.getBoolean("canPrepareRepair"),
        value.getBoolean("canInvestigate"), value.optJSONObject("repair")?.let(::parseValidationRecoveryOperation),
        value.optJSONObject("investigation")?.let(::parseValidationRecoveryOperation))
}
