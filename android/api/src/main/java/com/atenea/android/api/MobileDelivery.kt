package com.atenea.android.api

import org.json.JSONObject
import java.util.UUID

enum class MobileDeliveryTarget(val label: String) {
    APP_PROD("Backend PROD"), AX42_PLATFORM("Worker AX42"), ANDROID_STABLE("Android estable")
}
data class MobileDeliveryState(val enabled: Boolean, val operations: List<MobileDeliveryOperation>,
    val integration: MobileDeliveryIntegration? = null,
    val sourceUpdateEnabled: Boolean = false, val sourceUpdate: MobileSourceUpdate? = null,
    val releaseRecoveryEnabled: Boolean = false,
    val deployment: MobileDeploymentObservation? = null,
    val completionEnabled: Boolean = false,
    val readiness: MobileChangeDeliveryReadiness? = null,
    val validation: DevelopmentChangeValidationEvidence? = null)

data class MobileAndroidChannel(val sourceCommit: String, val versionName: String, val versionCode: Int,
    val apkSha256: String, val observedAt: Long)

data class MobileChangeDeliveryReadiness(val sessionId: Long, val requirementsStatus: String,
    val headCommit: String?, val integratedMergeCommit: String?, val requiredTargets: List<MobileDeliveryTarget>,
    val ready: Boolean, val android: MobileAndroidChannel?, val androidStatus: String, val observedAt: Long) {
    fun fresh(nowSeconds: Long): Boolean = observedAt in (nowSeconds - 60)..(nowSeconds + 5)
}

internal fun parseDeliveryReadiness(json: JSONObject): MobileChangeDeliveryReadiness {
    val status=json.getString("requirementsStatus")
    require(status in setOf("CONFIRMED","NOT_INTEGRATED","UNAVAILABLE"))
    val targets=json.getJSONArray("requiredTargets").let { values ->
        List(values.length()) { MobileDeliveryTarget.valueOf(values.getString(it)) }.also {
            require(it.size==it.distinct().size && it.none { target -> target==MobileDeliveryTarget.AX42_PLATFORM })
        }
    }
    val channel=json.optJSONObject("android")?.let { value ->
        require(value.getString("sourceCommit").matches(Regex("[0-9a-f]{40}")))
        for (key in listOf("apkSha256","certificateSha256","receiptSha256")) require(value.getString(key).matches(Regex("[0-9a-f]{64}")))
        for (key in listOf("planId","operationId")) require(UUID.fromString(value.getString(key)).toString()==value.getString(key))
        require(value.getInt("versionCode")>0 && value.getString("versionName").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        MobileAndroidChannel(value.getString("sourceCommit"),value.getString("versionName"),value.getInt("versionCode"),
            value.getString("apkSha256"),value.getLong("observedAt"))
    }
    val androidStatus=json.getString("androidStatus")
    require(androidStatus in setOf("INCLUDED","NOT_INCLUDED","UNAVAILABLE","NOT_INTEGRATED"))
    val ready=json.getBoolean("ready")
    require(!ready || status=="CONFIRMED" && (!targets.contains(MobileDeliveryTarget.ANDROID_STABLE) || channel!=null && androidStatus=="INCLUDED"))
    return MobileChangeDeliveryReadiness(json.getLong("sessionId"),status,json.nullableDeliveryString("headCommit"),
        json.nullableDeliveryString("integratedMergeCommit"),targets,ready,channel,androidStatus,json.getLong("observedAt"))
}

data class MobileDeploymentObservation(val sessionId: Long, val status: String, val origin: String?,
    val sourceCommit: String?, val integratedMergeCommit: String?, val healthy: Boolean,
    val observedAt: Long?, val planId: UUID?, val operationId: UUID?, val receiptSha256: String?, val errorCode: String?) {
    fun isDeployed(nowSeconds: Long): Boolean = status == "DEPLOYED" && healthy
        && observedAt?.let { it in (nowSeconds - 60)..(nowSeconds + 5) } == true
}

internal fun parseDeploymentObservation(json: JSONObject): MobileDeploymentObservation {
    require(json.keys().asSequence().toSet() == setOf("sessionId", "status", "origin", "sourceCommit",
        "integratedMergeCommit", "healthy", "observedAt", "planId", "operationId", "receiptSha256", "errorCode"))
    val session = json.get("sessionId")
    require(session is Long || session is Int)
    require((session as Number).toLong() > 0)
    val status = json.getString("status")
    require(status in setOf("DEPLOYED", "UNHEALTHY", "NOT_INCLUDED", "UNAVAILABLE", "NOT_INTEGRATED"))
    require(json.get("healthy") is Boolean)
    fun sha(key: String, size: Int): String? = json.nullableDeliveryString(key)?.also {
        require(it.matches(Regex("[0-9a-f]{$size}")))
    }
    fun id(key: String): UUID? = json.nullableDeliveryString(key)?.let {
        UUID.fromString(it).also { value -> require(value.toString() == it) }
    }
    val time = if (json.isNull("observedAt")) null else {
        val value = json.get("observedAt")
        require(value is Int || value is Long)
        (value as Number).toLong().also { require(it > 0) }
    }
    val result = MobileDeploymentObservation(session.toLong(), status, json.nullableDeliveryString("origin"),
        sha("sourceCommit",40), sha("integratedMergeCommit",40), json.getBoolean("healthy"), time,
        id("planId"), id("operationId"), sha("receiptSha256",64), json.nullableDeliveryString("errorCode"))
    require(result.errorCode?.matches(Regex("[A-Z][A-Z0-9_]{2,79}")) != false)
    if (status in setOf("DEPLOYED", "UNHEALTHY")) {
        require(result.origin in setOf("MOBILE", "OPERATOR") && result.sourceCommit != null
            && result.integratedMergeCommit != null && result.observedAt != null
            && result.planId != null && result.operationId != null && result.receiptSha256 != null
            && result.errorCode == null && result.healthy == (status == "DEPLOYED"))
    } else {
        require(!result.healthy && result.origin == null && result.sourceCommit == null
            && result.integratedMergeCommit == null && result.observedAt == null
            && result.planId == null && result.operationId == null && result.receiptSha256 == null)
    }
    return result
}

data class MobileSourceUpdate(val id: UUID, val sessionId: Long, val state: String,
    val targetMainCommit: String, val sourceRevision: Long?, val resolverRunId: Long?, val errorCode: String?,
    val recoveryAvailable: Boolean = false, val baseStatus: String = "UNAVAILABLE",
    val updatedAt: String? = null)

internal fun parseMobileSourceUpdate(json: JSONObject): MobileSourceUpdate {
    val state = json.getString("state")
    require(state in setOf("QUEUED", "PREPARE_CLAIMED", "UNCERTAIN", "ATTENTION", "READY_TO_RESOLVE",
        "RESOLVING", "RESOLVER_COMPLETED", "READY_TO_FINALIZE", "FAILED", "BLOCKED", "PUBLISHED", "RETRY_REQUESTED"))
    val main = json.getString("targetMainCommit")
    require(main.matches(Regex("[0-9a-f]{40}")))
    fun positive(key: String): Long? = if (json.isNull(key)) null else {
        val value = json.get(key)
        require(value is Long || value is Int)
        (value as Number).toLong().also { require(it > 0) }
    }
    val id = UUID.fromString(json.getString("id"))
    require(id.toString() == json.getString("id"))
    val baseStatus = if (json.has("baseStatus")) json.getString("baseStatus") else "UNAVAILABLE"
    require(baseStatus in setOf("NOT_REQUIRED", "CURRENT", "UPDATE_REQUIRED", "UNAVAILABLE"))
    return MobileSourceUpdate(id, positive("sessionId")!!,
        state, main, positive("sourceRevision"), positive("resolverRunId"), json.nullableDeliveryString("errorCode"),
        json.opt("recoveryAvailable") == true, baseStatus, json.nullableDeliveryString("updatedAt"))
}

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
    require(!json.has("sourceUpdate") || json.isNull("sourceUpdate") || json.opt("sourceUpdate") is JSONObject)
    require(!json.has("deployment") || json.isNull("deployment") || json.opt("deployment") is JSONObject)
    require(!json.has("validation") || json.isNull("validation") || json.opt("validation") is JSONObject)
    return MobileDeliveryState(json.getBoolean("enabled"),
        List(items.length()) { parseMobileDeliveryOperation(items.getJSONObject(it)) }, integration,
        json.opt("sourceUpdateEnabled") == true, json.optJSONObject("sourceUpdate")?.let(::parseMobileSourceUpdate),
        json.opt("releaseRecoveryEnabled") == true,
        json.optJSONObject("deployment")?.let(::parseDeploymentObservation),
        json.opt("completionEnabled") == true,
        json.optJSONObject("readiness")?.let(::parseDeliveryReadiness),
        json.optJSONObject("validation")?.let(::parseDevelopmentChangeValidationEvidence))
}

