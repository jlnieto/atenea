package com.atenea.android.api

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import kotlin.test.*

class MobileOperationProgressTest {
    private fun evidence(): JSONObject = JSONObject().put("sourceCommit","a".repeat(40)).put("state","CLOSING")
        .put("observedAt",1000).put("serviceState","DEGRADED").put("checks",JSONArray().put(JSONObject()
            .put("label","Validación del cambio").put("state","PASSED").put("startedAt",700).put("finishedAt",900)))
    private fun root(): JSONObject = JSONObject().put("operationId",UUID.randomUUID().toString()).put("sessionId",28)
        .put("kind","PUBLISH_PR").put("state","WAITING_CI").put("startedAt",600).put("updatedAt",999).put("observedAt",1000)
        .put("github",evidence())
    @Test fun `actual closing is not confused with tests running or failure`() {
        val parsed=parseOperationProgress(root())
        assertEquals("CLOSING",parsed.github?.state);assertEquals("DEGRADED",parsed.github?.serviceState)
        assertEquals(1,parsed.github?.checks?.size);assertEquals("PASSED",parsed.github?.checks?.first()?.state)
        assertEquals(28,parsed.sessionId)
    }
    @Test fun `unexpected provider text state source and labels are rejected`() {
        for (bad in listOf(evidence().put("sourceCommit","main"),evidence().put("state","DEPLOYED"),
            evidence().put("serviceState","whatever"),evidence().put("checks",JSONArray().put(JSONObject()
                .put("label","/etc/secret").put("state","PASSED").put("startedAt",700).put("finishedAt",900))))) {
            assertFails { parseOperationProgress(root().put("github",bad)) }
        }
    }
    @Test fun `previous main is a closed distinct state not a running or successful check`() {
        val value=root()
        value.getJSONObject("github").put("state","BASE_CHANGED").getJSONArray("checks")
            .getJSONObject(0).put("label","Backend combinado con main").put("state","BASE_CHANGED")
        val parsed=parseOperationProgress(value)
        assertEquals("BASE_CHANGED",parsed.github?.state)
        assertEquals("BASE_CHANGED",parsed.github?.checks?.first()?.state)
    }
    @Test fun `old backend does not invent progress and invalid timestamps do not invent durations`() {
        assertNull(parseMobileDeliveryState(JSONObject("{\"enabled\":true,\"operations\":[]}")).progress)
        val value=root();value.getJSONObject("github").getJSONArray("checks").getJSONObject(0).put("finishedAt",600)
        assertNull(parseOperationProgress(value).github?.checks?.first()?.finishedAt)
    }
    private fun storage(): JSONObject=JSONObject().put("sourceCommit","a".repeat(40)).put("target","APP_PROD")
        .put("phase","RECLAIMING").put("observedAt",1000).put("freeBytes",10L).put("requiredBytes",20L)
        .put("missingBytes",10L).put("reclaimedBytes",0L)
    @Test fun `storage is informational and old backends remain compatible`() {
        assertNull(parseOperationProgress(root()).storage)
        val value=parseOperationProgress(root().put("kind","RELEASE").put("storage",storage()))
        assertEquals("RECLAIMING",value.storage?.phase);assertEquals(10L,value.storage?.missingBytes)
        for(bad in listOf(storage().put("path","/secret"),storage().put("target","AX42_PLATFORM"),
            storage().put("phase","DELETE_ANYTHING"),storage().put("freeBytes",-1),storage().put("missingBytes",0),
            storage().put("requiredBytes","20"),storage().put("reclaimedBytes",2.5))) {
            assertFails { parseOperationProgress(root().put("kind","RELEASE").put("storage",bad)) }
        }
    }
}
