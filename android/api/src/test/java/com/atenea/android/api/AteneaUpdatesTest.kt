package com.atenea.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class AteneaUpdatesTest {
    private fun payload()=JSONObject().put("protocolVersion","atenea-updates/v1").put("observedAt","2026-10-15T12:00:00Z")
        .put("historyState","CURRENT").put("backend",JSONObject().put("sourceCommit","8".repeat(40)).put("healthy",true))
        .put("android",JSONObject().put("sourceCommit","8".repeat(40)).put("versionCode",1000042).put("versionName","0.6.42")
            .put("apkSha256","a".repeat(64)).put("observedAt",100L))
        .put("platform",JSONObject.NULL).put("workerHealthy",true).put("unavailable",JSONArray()).put("nextPage",JSONObject.NULL)
        .put("entries",JSONArray().put(JSONObject().put("id","app:71").put("title","Mejora de conversación").put("summary","Imágenes en la misma conversación")
            .put("integratedAt","2026-10-15T11:00:00Z").put("component","APP").put("mergeCommit","7".repeat(40))
            .put("requiredTargets",JSONArray().put("ANDROID_STABLE")).put("publications",JSONObject().put("ANDROID_STABLE","NOT_INCLUDED"))
            .put("pullRequestUrl","https://github.com/jlnieto/atenea/pull/71")))

    @Test fun `global history is authenticated GET independent of any WorkSession`() {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(payload().toString()))
            val client=AteneaApiClient(server.url("/").toString().trimEnd('/'),{"synthetic-token"})
            val value=runBlocking { client.fetchAteneaUpdates(1) }
            assertEquals("NOT_INCLUDED",value.entries.single().publications[MobileDeliveryTarget.ANDROID_STABLE])
            assertEquals(1000042,value.android?.versionCode)
            val request=server.takeRequest();assertEquals("GET",request.method);assertEquals("/api/mobile/atenea/updates?page=1",request.path)
            assertEquals("Bearer synthetic-token",request.getHeader("Authorization"));assertEquals(0L,request.body.size)
        } finally { server.shutdown() }
    }
    @Test fun `unknown statuses hosts hashes and unreachable pages are rejected`() {
        for(invalid in listOf(payload().put("nextPage",51),payload().put("historyState","PUBLISHED"),
            payload().put("backend",JSONObject().put("sourceCommit","/srv/foreign").put("healthy",true)),
            payload().also { it.getJSONArray("entries").getJSONObject(0).put("pullRequestUrl","https://foreign.invalid/pull/71") },
            payload().also { it.getJSONArray("entries").getJSONObject(0).getJSONObject("publications").put("ANDROID_STABLE","SUCCESS") })) {
            assertFails { parseAteneaUpdates(invalid) }
        }
    }
    @Test fun `missing component evidence remains unavailable not a backend success`() {
        val value=parseAteneaUpdates(payload().put("android",JSONObject.NULL));assertNull(value.android);assertEquals(true,value.backend?.healthy)
        assertEquals("NOT_INCLUDED",value.entries.single().publications[MobileDeliveryTarget.ANDROID_STABLE])
    }
}
