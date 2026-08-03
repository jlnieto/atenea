package com.atenea.android.coreconsole.voice

import com.atenea.android.api.VoiceDomain
import java.text.Normalizer

internal object VoiceCommandInterpreter {
    fun hasWakeWord(transcript: String): Boolean =
        transcript.voiceNormalized().wakeWordMatch() != null

    fun startsWithWakeWord(transcript: String): Boolean =
        transcript.trim().wakeCommandNormalized() != null

    fun interpretBlockControl(transcript: String): VoiceBlockControl {
        trailingFinishControl(transcript)?.let { return it }
        val command = transcript.trim().wakeCommandNormalized() ?: return VoiceBlockControl.None
        return when {
            command == "fin" -> VoiceBlockControl.Finish()
            command == "cancela bloque" || command == "descarta bloque" -> VoiceBlockControl.Cancel
            command == "lee bloque" || command == "leeme bloque" -> VoiceBlockControl.Read
            command == "resume bloque" || command == "resumen bloque" -> VoiceBlockControl.Summarize
            command == "estado bloque" -> VoiceBlockControl.Status
            else -> VoiceBlockControl.None
        }
    }

    private fun trailingFinishControl(transcript: String): VoiceBlockControl.Finish? {
        val match = TRAILING_FINISH_REGEX.find(transcript.trim()) ?: return null
        val content = transcript
            .substring(0, match.range.first)
            .trim(' ', ',', '.', ':', ';', '¿', '?', '¡', '!')
            .takeIf { it.isNotBlank() }
        return VoiceBlockControl.Finish(content)
    }

    fun interpret(transcript: String): VoiceIntent {
        val original = transcript.trim()
        if (original.isBlank()) {
            return VoiceIntent.Empty
        }

        val normalized = original.voiceNormalized()
        val wakeMatch = normalized.wakeWordMatch()
        if (wakeMatch == null) {
            return VoiceIntent.Empty
        }
        if (wakeMatch.index != 0) {
            return VoiceIntent.Empty
        }

        val command = original.dropWakePrefix(wakeMatch).trim(' ', ',', '.', ':', ';')
        val normalizedCommand = command.voiceNormalized()
        if (normalizedCommand.isBlank()) {
            return VoiceIntent.Empty
        }

        return when {
            normalizedCommand.isStartBlockCommand() -> VoiceIntent.StartBlock(VoiceBlockType.GENERIC, command.extractBlockInitialText())
            normalizedCommand.isQuestionBlockCommand() -> VoiceIntent.StartBlock(VoiceBlockType.QUESTION, command.extractBlockInitialText())
            normalizedCommand.isPromptBlockCommand() -> VoiceIntent.StartBlock(VoiceBlockType.PROMPT, command.extractBlockInitialText())
            normalizedCommand.extractReadNoteNumber() != null ->
                VoiceIntent.ReadNote(normalizedCommand.extractReadNoteNumber()!!)
            normalizedCommand.isNoteBlockCommand() -> VoiceIntent.StartBlock(VoiceBlockType.NOTE, command.extractNoteText().takeIf { it.isNotBlank() })
            normalizedCommand.isFinishBlockCommand() -> VoiceIntent.FinishBlock
            normalizedCommand.isCancelBlockCommand() -> VoiceIntent.CancelBlock
            normalizedCommand.isReadBlockCommand() -> VoiceIntent.ReadBlock
            normalizedCommand.isSummarizeBlockCommand() -> VoiceIntent.SummarizeBlock
            normalizedCommand.isBlockStatusCommand() -> VoiceIntent.BlockStatus
            normalizedCommand.isStopCommand() -> VoiceIntent.StopPlayback
            normalizedCommand.extractSegmentNumber() != null ->
                VoiceIntent.GoToSegment(normalizedCommand.extractSegmentNumber()!!)
            normalizedCommand.isStartCommand() -> VoiceIntent.StartPlayback
            normalizedCommand.isRepeatCommand() -> VoiceIntent.RepeatPlayback
            normalizedCommand.isContinueCommand() -> VoiceIntent.ContinuePlayback
            normalizedCommand.isNextCommand() -> VoiceIntent.NextPlayback
            normalizedCommand.isPreviousCommand() -> VoiceIntent.PreviousPlayback
            normalizedCommand.isCodexStatusQuestion() -> VoiceIntent.CheckCodexStatus
            normalizedCommand.isLatestSessionResponseCommand() -> VoiceIntent.RunCommand(command)
            normalizedCommand.isCountNotesCommand() -> VoiceIntent.CountNotes
            normalizedCommand.isArchiveLastNoteCommand() -> VoiceIntent.ArchiveLastNote
            normalizedCommand.isArchiveAllNotesCommand() -> VoiceIntent.ArchiveAllNotes
            normalizedCommand.extractArchiveNoteNumber() != null ->
                VoiceIntent.ArchiveNote(normalizedCommand.extractArchiveNoteNumber()!!)
            normalizedCommand.isSendNotesCommand() -> VoiceIntent.SendNotes(command.extractInstruction())
            normalizedCommand.isReadNotesCommand() -> VoiceIntent.ReadNotes
            normalizedCommand.isReadCommand() -> VoiceIntent.ReadPlayback
            normalizedCommand.isClarificationCommand() -> VoiceIntent.ClarifyCurrentSegment(command)
            normalizedCommand.isConfirmCommand() -> VoiceIntent.ConfirmPending
            normalizedCommand.isCancelPendingCommand() -> VoiceIntent.CancelPending
            normalizedCommand.isFocusQuestion() -> VoiceIntent.DescribeFocus
            normalizedCommand.isFocusChangeTo(VoiceDomain.COMMUNICATIONS) ->
                VoiceIntent.ChangeFocus(VoiceDomain.COMMUNICATIONS, "Comunicaciones")
            normalizedCommand.isFocusChangeTo(VoiceDomain.OPERATIONS) ->
                VoiceIntent.ChangeFocus(VoiceDomain.OPERATIONS, "Operaciones")
            normalizedCommand.isFocusChangeTo(VoiceDomain.DEVELOPMENT) ->
                VoiceIntent.ChangeFocus(VoiceDomain.DEVELOPMENT, "Trabajo activo")
            else -> VoiceIntent.RunCommand(command)
        }
    }

