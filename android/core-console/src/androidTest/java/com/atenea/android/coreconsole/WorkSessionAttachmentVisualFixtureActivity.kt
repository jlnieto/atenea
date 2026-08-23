package com.atenea.android.coreconsole

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.atenea.android.api.MobileConversationTurn
import com.atenea.android.api.WorkSessionAttachment
import java.util.UUID

class WorkSessionAttachmentVisualFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = intent.getStringExtra("state") ?: "selected"
        setContent {
            val capability = if (state == "blocked") blockedCapabilityFixture() else readyCapabilityFixture()
            val attachment = storedAttachmentFixture()
            val selected = if (state in setOf("selected", "uploading", "error")) {
                listOf(
                    PendingWorkSessionImage(
                        localId = UUID.fromString("00000000-0000-0000-0000-000000000011"),
                        uploadRequestId = UUID.fromString("00000000-0000-0000-0000-000000000012"),
                        displayName = "captura-de-prueba-con-un-nombre-muy-largo-para-validar-recorte.png",
                        contentType = "image/png",
                        sizeBytes = 384_000,
                        preview = generatedFixtureBitmap(),
                        status = when (state) {
                            "uploading" -> PendingImageStatus.UPLOADING
                            "error" -> PendingImageStatus.ERROR
                            else -> PendingImageStatus.READY
                        },
                        attachment = attachment.takeIf { state == "selected" },
                        failure = AttachmentFailure(
                            AttachmentFailureCategory.TRANSPORT,
                            "No se pudo comunicar con Atenea. Reintenta sin cambiar la selección.",
                            true
                        ).takeIf { state == "error" }
                    )
                )
            } else emptyList()
            val turns = if (state == "historical") {
                listOf(
                    MobileConversationTurn(
                        id = 1,
                        actor = "OPERATOR",
                        messageText = "Compara el estado visible y señala la acción principal.",
                        createdAt = "2026-08-11T12:00:00Z",
                        attachments = listOf(historicalAttachmentFixture())
                    ),
                    MobileConversationTurn(
                        id = 2,
                        actor = "AGENT",
                        messageText = "La imagen está vinculada únicamente al turno anterior.",
                        createdAt = "2026-08-11T12:01:00Z"
                    )
                )
            } else emptyList()
            ConversationSurface(
                title = "WorkSession 19",
                status = "OPEN",
                turns = turns,
                input = if (state in setOf("selected", "uploading", "error")) "Describe esta captura y comprueba que no haya solapamientos." else "",
                pending = false,
                placeholder = "Escribe o dicta la siguiente instrucción para Codex",
                onInputChange = {},
                onSend = {},
                onMicrophoneClick = {},
                onBack = {},
                onOpenCore = {},
                onRefresh = {},
                error = null,
                attachmentDraft = WorkSessionAttachmentDraft(
                    capability = capability,
                    capabilityLoading = false,
                    images = selected
                ),
                onAttachImages = {},
                onRemoveImage = {},
                onRetryImage = {},
                onOpenHistoricalAttachment = {}
            )
        }
    }
}

private fun generatedFixtureBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(31, 69, 64))
    val paint = Paint().apply {
        color = Color.rgb(23, 148, 137)
        style = Paint.Style.FILL
    }
    canvas.drawRect(16f, 18f, 144f, 40f, paint)
    paint.color = Color.rgb(231, 236, 233)
    canvas.drawRect(16f, 54f, 104f, 66f, paint)
    return bitmap
}

private fun storedAttachmentFixture() = WorkSessionAttachment(
    id = UUID.fromString("00000000-0000-0000-0000-000000000021"),
    workSessionId = 19,
    projectId = 1,
    agentRunId = null,
    source = "OPERATOR_UPLOAD",
    kind = "IMAGE",
    originalFilename = "fixture-selected.png",
    contentType = "image/png",
    sizeBytes = 384_000,
    retentionClass = "SESSION",
    retainUntil = "2026-09-10T12:00:00Z",
    sha256 = "a".repeat(64),
    createdAt = "2026-08-11T12:00:00Z",
    indexedAt = "2026-08-11T12:00:00Z"
)