data class MobileReleaseRecovery(val integrationOperationId: UUID, val publishedHeadCommit: String,
    val integratedMergeCommit: String, val selectedMainCommit: String)

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
    val resultSha256: String?,
    val releaseRecovery: MobileReleaseRecovery? = null,
    val updatedAt: String? = null
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
    resultSha256 = json.nullableDeliveryString("resultSha256"),
    releaseRecovery = parseReleaseRecovery(json),
    updatedAt = json.nullableDeliveryString("updatedAt")
)

private fun parseReleaseRecovery(operation: JSONObject): MobileReleaseRecovery? {
    if (!operation.has("releaseRecovery") || operation.isNull("releaseRecovery")) return null
    require(operation.opt("releaseRecovery") is JSONObject)
    val value = operation.getJSONObject("releaseRecovery")
    require(value.keys().asSequence().toSet() == setOf("protocol", "integrationOperationId",
        "publishedHeadCommit", "integratedMergeCommit", "selectedMainCommit"))
    require(value.getString("protocol") == "integrated-release-recovery/v1")
    require(operation.getString("kind") == "RELEASE" && operation.getString("target") in setOf("APP_PROD","ANDROID_STABLE"))
    fun sha(key: String) = value.getString(key).also { require(it.matches(Regex("[0-9a-f]{40}"))) }
    val selected = sha("selectedMainCommit")
    require(selected == operation.getString("sourceCommit"))
    val id = UUID.fromString(value.getString("integrationOperationId"))
    require(id.toString() == value.getString("integrationOperationId"))
    return MobileReleaseRecovery(id, sha("publishedHeadCommit"), sha("integratedMergeCommit"), selected)
}
private fun JSONObject.nullableDeliveryString(key: String): String? = if (isNull(key)) null else getString(key)