    private fun String.dropWakePrefix(wakeMatch: WakeWordMatch): String {
        val prefixLength = take(wakeMatch.index).length
        return drop(prefixLength + wakeMatch.word.length)
    }

    private fun String.isStopCommand(): Boolean =
        anyToken(
            "para",
            "parar",
            "pausa",
            "pausar",
            "espera",
            "esperar",
            "esperate",
            "calla",
            "detente",
            "silencio",
            "corta"
        )

    private fun String.isRepeatCommand(): Boolean =
        contains("repite") ||
            contains("repiteme") ||
            contains("repite esto") ||
            contains("repite este segmento") ||
            contains("repite esta parte") ||
            contains("otra vez") ||
            contains("que has dicho")

    private fun String.isStartCommand(): Boolean =
        anyToken("inicio", "empieza", "empezar", "reinicia", "reiniciar") ||
            contains("desde el principio") ||
            contains("desde principio") ||
            contains("al principio") ||
            contains("vuelve al principio") ||
            contains("empieza de nuevo") ||
            contains("empieza otra vez") ||
            contains("leer desde el principio") ||
            contains("lee desde el principio") ||
            contains("lee todo desde el principio") ||
            contains("vuelve a empezar")

    private fun String.isContinueCommand(): Boolean =
        anyToken("sigue", "seguir", "continua", "continuar") ||
            contains("sigue leyendo") ||
            contains("continua leyendo") ||
            contains("continúa leyendo")

    private fun String.isNextCommand(): Boolean =
        anyToken("siguiente", "avanza", "avanzar") ||
            contains("siguiente punto") ||
            contains("siguiente segmento") ||
            contains("siguiente parte") ||
            contains("pasa al siguiente")

    private fun String.isPreviousCommand(): Boolean =
        contains("anterior") ||
            contains("segmento anterior") ||
            contains("parte anterior") ||
            contains("punto anterior") ||
            contains("vuelve atras") ||
            contains("vuelve un poco atras") ||
            contains("retrocede") ||
            contains("atras") ||
            contains("atrás")

    private fun String.isReadCommand(): Boolean =
        contains("lee") || contains("leeme") || contains("leelo") || contains("leer respuesta")

    private fun String.isLatestSessionResponseCommand(): Boolean {
        val mentionsCodex = contains("codex")
        val mentionsLatestResponse = contains("ultima respuesta") ||
            contains("última respuesta") ||
            contains("ultimo que dijo") ||
            contains("último que dijo") ||
            contains("que ha dicho") ||
            contains("ha contestado") ||
            contains("ha respondido")
        val asksToRead = contains("lee") || contains("leeme") || contains("leer") || contains("dime")
        return (mentionsCodex || mentionsLatestResponse) &&
            mentionsLatestResponse &&
            (asksToRead || contains("contestado") || contains("respondido") || contains("ha dicho"))
    }

