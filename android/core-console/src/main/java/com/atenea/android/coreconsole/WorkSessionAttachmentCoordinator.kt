package com.atenea.android.coreconsole

import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileWorkSessionConversation
import com.atenea.android.api.WorkSessionAttachment
import java.util.UUID

internal class WorkSessionAttachmentCoordinator(
    private val remote: WorkSessionAttachmentRemote,
    private val uuidFactory: () -> UUID = UUID::randomUUID
) {
    constructor(apiClient: AteneaApiClient, uuidFactory: () -> UUID = UUID::randomUUID) : this(
        remote = AteneaWorkSessionAttachmentRemote(apiClient),
        uuidFactory = uuidFactory
    )

    suspend fun uploadSequentially(
        sessionId: Long,
        initial: WorkSessionAttachmentDraft,
        candidates: List<LocalWorkSessionImage>,
        onState: (WorkSessionAttachmentDraft) -> Unit = {}
    ): WorkSessionAttachmentDraft {
        var state = initial
        for (candidate in candidates) {
            val localId = uuidFactory()
            val uploadRequestId = uuidFactory()
            state = state.acceptCandidate(candidate, localId, uploadRequestId)
            onState(state)
            if (state.images.none { it.localId == localId }) {
                candidate.bytes.fill(0)
                continue
            }
            state = upload(sessionId, state, localId, onState)
        }
        return state
    }

    suspend fun retryUpload(
        sessionId: Long,
        initial: WorkSessionAttachmentDraft,
        localId: UUID,
        onState: (WorkSessionAttachmentDraft) -> Unit = {}
    ): WorkSessionAttachmentDraft = upload(sessionId, initial, localId, onState)

    suspend fun submit(
        sessionId: Long,
        initial: WorkSessionAttachmentDraft,
        message: String,
        onState: (WorkSessionAttachmentDraft) -> Unit = {}
    ): AttachmentSubmissionResult {
        val requestId = initial.pendingTurn?.requestId ?: uuidFactory()
        var state = try {
            initial.beginSubmission(message, requestId)
        } catch (error: Throwable) {
            return AttachmentSubmissionResult(initial.markSubmissionFailed(classifyAttachmentFailure(error)), null)
        }
        onState(state)
        val request = checkNotNull(state.pendingTurn)
        return try {
            val conversation = remote.createTurn(
                sessionId = sessionId,
                message = request.normalizedMessage,
                clientRequestId = request.requestId,
                attachmentIds = request.attachmentIds
            )
            state = state.acceptConversation(conversation)
            if (state.pendingTurn != null) {
                state = state.markSubmissionFailed(
                    AttachmentFailure(
                        category = AttachmentFailureCategory.TRANSPORT,
                        message = "Atenea respondió sin confirmar el turno exacto. Reintenta sin cambiar el borrador.",
                        retryable = true
                    )
                )
            }
            onState(state)
            AttachmentSubmissionResult(state, conversation)
        } catch (error: Throwable) {
            state = state.markSubmissionFailed(classifyAttachmentFailure(error))
            onState(state)
            AttachmentSubmissionResult(state, null)
        }
    }

    private suspend fun upload(
        sessionId: Long,
        initial: WorkSessionAttachmentDraft,
        localId: UUID,
        onState: (WorkSessionAttachmentDraft) -> Unit
    ): WorkSessionAttachmentDraft {
        val selected = initial.images.firstOrNull { it.localId == localId } ?: return initial
        val local = selected.localImage ?: return initial
        var state = initial.markUploading(localId)
        onState(state)
        state = try {
            val attachment = remote.upload(
                sessionId = sessionId,
                idempotencyKey = selected.uploadRequestId,
                fileName = selected.displayName,
                contentType = selected.contentType,
                bytes = local.bytes
            )
            state.markUploaded(localId, attachment)
        } catch (error: Throwable) {
            state.markUploadFailed(localId, classifyAttachmentFailure(error))
        }
        onState(state)
        return state
    }
}

internal data class AttachmentSubmissionResult(
    val draft: WorkSessionAttachmentDraft,
    val conversation: MobileWorkSessionConversation?
)

internal interface WorkSessionAttachmentRemote {
    suspend fun upload(
        sessionId: Long,
        idempotencyKey: UUID,
        fileName: String,
        contentType: String,
        bytes: ByteArray
    ): WorkSessionAttachment

    suspend fun createTurn(
        sessionId: Long,
        message: String,
        clientRequestId: UUID,
        attachmentIds: List<UUID>
    ): MobileWorkSessionConversation
}

private class AteneaWorkSessionAttachmentRemote(
    private val apiClient: AteneaApiClient
) : WorkSessionAttachmentRemote {
    override suspend fun upload(
        sessionId: Long,
        idempotencyKey: UUID,
        fileName: String,
        contentType: String,
        bytes: ByteArray
    ): WorkSessionAttachment = apiClient.uploadWorkSessionAttachment(
        sessionId = sessionId,
        idempotencyKey = idempotencyKey,
        fileName = fileName,
        contentType = contentType,
        bytes = bytes
    )

    override suspend fun createTurn(
        sessionId: Long,
        message: String,
        clientRequestId: UUID,
        attachmentIds: List<UUID>
    ): MobileWorkSessionConversation = apiClient.createMobileWorkSessionTurn(
        sessionId = sessionId,
        message = message,
        clientRequestId = clientRequestId,
        attachmentIds = attachmentIds
    )
}
