package com.atenea.android.api

import org.json.JSONObject

data class AteneaProductEntry(val id: String, val title: String, val summary: String, val integratedAt: String,
    val component: String, val requiredTargets: List<MobileDeliveryTarget>?, val publications: Map<MobileDeliveryTarget,String>,
    val pullRequestUrl: String, val mergeCommit: String)
data class AteneaInstalledComponent(val sourceCommit: String, val healthy: Boolean?)
data class AteneaProductUpdates(val observedAt: String, val historyState: String, val entries: List<AteneaProductEntry>,
    val backend: AteneaInstalledComponent?, val android: MobileAndroidChannel?, val platform: AteneaInstalledComponent?,
    val workerHealthy: Boolean?, val nextPage: Int?, val unavailable: List<String>)

internal fun parseAteneaUpdates(json: JSONObject): AteneaProductUpdates {
    require(json.getString("protocolVersion")=="atenea-updates/v1")
    val history=json.getString("historyState");require(history in setOf("CURRENT","CACHED","UNAVAILABLE"))
    fun sha(value: String,size: Int): String = value.also { require(it.matches(Regex("[0-9a-f]{$size}"))) }
    fun component(key: String)=json.optJSONObject(key)?.let { value ->
        AteneaInstalledComponent(sha(value.getString("sourceCommit"),40),if(value.has("healthy"))value.getBoolean("healthy")else null)
    }
    val channel=json.optJSONObject("android")?.let { value ->
        require(value.getInt("versionCode")>0)
        MobileAndroidChannel(sha(value.getString("sourceCommit"),40),value.getString("versionName"),value.getInt("versionCode"),
            sha(value.getString("apkSha256"),64),value.getLong("observedAt"))
    }
    val values=json.getJSONArray("entries");require(values.length()<=10)
    val entries=List(values.length()) { index ->
        val value=values.getJSONObject(index);val id=value.getString("id");require(id.matches(Regex("(app|platform):[1-9][0-9]*")))
        val targets=value.optJSONArray("requiredTargets")?.let { targets -> List(targets.length()) { MobileDeliveryTarget.valueOf(targets.getString(it)) } }
        val statuses=value.getJSONObject("publications");val publications=statuses.keys().asSequence().associate { key ->
            val status=statuses.getString(key);require(status in setOf("PUBLISHED","NOT_INCLUDED","UNAVAILABLE","PUBLISHED_NEEDS_ATTENTION"))
            MobileDeliveryTarget.valueOf(key) to status
        }
        val url=value.getString("pullRequestUrl");require(url.matches(Regex("https://github\\.com/jlnieto/(atenea|atenea-remote-worker-spec)/pull/[1-9][0-9]*")))
        val title=value.getString("title");val summary=value.getString("summary");require(title.length<=200 && summary.length<=1000)
        AteneaProductEntry(id,title,summary,value.getString("integratedAt"),value.getString("component"),targets,publications,url,sha(value.getString("mergeCommit"),40))
    }
    require(entries.map { it.id }.distinct().size==entries.size)
    val next=if(json.isNull("nextPage"))null else json.getInt("nextPage").also { require(it in 2..50) }
    val missing=json.getJSONArray("unavailable");require(missing.length()<=5)
    return AteneaProductUpdates(json.getString("observedAt"),history,entries,component("backend"),channel,component("platform"),
        if(json.isNull("workerHealthy"))null else json.getBoolean("workerHealthy"),next,List(missing.length()) { missing.getString(it) })
}
