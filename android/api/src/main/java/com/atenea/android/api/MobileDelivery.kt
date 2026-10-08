package com.atenea.android.api

import org.json.JSONObject
import java.util.UUID

enum class MobileDeliveryTarget(val label: String) {
    APP_PROD("Backend PROD"), AX42_PLATFORM("Worker AX42"), ANDROID_STABLE("Android estable")
}
data class MobileDeliveryState(val enabled: Boolean, val operations: List<MobileDeliveryOperation>,
    val integration: MobileDeliveryIntegration? = null)

data class MobileDeliveryIntegration(
    val sessionId: Long,
    val sourceCommit: String?,
    val mergeState: String,
    val canRequestIntegration: Boolean,
    val errorCode: String?
) {
    val allowsRequest: Boolean get() = canRequestIntegration && mergeState == "MERGEABLE"
        && sourceCommit?.matches(Regex("[0-9a-f]{40}")) == true && errorCode == null
}

internal fun parseMobileDeliveryState(json: JSONObject): MobileDeliveryState {
    val items = json.getJSONArray("operations")
    val integration = json.optJSONObject("integration")?.let { value ->
        MobileDeliveryIntegration(value.getLong("sessionId"), value.nullableDeliveryString("sourceCommit"),
            value.getString("mergeState"), value.opt("canRequestIntegration") == true,
            value.nullableDeliveryString("errorCode"))
    }
    return MobileDeliveryState(json.getBoolean("enabled"),
        List(items.length()) { parseMobileDeliveryOperation(items.getJSONObject(it)) }, integration)
}

data class MobileDeliveryOperation(
    val id: UUID,
    val operationId: UUID,
    val sessionId: Long,
    val kind: String,
    val target: MobileDeliveryTarget,
    val sourceCommit: String?,
    val state: String,
    val planSha256: String?,
    val errorCode: String?,
    val versionCode: Long?,
    val versionName: String?,
    val expiresAt: Long?,
    val pullRequestUrl: String?,
    val mergeCommit: String?,
    val effectiveSourceCommit: String?,
    val resultSha256: String?
) {
    val terminal: Boolean get() = state in setOf("SUCCEEDED", "ROLLED_BACK", "FAILED", "BLOCKED", "ROLLBACK_FAILED")
    fun canConfirm(nowSeconds: Long): Boolean = kind == "RELEASE" && state == "READY"
        && planSha256?.matches(Regex("[0-9a-f]{64}")) == true && (expiresAt ?: 0) > nowSeconds
}

internal fun parseMobileDeliveryOperation(json: JSONObject): MobileDeliveryOperation = MobileDeliveryOperation(
    id = UUID.fromString(json.getString("id")),
    operationId = UUID.fromString(json.getString("operationId")),
    sessionId = json.getLong("sessionId"),
    kind = json.getString("kind"),
    target = MobileDeliveryTarget.valueOf(json.getString("target")),
    sourceCommit = json.nullableDeliveryString("sourceCommit"),
    state = json.getString("state"),
    planSha256 = json.nullableDeliveryString("planSha256"),
    errorCode = json.nullableDeliveryString("errorCode"),
    versionCode = if (json.isNull("versionCode")) null else json.getLong("versionCode"),
    versionName = json.nullableDeliveryString("versionName"),
    expiresAt = if (json.isNull("expiresAt")) null else json.getLong("expiresAt"),
    pullRequestUrl = json.nullableDeliveryString("pullRequestUrl"),
    mergeCommit = json.nullableDeliveryString("mergeCommit"),
    effectiveSourceCommit = json.nullableDeliveryString("effectiveSourceCommit"),
    resultSha256 = json.nullableDeliveryString("resultSha256")
)
private fun JSONObject.nullableDeliveryString(key: String): String? = if (isNull(key)) null else getString(key)