    private fun String.isClarificationCommand(): Boolean =
        contains("no he entendido") ||
            contains("no entiendo") ||
            contains("explicame") ||
            contains("explica esto") ||
            contains("explicalo") ||
            contains("dame mas detalle") ||
            contains("dame detalle") ||
            contains("dame un ejemplo") ||
            contains("ponme un ejemplo") ||
            contains("resume esto") ||
            contains("resumelo") ||
            contains("que significa") ||
            contains("aclarame") ||
            contains("aclararme") ||
            contains("puedes aclarar") ||
            contains("me aclaras") ||
            contains("aclara lo de") ||
            contains("explica lo de") ||
            contains("lo de la automatizacion") ||
            contains("lo de automatizacion")

    private fun String.isStartBlockCommand(): Boolean =
        this == "bloque" ||
            this == "abre bloque" ||
            this == "abrir bloque" ||
            this == "quiero pensar esto" ||
            this == "voy a pensar en voz"

    private fun String.isQuestionBlockCommand(): Boolean =
        this == "pregunta" ||
            startsWith("pregunta ") ||
            this == "duda" ||
            startsWith("duda ") ||
            this == "tengo una pregunta" ||
            startsWith("tengo una pregunta ")

    private fun String.isPromptBlockCommand(): Boolean =
        this == "prompt" ||
            startsWith("prompt ") ||
            this == "prepara prompt" ||
            startsWith("prepara prompt ") ||
            this == "preparar prompt" ||
            startsWith("preparar prompt ")

    private fun String.isNoteBlockCommand(): Boolean =
        isSaveNoteCommand() ||
            this == "nota larga" ||
            startsWith("nota larga ")

    private fun String.isFinishBlockCommand(): Boolean =
        this == "fin"

    private fun String.isCancelBlockCommand(): Boolean =
        this == "cancela bloque" || this == "descarta bloque"

    private fun String.isReadBlockCommand(): Boolean =
        this == "lee bloque" || this == "leeme bloque"

    private fun String.isSummarizeBlockCommand(): Boolean =
        this == "resume bloque" || this == "resumen bloque"

    private fun String.isBlockStatusCommand(): Boolean =
        this == "estado bloque"

    private fun String.extractBlockInitialText(): String? {
        val normalized = voiceNormalized()
        val prefixes = listOf(
            "abre bloque",
            "abrir bloque",
            "bloque",
            "pregunta",
            "duda",
            "tengo una pregunta",
            "prompt",
            "prepara prompt",
            "preparar prompt",
            "quiero pensar esto",
            "voy a pensar en voz"
        )
        val matchedPrefix = prefixes.firstOrNull { normalized == it || normalized.startsWith("$it ") } ?: return null
        return drop(matchedPrefix.length).trim(' ', ',', '.', ':', ';').takeIf { it.isNotBlank() }
    }

    private fun String.extractSegmentNumber(): Int? {
        val digit = Regex("(?:segmento|parte|punto)\\s+(\\d{1,2})").find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (digit != null) {
            return digit
        }
        return SEGMENT_NUMBER_WORDS.entries.firstOrNull { (word, _) ->
            contains("segmento $word") || contains("parte $word") || contains("punto $word")
        }?.value
    }

    private fun String.isSendNotesCommand(): Boolean =
        contains("nota") && listOf(
            "manda",
            "mandar",
            "mandalas",
            "manda las",
            "envia",
            "enviar",
            "envialas",
            "envia las",
            "envio"
        ).any { contains(it) }

    private fun String.isConfirmCommand(): Boolean =
        contains("confirmo") || contains("confirma") || contains("adelante") || contains("si confirma") || contains("si adelante")

    private fun String.isCancelPendingCommand(): Boolean =
        this == "cancela" ||
            this == "cancelar" ||
            this == "no" ||
            contains("no envies") ||
            contains("no enviar") ||
            contains("cancela envio") ||
            contains("cancela el envio") ||
            contains("cancelar envio")

    private fun String.isReadNotesCommand(): Boolean =
        contains("nota") && (
            hasReadVerb() ||
                contains("mis notas") ||
                contains("las notas") ||
                contains("notas activas")
            )

