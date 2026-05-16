package com.atenea.android.voiceruntime

internal object RealtimeSdpNormalizer {
    fun normalizeAnswerBody(rawBody: String): String {
        val trimmed = rawBody.trim()
        val extracted = if (trimmed.startsWith("{")) {
            extractJsonStringField(trimmed, "sdp") ?: trimmed
        } else {
            trimmed
        }.trim()
        if (!extracted.lineSequence().firstOrNull().orEmpty().startsWith("v=0")) {
            throw IllegalStateException("OpenAI Realtime no devolvio una SDP answer valida")
        }
        return extracted
    }

    private fun extractJsonStringField(json: String, field: String): String? {
        val match = Regex(""""$field"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json)
            ?: return null
        return match.groupValues[1]
            .replace("\\r", "\r")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .takeIf { it.isNotBlank() }
    }

    fun normalizeRemoteAnswerForAndroid(sdp: String): NormalizedSdp {
        var normalized = sdp
        val changes = mutableListOf<String>()

        val msidSemanticWithoutSpace = Regex("(?m)^a=msid-semantic:WMS(\\s|$)")
        if (msidSemanticWithoutSpace.containsMatchIn(normalized)) {
            normalized = normalized.replace(msidSemanticWithoutSpace, "a=msid-semantic: WMS$1")
            changes += "msid-semantic-space"
        }

        val firstMediaStreamId = Regex("(?m)^a=msid:([^\\s]+)\\s+[^\\s]+").find(normalized)
            ?.groupValues
            ?.getOrNull(1)
        if (!firstMediaStreamId.isNullOrBlank() && Regex("(?m)^a=msid-semantic: WMS \\*$").containsMatchIn(normalized)) {
            normalized = normalized.replace(
                Regex("(?m)^a=msid-semantic: WMS \\*$"),
                "a=msid-semantic: WMS $firstMediaStreamId"
            )
            changes += "msid-semantic-stream-id"
        }

        val crlfNormalized = normalized
            .lineSequence()
            .joinToString("\r\n")
            .let { "$it\r\n" }
        if (crlfNormalized != normalized) {
            normalized = crlfNormalized
            changes += "crlf-trailing-newline"
        }

        return NormalizedSdp(normalized, changes)
    }
}

internal data class NormalizedSdp(
    val sdp: String,
    val changes: List<String>
) {
    val changed: Boolean = changes.isNotEmpty()
}
