package com.atenea.android.coreconsole

import com.atenea.android.api.CoreCommandResponse
import com.atenea.android.api.MobileVoiceFocus
import com.atenea.android.api.MobileVoiceNote
import com.atenea.android.api.VoiceDomain
import com.atenea.android.coreconsole.voice.VoiceIntent
import com.atenea.android.coreconsole.voice.VoiceSegmenter

internal fun VoiceDomain.label(): String = when (this) {
    VoiceDomain.NONE -> "Sin foco"
    VoiceDomain.DEVELOPMENT -> "Trabajo"
    VoiceDomain.OPERATIONS -> "Operaciones"
    VoiceDomain.COMMUNICATIONS -> "Comunicaciones"
    VoiceDomain.PERSONAL -> "Personal"
}

internal fun CoreCommandResponse.bestVoiceMessage(): String? =
    speakableMessage ?: operatorMessage ?: confirmation?.message ?: clarification?.message

internal fun VoiceIntent.isRealtimeRoutable(): Boolean = when (this) {
    is VoiceIntent.ArchiveNote,
    is VoiceIntent.ClarifyCurrentSegment,
    is VoiceIntent.ChangeFocus,
    is VoiceIntent.SaveNote,
    is VoiceIntent.SendNotes,
    is VoiceIntent.StartBlock,
    VoiceIntent.ArchiveAllNotes,
    VoiceIntent.ArchiveLastNote,
    VoiceIntent.BlockStatus,
    VoiceIntent.CancelBlock,
    VoiceIntent.ConfirmPending,
    VoiceIntent.ContinuePlayback,
    VoiceIntent.CountNotes,
    VoiceIntent.CheckCodexStatus,
    VoiceIntent.DescribeFocus,
    VoiceIntent.FinishBlock,
    is VoiceIntent.GoToSegment,
    VoiceIntent.NextPlayback,
    VoiceIntent.PreviousPlayback,
    is VoiceIntent.ReadNote,
    VoiceIntent.ReadNotes,
    VoiceIntent.ReadPlayback,
    VoiceIntent.RepeatPlayback,
    VoiceIntent.ReadBlock,
    VoiceIntent.SummarizeBlock,
    VoiceIntent.SavePendingNote,
    VoiceIntent.StartPlayback,
    VoiceIntent.StopPlayback -> true
    else -> false
}

internal fun buildRealtimeOperatorContext(
    focus: MobileVoiceFocus?,
    notes: List<MobileVoiceNote>,
    response: CoreCommandResponse?,
    playbackIndex: Int,
    playbackSegments: List<String>
): String = buildString {
    appendLine("Este contexto viene de Atenea Android en el momento de abrir Realtime.")
    if (focus == null) {
        appendLine("Foco: no cargado.")
    } else {
        appendLine("Dominio: ${focus.domain.label()}.")
        focus.projectId?.let { appendLine("Project ID: $it.") }
        focus.projectName?.let { appendLine("Proyecto: $it.") }
        focus.workSessionId?.let { appendLine("WorkSession ID: $it.") }
        focus.workSessionTitle?.let { appendLine("WorkSession: $it.") }
        focus.managedHostId?.let { appendLine("Managed host ID: $it.") }
        focus.managedHostName?.let { appendLine("Servidor: $it.") }
        focus.activity?.let { appendLine("Actividad: $it.") }
        focus.activeCommandId?.let { appendLine("Comando activo ID: $it.") }
        focus.latestCommandId?.let { appendLine("Ultimo comando Core ID: $it.") }
        focus.focusUpToDate?.let { appendLine("Foco al dia: $it.") }
        focus.playback?.let { playback ->
            val segment = playback.segmentIndex?.let { it + 1 }?.toString() ?: "desconocido"
            appendLine("Cursor persistido: ${playback.sourceType}/${playback.sourceId}, segmento $segment de ${playback.segmentCount ?: "desconocido"}.")
        }
        appendLine("Notas activas: ${focus.activeNoteCount}.")
    }
    if (notes.isNotEmpty()) {
        appendLine("Notas activas transcritas:")
        notes.take(5).forEachIndexed { index, note ->
            appendLine("${index + 1}. ${note.text.take(220)}")
        }
    }
    response?.let { command ->
        appendLine("Ultimo comando Core:")
        appendLine("ID: ${command.commandId}.")
        appendLine("Estado: ${command.status}.")
        command.confirmation?.message?.let { appendLine("Confirmacion pendiente: $it") }
        command.clarification?.message?.let { appendLine("Aclaracion pendiente: $it") }
        command.bestVoiceMessage()?.take(900)?.let { appendLine("Ultima respuesta conocida: $it") }
    }
    if (playbackSegments.isNotEmpty()) {
        appendLine("Lectura actual: segmento ${playbackIndex + 1} de ${playbackSegments.size}.")
        playbackSegments.getOrNull(playbackIndex)?.take(700)?.let { appendLine("Segmento actual: $it") }
    }
}.trim()

internal fun String?.toVoiceSegments(): List<String> {
    return VoiceSegmenter.segment(this)
}

internal fun String.isRealtimeOperationalRequest(): Boolean {
    val text = lowercase()
        .replace("á", "a")
        .replace("é", "e")
        .replace("í", "i")
        .replace("ó", "o")
        .replace("ú", "u")
    if (!listOf("atenea", "aterea", "atenia", "athenea", "antenea", "antena").any { text.contains(it) }) {
        return false
    }
    if (text.contains("donde estas") || text.contains("que estas haciendo") || text.contains("en que estas")) {
        return false
    }
    if (text.contains("respuesta")
        || text.contains("contestado")
        || text.contains("respondido")
        || text.contains("ha dicho")
    ) {
        return true
    }
    return listOf(
        "codex",
        "haz ",
        "hacer ",
        "implementa",
        "modifica",
        "cambia",
        "arregla",
        "corrige",
        "revisa",
        "pasa",
        "envia",
        "ejecuta",
        "crea",
        "trabaja",
        "actualiza"
    ).any { text.contains(it) }
}
