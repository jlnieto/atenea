package com.atenea.android.coreconsole.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VoiceCommandInterpreterTest {
    @Test
    fun ignoresTranscriptsWithoutWakeWord() {
        assertEquals(VoiceIntent.Empty, VoiceCommandInterpreter.interpret("guarda esto como nota"))
    }

    @Test
    fun ignoresWakeWordThatDoesNotStartTheCommand() {
        assertEquals(VoiceIntent.Empty, VoiceCommandInterpreter.interpret("vale Atenea, nota revisar esto"))
        assertEquals(false, VoiceCommandInterpreter.startsWithWakeWord("vale Atenea, nota revisar esto"))
        assertEquals(true, VoiceCommandInterpreter.startsWithWakeWord("Athenea, nota revisar esto"))
        assertEquals(false, VoiceCommandInterpreter.startsWithWakeWord("Atena, elimina nota dos"))
    }

    @Test
    fun routesReadNotesBeforeGenericReadCommand() {
        assertEquals(VoiceIntent.ReadNotes, VoiceCommandInterpreter.interpret("Atenea, lee mis notas"))
    }

    @Test
    fun routesReadSingleNoteBeforeReadAllNotes() {
        assertEquals(VoiceIntent.ReadNote(1), VoiceCommandInterpreter.interpret("Atenea, lee nota 1"))
        assertEquals(VoiceIntent.ReadNote(1), VoiceCommandInterpreter.interpret("Atenea, lee nota uno"))
        assertEquals(VoiceIntent.ReadNote(1), VoiceCommandInterpreter.interpret("Atenea, le nota 1"))
        assertEquals(VoiceIntent.ReadNote(1), VoiceCommandInterpreter.interpret("Atenea, dime nota uno"))
        assertEquals(VoiceIntent.ReadNote(1), VoiceCommandInterpreter.interpret("Atenea, nota 1"))
    }

    @Test
    fun routesArchiveLastNote() {
        assertEquals(VoiceIntent.ArchiveLastNote, VoiceCommandInterpreter.interpret("Atenea, borra la ultima nota"))
    }

    @Test
    fun routesDirectNoteCommandToSaveNote() {
        val intent = VoiceCommandInterpreter.interpret("Atenea, nota revisar el contrato del cliente")

        assertIs<VoiceIntent.StartBlock>(intent)
        assertEquals(VoiceBlockType.NOTE, intent.type)
        assertEquals("revisar el contrato del cliente", intent.initialText)
    }

    @Test
    fun routesTakeNoteCommandToSaveNote() {
        val intent = VoiceCommandInterpreter.interpret("Atenea, toma nota llamar a Javier")

        assertIs<VoiceIntent.StartBlock>(intent)
        assertEquals(VoiceBlockType.NOTE, intent.type)
        assertEquals("llamar a Javier", intent.initialText)
    }

    @Test
    fun routesQuestionToBlockMode() {
        val intent = VoiceCommandInterpreter.interpret("Athenea, pregunta lo de la automatizacion")

        assertIs<VoiceIntent.StartBlock>(intent)
        assertEquals(VoiceBlockType.QUESTION, intent.type)
        assertEquals("lo de la automatizacion", intent.initialText)
    }

    @Test
    fun onlyExactWakeWordFinClosesBlock() {
        assertEquals(VoiceBlockControl.Finish(), VoiceCommandInterpreter.interpretBlockControl("Atenea, fin"))
        assertEquals(VoiceBlockControl.Finish(), VoiceCommandInterpreter.interpretBlockControl("Athenea fin."))
        assertEquals(VoiceBlockControl.None, VoiceCommandInterpreter.interpretBlockControl("Atena fin."))
        assertEquals(VoiceBlockControl.None, VoiceCommandInterpreter.interpretBlockControl("Atenea, define el fin de la automatizacion"))
        assertEquals(VoiceBlockControl.None, VoiceCommandInterpreter.interpretBlockControl("al final esto deberia hacerse"))
    }

    @Test
    fun closesBlockWhenWakeWordFinIsTrailingAfterContent() {
        assertEquals(
            VoiceBlockControl.Finish("esto ultimo tambien forma parte de la nota"),
            VoiceCommandInterpreter.interpretBlockControl("esto ultimo tambien forma parte de la nota Atenea fin")
        )
    }

    @Test
    fun routesClarificationAboutAutomation() {
        val intent = VoiceCommandInterpreter.interpret("Atenea, puedes aclararme lo de la automatizacion")

        assertIs<VoiceIntent.ClarifyCurrentSegment>(intent)
    }

    @Test
    fun routesDirectSegmentNavigation() {
        val intent = VoiceCommandInterpreter.interpret("Atenea, ve al segmento tres")

        assertIs<VoiceIntent.GoToSegment>(intent)
        assertEquals(3, intent.number)
    }

    @Test
    fun routesExactSegmentNavigationBeforeContinueWords() {
        val first = VoiceCommandInterpreter.interpret("Atenea, segmento uno")
        val third = VoiceCommandInterpreter.interpret("Atenea, continua por el segmento tres")

        assertIs<VoiceIntent.GoToSegment>(first)
        assertEquals(1, first.number)
        assertIs<VoiceIntent.GoToSegment>(third)
        assertEquals(3, third.number)
    }

    @Test
    fun separatesContinueFromNext() {
        assertEquals(VoiceIntent.ContinuePlayback, VoiceCommandInterpreter.interpret("Atenea, continua"))
        assertEquals(VoiceIntent.ContinuePlayback, VoiceCommandInterpreter.interpret("Atenea, sigue leyendo"))
        assertEquals(VoiceIntent.NextPlayback, VoiceCommandInterpreter.interpret("Atenea, siguiente"))
    }

    @Test
    fun routesGenericReadCommand() {
        assertEquals(VoiceIntent.ReadPlayback, VoiceCommandInterpreter.interpret("Atenea, lee esto"))
    }

    @Test
    fun routesCancelPendingAction() {
        assertEquals(VoiceIntent.CancelPending, VoiceCommandInterpreter.interpret("Atenea, cancela el envio"))
    }

    @Test
    fun routesSendNotesToPendingBackendIntent() {
        val plain = VoiceCommandInterpreter.interpret("Atenea, envia la nota a Codex")
        val infinitive = VoiceCommandInterpreter.interpret("Atenea, enviar las notas")
        val instructed = VoiceCommandInterpreter.interpret("Atenea, manda las notas y pide que priorice lo urgente")

        assertIs<VoiceIntent.SendNotes>(plain)
        assertEquals(null, plain.instruction)
        assertIs<VoiceIntent.SendNotes>(infinitive)
        assertEquals(null, infinitive.instruction)
        assertIs<VoiceIntent.SendNotes>(instructed)
        assertEquals("pide que priorice lo urgente", instructed.instruction)
    }

    @Test
    fun routesCodexStatusQuestion() {
        assertEquals(VoiceIntent.CheckCodexStatus, VoiceCommandInterpreter.interpret("Atenea, como va Codex"))
        assertEquals(VoiceIntent.CheckCodexStatus, VoiceCommandInterpreter.interpret("Atenea, estado de Codex"))
        assertEquals(VoiceIntent.CheckCodexStatus, VoiceCommandInterpreter.interpret("Atenea, Codex ya ha respondido"))
    }

    @Test
    fun routesConfirmationBeforeGenericCommands() {
        assertEquals(VoiceIntent.ConfirmPending, VoiceCommandInterpreter.interpret("Atenea, confirmo"))
        assertEquals(VoiceIntent.ConfirmPending, VoiceCommandInterpreter.interpret("Atenea, adelante"))
    }

    @Test
    fun routesRepeatedControlCommandsDeterministically() {
        assertEquals(VoiceIntent.RepeatPlayback, VoiceCommandInterpreter.interpret("Atenea, repite"))
        assertEquals(VoiceIntent.RepeatPlayback, VoiceCommandInterpreter.interpret("Atenea, repite"))
    }

    @Test
    fun keepsOperationalCommandPayloadWithoutWakeWord() {
        val intent = VoiceCommandInterpreter.interpret("Atenea, dile a Codex que revise el modulo de voz")

        assertIs<VoiceIntent.RunCommand>(intent)
        assertEquals("dile a Codex que revise el modulo de voz", intent.input)
    }
}
