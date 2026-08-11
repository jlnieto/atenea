package com.atenea.android.coreconsole

import com.atenea.android.api.SessionTurnAttachment
import com.atenea.android.api.WorkSessionAttachmentBlockedReason
import com.atenea.android.api.WorkSessionAttachmentCapability
import com.atenea.android.api.WorkSessionAttachmentCapabilityState
import com.atenea.android.api.WorkSessionAttachmentWorkerCompatibility
import java.util.UUID

internal fun readyCapabilityFixture() = WorkSessionAttachmentCapability(
    state = WorkSessionAttachmentCapabilityState.READY,
    blockedReason = WorkSessionAttachmentBlockedReason.NONE,
    message = "Puedes adjuntar imágenes.",
    nextAction = "Selecciona hasta 4 imágenes.",
    policyRevision = "atenea-real-attachments-v1",
    workerCompatibility = WorkSessionAttachmentWorkerCompatibility.COMPATIBLE,
    acceptedContentTypes = listOf("image/png", "image/jpeg", "image/webp"),
    currentSessionBytes = 384_000,
    maxSessionBytes = 256L * 1024L * 1024L,
    remainingSessionBytes = 255L * 1024L * 1024L,
    maxFileBytes = 16L * 1024L * 1024L,
    maxAttachmentsPerTurn = 4,
    maxAttachmentBytesPerTurn = 32L * 1024L * 1024L
)

internal fun blockedCapabilityFixture() = readyCapabilityFixture().copy(
    state = WorkSessionAttachmentCapabilityState.BLOCKED,
    blockedReason = WorkSessionAttachmentBlockedReason.SESSION_NOT_ELIGIBLE,
    message = "Esta WorkSession se creó antes de activar imágenes.",
    nextAction = "Abre una WorkSession nueva de Atenea.",
    workerCompatibility = WorkSessionAttachmentWorkerCompatibility.NOT_CHECKED
)

internal fun historicalAttachmentFixture() = SessionTurnAttachment(
    id = UUID.fromString("00000000-0000-0000-0000-000000000031"),
    position = 0,
    originalFilename = "fixture-history.png",
    contentType = "image/png",
    sizeBytes = 512_000,
    sha256 = "b".repeat(64),
    downloadPath = "/api/sessions/19/attachments/00000000-0000-0000-0000-000000000031/content"
)
