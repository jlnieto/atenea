package com.atenea.android.coreconsole

import android.graphics.Bitmap
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.MobileWorkSessionConversation
import com.atenea.android.api.WorkSessionAttachment
import com.atenea.android.api.WorkSessionAttachmentCapability
import com.atenea.android.api.WorkSessionAttachmentCapabilityState
import java.io.IOException
import java.util.UUID

internal enum class PendingImageStatus { SELECTED, UPLOADING, READY, ERROR }

internal enum class AttachmentFailureCategory {
    TRANSPORT,
    CAPACITY,
    VALIDATION,
    POLICY,
    OWNERSHIP,
    AUTHORIZATION,
    CONFLICT,
    UNKNOWN
}

internal data class AttachmentFailure(
    val category: AttachmentFailureCategory,
    val message: String,
    val retryable: Boolean
)

internal data class PendingWorkSessionImage(
    val localId: UUID,
    val uploadRequestId: UUID,
    val displayName: String,
    val contentType: String,
    val sizeBytes: Long,
    val localImage: LocalWorkSessionImage? = null,
    val preview: Bitmap? = null,
    val status: PendingImageStatus = PendingImageStatus.SELECTED,
    val attachment: WorkSessionAttachment? = null,
    val failure: AttachmentFailure? = null
)

internal data class PendingTurnSubmission(
    val requestId: UUID,
    val normalizedMessage: String,
    val attachmentIds: List<UUID>
)

internal data class WorkSessionAttachmentDraft(
    val capability: WorkSessionAttachmentCapability? = null,
    val capabilityLoading: Boolean = true,
    val capabilityFailure: AttachmentFailure? = null,
    val submissionFailure: AttachmentFailure? = null,
    val images: List<PendingWorkSessionImage> = emptyList(),
    val pendingTurn: PendingTurnSubmission? = null
) {
    val readyImages: List<PendingWorkSessionImage>
        get() = images.filter { it.status == PendingImageStatus.READY && it.attachment != null }

    val attachmentIds: List<UUID>
        get() = readyImages.mapNotNull { it.attachment?.id }

    val isReady: Boolean
        get() = capability?.state == WorkSessionAttachmentCapabilityState.READY

    val hasUploadInProgress: Boolean
        get() = images.any { it.status == PendingImageStatus.UPLOADING || it.status == PendingImageStatus.SELECTED }

    val isSubmissionLocked: Boolean
        get() = pendingTurn != null

    fun canAddImage(): Boolean {
        val current = capability ?: return false
        return current.state == WorkSessionAttachmentCapabilityState.READY &&
            pendingTurn == null &&
            images.size < current.maxAttachmentsPerTurn
    }

    fun acceptCandidate(candidate: LocalWorkSessionImage, localId: UUID, uploadRequestId: UUID): WorkSessionAttachmentDraft {
        val validation = validateImageCandidate(capability, images, candidate)
        if (validation != null) {
            return copy(capabilityFailure = validation)
        }
        return copy(
            capabilityFailure = null,
            images = images + PendingWorkSessionImage(
                localId = localId,
                uploadRequestId = uploadRequestId,
                displayName = candidate.displayName,
                contentType = candidate.contentType,
                sizeBytes = candidate.bytes.size.toLong(),
                localImage = candidate,
                preview = candidate.preview
            )
        )
    }

    fun markUploading(localId: UUID): WorkSessionAttachmentDraft = updateImage(localId) {
        it.copy(status = PendingImageStatus.UPLOADING, failure = null)
    }

    fun markUploaded(localId: UUID, attachment: WorkSessionAttachment): WorkSessionAttachmentDraft = updateImage(localId) {
        require(attachment.id.toString().isNotBlank())
        it.localImage?.bytes?.fill(0)
        it.copy(status = PendingImageStatus.READY, localImage = null, attachment = attachment, failure = null)
    }

    fun markUploadFailed(localId: UUID, failure: AttachmentFailure): WorkSessionAttachmentDraft = updateImage(localId) {
        it.copy(status = PendingImageStatus.ERROR, failure = failure)
    }

    fun remove(localId: UUID): WorkSessionAttachmentDraft = if (pendingTurn == null) {
        images.firstOrNull { it.localId == localId }?.localImage?.bytes?.fill(0)
        images.firstOrNull { it.localId == localId }?.preview?.takeUnless { it.isRecycled }?.recycle()
        copy(images = images.filterNot { it.localId == localId }, capabilityFailure = null)
    } else {
        this
    }

    fun beginSubmission(message: String, requestId: UUID): WorkSessionAttachmentDraft {
        val normalized = message.trim()
        require(normalized.isNotBlank()) { "La instrucción no puede estar vacía." }
        require(!hasUploadInProgress) { "Espera a que terminen las subidas." }
        require(images.all { it.status == PendingImageStatus.READY }) { "Revisa las imágenes con error antes de enviar." }
        val exact = PendingTurnSubmission(requestId, normalized, attachmentIds)
        val existing = pendingTurn
        require(existing == null || existing == exact) {
            "El envío pendiente debe reintentarse exactamente o restablecerse."
        }
        return copy(pendingTurn = existing ?: exact, submissionFailure = null)
    }

    fun acceptConversation(conversation: MobileWorkSessionConversation): WorkSessionAttachmentDraft {
        val expected = pendingTurn ?: return this
        val accepted = conversation.recentTurns.any { turn ->
            turn.actor == "OPERATOR" &&
                turn.messageText.trim() == expected.normalizedMessage &&
                turn.attachments.sortedBy { it.position }.map { it.id } == expected.attachmentIds
        }
        return if (accepted) {
            images.forEach { it.preview?.takeUnless { preview -> preview.isRecycled }?.recycle() }
            copy(images = emptyList(), pendingTurn = null, capabilityFailure = null, submissionFailure = null)
        } else this
    }

    fun resetUncertainSubmission(): WorkSessionAttachmentDraft = copy(pendingTurn = null)

    fun markSubmissionFailed(failure: AttachmentFailure): WorkSessionAttachmentDraft = copy(submissionFailure = failure)

    private fun updateImage(
        localId: UUID,
        transform: (PendingWorkSessionImage) -> PendingWorkSessionImage
    ): WorkSessionAttachmentDraft = copy(images = images.map { if (it.localId == localId) transform(it) else it })
}

