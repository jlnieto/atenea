package com.atenea.android.api

import org.json.JSONObject
import java.util.UUID

data class MobileProgressCheck(val label: String, val state: String, val startedAt: Long?, val finishedAt: Long?)
data class MobileGitHubProgress(val sourceCommit: String, val state: String, val observedAt: Long,
    val serviceState: String, val checks: List<MobileProgressCheck>)
data class MobileOperationProgress(val operationId: UUID, val sessionId: Long, val kind: String, val state: String,
    val startedAt: Long, val updatedAt: Long, val observedAt: Long, val github: MobileGitHubProgress?)

internal fun parseOperationProgress(json: JSONObject): MobileOperationProgress {
    fun time(value: JSONObject, key: String): Long = value.getLong(key).also { require(it > 0) }
    fun optionalTime(value: JSONObject, key: String): Long? = if (value.isNull(key)) null else time(value,key)
    val id=UUID.fromString(json.getString("operationId"));require(id.toString()==json.getString("operationId"))
    val github=json.optJSONObject("github")?.let { value ->
        val source=value.getString("sourceCommit");require(source.matches(Regex("[0-9a-f]{40}")))
        val state=value.getString("state");require(state in setOf("NOT_STARTED","QUEUED","RUNNING","CLOSING","PASSED","FAILED","UNAVAILABLE","BASE_CHANGED"))
        val service=value.getString("serviceState");require(service in setOf("OPERATIONAL","DEGRADED","UNKNOWN"))
        val checks=value.getJSONArray("checks");require(checks.length()<=200)
        MobileGitHubProgress(source,state,time(value,"observedAt"),service,List(checks.length()) { index ->
            val check=checks.getJSONObject(index)
            val label=check.getString("label");require(label in setOf("Validación del cambio","Construcción de la versión",
                "Backend combinado con main","Construcción y pruebas backend","Construcción y pruebas Android"))
            val status=check.getString("state");require(status in setOf("NOT_STARTED","QUEUED","RUNNING","PASSED","FAILED","UNAVAILABLE","BASE_CHANGED"))
            val start=optionalTime(check,"startedAt");val end=optionalTime(check,"finishedAt")
            // Provider timestamps can be inconsistent during an outage; do not invent a duration.
            MobileProgressCheck(label,status,start,end?.takeIf { start==null || it>=start })
        })
    }
    return MobileOperationProgress(id,json.getLong("sessionId").also { require(it>0) },json.getString("kind"),
        json.getString("state"),time(json,"startedAt"),time(json,"updatedAt"),time(json,"observedAt"),github)
}
