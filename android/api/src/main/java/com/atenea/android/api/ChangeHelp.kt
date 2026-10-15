package com.atenea.android.api

import org.json.JSONObject
import java.util.UUID

enum class ChangeHelpDestination { CHANGE, ACTIVITY, CHAT, DELIVERY, APP_UPDATES }

data class ChangeHelpNavigation(val destination: ChangeHelpDestination, val label: String, val reason: String)

data class ChangeHelp(
    val workSessionId: Long,
    val changeKey: UUID,
    val sourceRevision: Long,
    val observedAt: String,
    val stage: String,
    val answer: String,
    val simpleAnswer: String,
    val facts: List<String>,
    val nextAction: ChangeHelpNavigation,
    val suggestedQuestions: List<String>,
    val explanationSource: String
)

internal fun parseChangeHelp(json: JSONObject, sessionId: Long): ChangeHelp {
    require(json.getString("protocolVersion") == "change-help/v1")
    require(json.getLong("workSessionId") == sessionId)
    require(json.getLong("sourceRevision") >= 0)
    val navigation = json.getJSONObject("nextAction")
    fun strings(key: String, max: Int): List<String> {
        val values = json.getJSONArray(key)
        require(values.length() <= max)
        return List(values.length()) { values.getString(it) }
    }
    return ChangeHelp(
        sessionId, UUID.fromString(json.getString("changeKey")), json.getLong("sourceRevision"),
        json.getString("observedAt"), json.getString("stage"), json.getString("answer"),
        json.getString("simpleAnswer"), strings("facts", 20),
        ChangeHelpNavigation(ChangeHelpDestination.valueOf(navigation.getString("destination")),
            navigation.getString("label"), navigation.getString("reason")),
        strings("suggestedQuestions", 5), json.getString("explanationSource")
    )
}
