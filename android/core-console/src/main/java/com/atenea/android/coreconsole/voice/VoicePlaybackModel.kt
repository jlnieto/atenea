package com.atenea.android.coreconsole.voice

internal data class VoicePlaybackResume(
    val segments: List<String>,
    val index: Int,
    val sourceType: String?,
    val sourceId: String?
)

internal object VoiceSegmenter {
    private const val TARGET_CHARS = 520
    private const val HARD_LIMIT_CHARS = 760

    fun segment(message: String?): List<String> {
        val normalized = normalizeForAudio(message)
        if (normalized.isBlank()) {
            return emptyList()
        }

        val units = splitIntoUnits(normalized)
        if (units.isEmpty()) {
            return listOf(normalized)
        }

        val result = mutableListOf<String>()
        var current = StringBuilder()
        units.forEach { unit ->
            val wouldExceedTarget = current.isNotEmpty() && current.length + unit.length + 1 > TARGET_CHARS
            val shouldFlush = wouldExceedTarget && (current.length >= TARGET_CHARS / 2 || unit.length > TARGET_CHARS)
            if (shouldFlush) {
                result += current.toString().trim()
                current = StringBuilder()
            }
            if (unit.length > HARD_LIMIT_CHARS) {
                if (current.isNotEmpty()) {
                    result += current.toString().trim()
                    current = StringBuilder()
                }
                result += splitLongUnit(unit)
                return@forEach
            }
            if (current.isNotEmpty()) {
                current.append(' ')
            }
            current.append(unit)
        }
        if (current.isNotEmpty()) {
            result += current.toString().trim()
        }
        return result.filter { it.isNotBlank() }
    }

    fun listeningText(message: String?): String {
        val text = message?.trim().orEmpty()
        if (text.isBlank()) {
            return ""
        }
        return text
            .replace(Regex("(?s)```.*?```"), " Hay un bloque tecnico disponible en pantalla. ")
            .replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)")) { match ->
                match.groupValues[1].ifBlank { "enlace disponible" }
            }
            .replace(Regex("(?m)^\\s*(sudo\\s+|curl\\s+|wget\\s+|docker\\s+|git\\s+|npm\\s+|yarn\\s+|pnpm\\s+|./|\\.\\/|mvn\\s+|gradle\\s+|kubectl\\s+|systemctl\\s+).*$"), " Hay un comando tecnico disponible en pantalla. ")
            .replace(Regex("https?://\\S+"), " enlace disponible ")
            .replace(Regex("`([^`]+)`")) { match ->
                val value = match.groupValues[1]
                when {
                    value.contains("/") || value.contains("\\") -> " ruta tecnica disponible en pantalla "
                    value.length > 36 -> " valor tecnico disponible "
                    else -> value
                }
            }
            .replace(Regex("(?m)^\\s*(/[^\\s]+|[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+)\\s*$"), " ruta tecnica disponible en pantalla ")
            .replace(Regex("(Hay un comando tecnico disponible en pantalla\\.\\s*){2,}"), "Hay comandos tecnicos disponibles en pantalla. ")
            .replace(Regex("(Hay un bloque tecnico disponible en pantalla\\.\\s*){2,}"), "Hay bloques tecnicos disponibles en pantalla. ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeForAudio(message: String?): String =
        listeningText(message)
            .replace("\r\n", "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("(?m)^\\s*[-*]\\s+"), "Punto: ")
            .replace(Regex("(?m)^\\s*(\\d+)[.)]\\s+"), "Punto $1: ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun splitIntoUnits(message: String): List<String> =
        message
            .split(Regex("(?<=[.!?])\\s+|(?=Punto\\s+\\d+:)|(?=Punto:)"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun splitLongUnit(unit: String): List<String> {
        val words = unit.split(Regex("\\s+"))
        val result = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            if (current.isNotEmpty() && current.length + word.length + 1 > TARGET_CHARS) {
                result += current.toString().trim()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) {
                current.append(' ')
            }
            current.append(word)
        }
        if (current.isNotEmpty()) {
            result += current.toString().trim()
        }
        return result
    }
}