    private fun String.extractReadNoteNumber(): Int? {
        val digit = Regex("(?:nota|numero)\\s+(\\d{1,2})").find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (digit != null) {
            return digit.takeIf { hasReadVerb() || looksLikeBareReadNoteCommand() }
        }
        val wordMatch = NOTE_NUMBER_WORDS.entries.firstOrNull { (word, _) ->
            contains("nota $word") || contains("nota numero $word") || contains("numero $word")
        }?.value
        return wordMatch?.takeIf { hasReadVerb() || looksLikeBareReadNoteCommand() }
    }

    private fun String.hasReadVerb(): Boolean =
        listOf(
            "lee",
            "leer",
            "leeme",
            "leelo",
            "le",
            "ley",
            "dime",
            "dicta",
            "repasa",
            "revisa",
            "reproduce"
        ).any { anyToken(it) || contains("$it ") }

    private fun String.looksLikeBareReadNoteCommand(): Boolean =
        contains("nota") &&
            (Regex("(?:nota|numero)\\s+\\d{1,2}").containsMatchIn(this) ||
                NOTE_NUMBER_WORDS.keys.any { word -> contains("nota $word") || contains("numero $word") }) &&
            !isSendNotesCommand() &&
            !isArchiveAllNotesCommand() &&
            !isArchiveLastNoteCommand() &&
            extractArchiveNoteNumber() == null

    private fun String.isCountNotesCommand(): Boolean =
        (contains("cuantas") || contains("cuenta") || contains("numero de")) && contains("nota")

    private fun String.isArchiveLastNoteCommand(): Boolean =
        (contains("borra") || contains("elimina") || contains("descarta") || contains("archiva")) &&
            (contains("ultima nota") || contains("última nota") || contains("la ultima") || contains("la última"))

    private fun String.isArchiveAllNotesCommand(): Boolean =
        (contains("limpia") || contains("borra") || contains("elimina") || contains("descarta") || contains("archiva")) &&
            (contains("todas las notas") || contains("toda las notas") || contains("mis notas") || contains("notas activas"))

    private fun String.extractArchiveNoteNumber(): Int? {
        if (!(contains("borra") || contains("elimina") || contains("descarta") || contains("archiva"))) {
            return null
        }
        val digit = Regex("(?:nota|numero|número)\\s+(\\d{1,2})").find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (digit != null) {
            return digit
        }
        return NOTE_NUMBER_WORDS.entries.firstOrNull { (word, _) ->
            contains("nota $word") || contains("nota numero $word") || contains("nota número $word")
        }?.value
    }

    private fun String.isFocusQuestion(): Boolean =
        contains("donde estas") ||
            contains("en que estas") ||
            contains("foco actual") ||
            contains("que estas haciendo") ||
            contains("comando mas nuevo") ||
            contains("comando más nuevo") ||
            contains("estas al dia") ||
            contains("estás al día")

    private fun String.isCodexStatusQuestion(): Boolean =
        contains("codex") && (
            contains("como va") ||
                contains("cómo va") ||
                contains("ya respondio") ||
                contains("ya respondió") ||
                contains("ha respondido") ||
                contains("ha contestado") ||
                contains("sigue trabajando") ||
                contains("estado")
            )

    private fun String.isSaveNoteCommand(): Boolean =
        startsWith("apunta") ||
            startsWith("nota") ||
            startsWith("nueva nota") ||
            startsWith("toma nota") ||
            startsWith("crea nota") ||
            startsWith("guarda nota") ||
            startsWith("guardar nota") ||
            startsWith("guarda esto") ||
            startsWith("anade a notas") ||
            startsWith("añade a notas") ||
            startsWith("anota") ||
            startsWith("recuerda")

    private fun String.isFocusChangeTo(domain: VoiceDomain): Boolean = when (domain) {
        VoiceDomain.COMMUNICATIONS -> contains("comunicaciones") || contains("email") || contains("correo")
        VoiceDomain.OPERATIONS -> contains("operaciones") || contains("dedicado") || contains("apache")
        VoiceDomain.DEVELOPMENT -> contains("trabajo") || contains("desarrollo") || contains("proyecto")
        VoiceDomain.NONE,
        VoiceDomain.PERSONAL -> false
    }

    private fun String.extractInstruction(): String? {
        val marker = " y "
        val index = indexOf(marker)
        return if (index >= 0) drop(index + marker.length).trim().takeIf { it.isNotBlank() } else null
    }