internal fun validateImageCandidate(
    capability: WorkSessionAttachmentCapability?,
    current: List<PendingWorkSessionImage>,
    candidate: LocalWorkSessionImage
): AttachmentFailure? {
    if (capability == null || capability.state != WorkSessionAttachmentCapabilityState.READY) {
        return AttachmentFailure(AttachmentFailureCategory.POLICY, "Las imágenes no están disponibles en esta sesión.", false)
    }
    if (current.size >= capability.maxAttachmentsPerTurn) {
        return AttachmentFailure(AttachmentFailureCategory.CAPACITY, "Puedes adjuntar hasta ${capability.maxAttachmentsPerTurn} imágenes por turno.", false)
    }
    val bytes = candidate.bytes.size.toLong()
    if (bytes <= 0L) {
        return AttachmentFailure(AttachmentFailureCategory.VALIDATION, "La imagen está vacía.", false)
    }
    if (candidate.contentType !in capability.acceptedContentTypes || candidate.contentType !in SUPPORTED_IMAGE_TYPES) {
        return AttachmentFailure(AttachmentFailureCategory.VALIDATION, "Usa una imagen PNG, JPEG o WebP.", false)
    }
    if (bytes > capability.maxFileBytes) {
        return AttachmentFailure(AttachmentFailureCategory.CAPACITY, "La imagen supera ${formatAttachmentBytes(capability.maxFileBytes)}.", false)
    }
    if (bytes > capability.remainingSessionBytes) {
        return AttachmentFailure(AttachmentFailureCategory.CAPACITY, "La sesión no tiene espacio suficiente para esta imagen.", false)
    }
    if (current.sumOf { it.sizeBytes } + bytes > capability.maxAttachmentBytesPerTurn) {
        return AttachmentFailure(AttachmentFailureCategory.CAPACITY, "Las imágenes del turno superan ${formatAttachmentBytes(capability.maxAttachmentBytesPerTurn)}.", false)
    }
    return null
}

internal fun classifyAttachmentFailure(error: Throwable): AttachmentFailure = when (error) {
    is AteneaApiException -> when (error.status) {
        400, 415, 422 -> AttachmentFailure(AttachmentFailureCategory.VALIDATION, error.message, false)
        401 -> AttachmentFailure(AttachmentFailureCategory.AUTHORIZATION, error.message, false)
        403 -> if (error.message.contains("disabled", true) || error.message.contains("eligible", true)) {
            AttachmentFailure(AttachmentFailureCategory.POLICY, error.message, false)
        } else {
            AttachmentFailure(AttachmentFailureCategory.AUTHORIZATION, error.message, false)
        }
        404 -> AttachmentFailure(AttachmentFailureCategory.OWNERSHIP, error.message, false)
        409 -> AttachmentFailure(AttachmentFailureCategory.CONFLICT, error.message, false)
        413, 429 -> AttachmentFailure(AttachmentFailureCategory.CAPACITY, error.message, false)
        in 500..599 -> AttachmentFailure(AttachmentFailureCategory.TRANSPORT, "Atenea no pudo completar la operación. Reintenta sin cambiar la selección.", true)
        else -> AttachmentFailure(AttachmentFailureCategory.UNKNOWN, error.message, false)
    }
    is IOException -> AttachmentFailure(AttachmentFailureCategory.TRANSPORT, "No se pudo comunicar con Atenea. Reintenta sin cambiar la selección.", true)
    is IllegalArgumentException -> AttachmentFailure(AttachmentFailureCategory.VALIDATION, error.message ?: "Revisa el borrador antes de enviar.", false)
    else -> AttachmentFailure(AttachmentFailureCategory.UNKNOWN, error.message ?: "No se pudo completar la operación.", false)
}

internal fun formatAttachmentBytes(bytes: Long): String {
    val kib = 1024.0
    val mib = kib * 1024.0
    return when {
        bytes >= mib -> String.format("%.1f MB", bytes / mib)
        bytes >= kib -> String.format("%.1f KB", bytes / kib)
        else -> "$bytes B"
    }
}

internal val SUPPORTED_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp")
