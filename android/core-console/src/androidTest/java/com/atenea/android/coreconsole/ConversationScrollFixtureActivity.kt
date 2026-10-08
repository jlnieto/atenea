package com.atenea.android.coreconsole

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atenea.android.api.CodexTurnExecutionProfile
import com.atenea.android.api.MobileConversationTurn
import org.json.JSONArray

/** Test-only transcript: no API client, credentials, session or AgentRun. */
class ConversationScrollFixtureActivity : ComponentActivity() {
    var turns by mutableStateOf(emptyList<MobileConversationTurn>())
    var draft by mutableStateOf("Borrador intacto")
    var sends = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Preserve only the reported transcript's shape; all words/numbers and identities are anonymized.
        val fixture = JSONArray(assets.open("conversation-scroll-shape.json").bufferedReader().use { it.readText() })
        turns = List(fixture.length()) { index ->
            val turn = fixture.getJSONObject(index)
            MobileConversationTurn(turn.getLong("id"), turn.getString("actor"), turn.getString("messageText"),
                turn.getString("createdAt"), CodexTurnExecutionProfile(1, "test-model", "TEST", "medium", "TEST", "test-version"))
        }
        setContent {
            ConversationSurface(
                title = "Prueba local de conversación", status = "Abierta",
                turns = turns, input = draft, pending = false, placeholder = "Instrucción",
                onInputChange = { draft = it }, onSend = { sends++ }, onBack = {},
                onOpenCore = {}, onRefresh = {}, error = null, surfaceKey = "real-scroll-fixture"
            )
        }
    }
}