    private fun String.extractNoteText(): String {
        val normalized = voiceNormalized()
        val prefixes = listOf(
            "apunta",
            "nueva nota",
            "toma nota",
            "crea nota",
            "nota",
            "guarda nota",
            "guardar nota",
            "guarda esto como nota",
            "guarda esto",
            "anade a notas",
            "añade a notas",
            "anota",
            "recuerda"
        )
        val matchedPrefix = prefixes.firstOrNull { normalized.startsWith(it) } ?: return this
        return drop(matchedPrefix.length).trim(' ', ',', '.', ':', ';')
    }

    private fun String.anyToken(vararg tokens: String): Boolean =
        tokens.any { token -> this == token || startsWith("$token ") || contains(" $token ") }

    private fun String.voiceNormalized(): String =
        Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.wakeWordMatch(): WakeWordMatch? =
        WAKE_WORDS
            .asSequence()
            .mapNotNull { word ->
                val match = Regex("(^|\\s)${Regex.escape(word)}(?=\\s|[,.:;¿?¡!]|$)")
                    .find(this)
                    ?: return@mapNotNull null
                WakeWordMatch(match.range.first + match.groupValues[1].length, word)
            }
            .minByOrNull { it.index }

    private fun String.wakeCommandNormalized(): String? {
        val normalized = voiceNormalized()
        val wakeMatch = normalized.wakeWordMatch() ?: return null
        if (wakeMatch.index != 0) {
            return null
        }
        return normalized
            .drop(wakeMatch.word.length)
            .trim(' ', ',', '.', ':', ';', '¿', '?', '¡', '!')
            .takeIf { it.isNotBlank() }
    }

    private data class WakeWordMatch(
        val index: Int,
        val word: String
    )

    private val WAKE_WORDS = listOf(
        "atenea",
        "aterea",
        "atenia",
        "athenea",
        "antenea",
        "antena"
    )

    private val TRAILING_FINISH_REGEX = Regex(
        "(?:^|\\s)(?:atenea|aterea|atenia|athenea|antenea|antena)[,\\s]+fin[.!?¿¡\\s]*$",
        RegexOption.IGNORE_CASE
    )

    private val NOTE_NUMBER_WORDS = mapOf(
        "uno" to 1,
        "una" to 1,
        "dos" to 2,
        "tres" to 3,
        "cuatro" to 4,
        "cinco" to 5,
        "seis" to 6,
        "siete" to 7,
        "ocho" to 8,
        "nueve" to 9,
        "diez" to 10
    )

    private val SEGMENT_NUMBER_WORDS = NOTE_NUMBER_WORDS
}

internal sealed interface VoiceIntent {
    data object Empty : VoiceIntent
    data class SaveNote(val text: String) : VoiceIntent
    data object SavePendingNote : VoiceIntent
    data object ReadNotes : VoiceIntent
    data class ReadNote(val number: Int) : VoiceIntent
    data object CountNotes : VoiceIntent
    data class ArchiveNote(val number: Int) : VoiceIntent
    data object ArchiveLastNote : VoiceIntent
    data object ArchiveAllNotes : VoiceIntent
    data class SendNotes(val instruction: String?) : VoiceIntent
    data class StartBlock(val type: VoiceBlockType, val initialText: String?) : VoiceIntent
    data object FinishBlock : VoiceIntent
    data object CancelBlock : VoiceIntent
    data object ReadBlock : VoiceIntent
    data object SummarizeBlock : VoiceIntent
    data object BlockStatus : VoiceIntent
    data object ConfirmPending : VoiceIntent
    data object CancelPending : VoiceIntent
    data class ClarifyCurrentSegment(val request: String) : VoiceIntent
    data object CheckCodexStatus : VoiceIntent
    data class RunCommand(val input: String) : VoiceIntent
    data class ChangeFocus(val domain: VoiceDomain, val activity: String) : VoiceIntent
    data object DescribeFocus : VoiceIntent
    data object ReadPlayback : VoiceIntent
    data object StartPlayback : VoiceIntent
    data object RepeatPlayback : VoiceIntent
    data object ContinuePlayback : VoiceIntent
    data object NextPlayback : VoiceIntent
    data object PreviousPlayback : VoiceIntent
    data class GoToSegment(val number: Int) : VoiceIntent
    data object StopPlayback : VoiceIntent
}

internal enum class VoiceBlockType {
    GENERIC,
    QUESTION,
    PROMPT,
    NOTE
}

internal sealed interface VoiceBlockControl {
    data object None : VoiceBlockControl
    data class Finish(val leadingContent: String? = null) : VoiceBlockControl
    data object Cancel : VoiceBlockControl
    data object Read : VoiceBlockControl
    data object Summarize : VoiceBlockControl
    data object Status : VoiceBlockControl
}
