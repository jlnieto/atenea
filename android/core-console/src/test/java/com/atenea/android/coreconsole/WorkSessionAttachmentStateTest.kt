package com.atenea.android.coreconsole

import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.MobileConversationTurn
import com.atenea.android.api.MobileWorkSession
import com.atenea.android.api.MobileWorkSessionConversation
import com.atenea.android.api.SessionTurnAttachment
import com.atenea.android.api.WorkSessionAttachment
import com.atenea.android.api.WorkSessionAttachmentBlockedReason
import com.atenea.android.api.WorkSessionAttachmentCapability
import com.atenea.android.api.WorkSessionAttachmentCapabilityState
import com.atenea.android.api.WorkSessionAttachmentWorkerCompatibility
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkSessionAttachmentStateTest {

    @Test
    fun `candidate limits are server-derived and preserve existing selection`() {
        val capability = readyCapability(maxCount = 1, maxFileBytes = 8)
        val image = pngImage(size = 8)
        val draft = WorkSessionAttachmentDraft(capability = capability, capabilityLoading = false)
            .acceptCandidate(image, uuid(1), uuid(11))

        assertEquals(1, draft.images.size)
        assertFalse(draft.canAddImage())

        val rejected = draft.acceptCandidate(pngImage(size = 1), uuid(2), uuid(12))
        assertEquals(1, rejected.images.size)
        assertEquals(AttachmentFailureCategory.CAPACITY, rejected.capabilityFailure?.category)
    }

    @Test
    fun `uncertain submission stays immutable until exact accepted echo`() {
        val attachment = storedAttachment(uuid(21))
        val ready = WorkSessionAttachmentDraft(capability = readyCapability(), capabilityLoading = false)
            .acceptCandidate(pngImage(), uuid(1), uuid(11))
            .markUploaded(uuid(1), attachment)
        val pending = ready.beginSubmission("  describe la imagen  ", uuid(30))

        assertTrue(pending.isSubmissionLocked)
        assertEquals(listOf(attachment.id), pending.attachmentIds)
        assertEquals(pending, pending.acceptConversation(conversation("otro mensaje", listOf(attachment.id))))
        assertEquals(pending, pending.acceptConversation(conversation("describe la imagen", emptyList())))

        val accepted = pending.acceptConversation(conversation("describe la imagen", listOf(attachment.id)))
        assertTrue(accepted.images.isEmpty())
        assertNull(accepted.pendingTurn)
    }

    @Test
    fun `same request cannot be changed but explicit reset retains uploaded image`() {
        val attachment = storedAttachment(uuid(21))
        val pending = WorkSessionAttachmentDraft(capability = readyCapability(), capabilityLoading = false)
            .acceptCandidate(pngImage(), uuid(1), uuid(11))
            .markUploaded(uuid(1), attachment)
            .beginSubmission("mensaje", uuid(30))

        assertFailsWith<IllegalArgumentException> { pending.beginSubmission("mensaje cambiado", uuid(30)) }
        assertEquals(1, pending.remove(uuid(1)).images.size)

        val reset = pending.resetUncertainSubmission()
        assertNull(reset.pendingTurn)
        assertEquals(listOf(attachment.id), reset.attachmentIds)
    }

    @Test
    fun `failure classification never turns deterministic 4xx into transport`() {
        assertEquals(AttachmentFailureCategory.VALIDATION, classifyAttachmentFailure(AteneaApiException(415, "tipo")).category)
        assertEquals(AttachmentFailureCategory.CAPACITY, classifyAttachmentFailure(AteneaApiException(413, "grande")).category)
        assertEquals(AttachmentFailureCategory.OWNERSHIP, classifyAttachmentFailure(AteneaApiException(404, "ajena")).category)
        assertEquals(AttachmentFailureCategory.CONFLICT, classifyAttachmentFailure(AteneaApiException(409, "conflicto")).category)
        assertEquals(AttachmentFailureCategory.AUTHORIZATION, classifyAttachmentFailure(AteneaApiException(403, "sin permiso")).category)
        assertEquals(AttachmentFailureCategory.POLICY, classifyAttachmentFailure(AteneaApiException(403, "session not eligible")).category)
        assertEquals(AttachmentFailureCategory.TRANSPORT, classifyAttachmentFailure(IOException("offline")).category)
    }

    @Test
    fun `coordinator uploads sequentially and retries failed bytes with same identity`() = runBlocking {
        val ids = ArrayDeque(listOf(uuid(1), uuid(11), uuid(2), uuid(12)))
        val remote = FakeAttachmentRemote().apply { failUploadCall = 2 }
        val coordinator = WorkSessionAttachmentCoordinator(remote) { ids.removeFirst() }
        val firstBytes = pngBytes()
        val secondBytes = pngBytes()

        val failed = coordinator.uploadSequentially(
            sessionId = 19,
            initial = WorkSessionAttachmentDraft(capability = readyCapability(), capabilityLoading = false),
            candidates = listOf(
                LocalWorkSessionImage("one.png", "image/png", firstBytes),
                LocalWorkSessionImage("two.png", "image/png", secondBytes)
            )
        )

        assertEquals(listOf(uuid(11), uuid(12)), remote.uploadKeys)
        assertEquals(listOf(PendingImageStatus.READY, PendingImageStatus.ERROR), failed.images.map { it.status })
        assertTrue(firstBytes.all { it == 0.toByte() })
        assertTrue(secondBytes.any { it != 0.toByte() })

        remote.failUploadCall = null
        val retried = coordinator.retryUpload(19, failed, uuid(2))
        assertEquals(listOf(uuid(11), uuid(12), uuid(12)), remote.uploadKeys)
        assertTrue(retried.images.all { it.status == PendingImageStatus.READY })
        assertTrue(secondBytes.all { it == 0.toByte() })
    }

    @Test
    fun `coordinator repeats uncertain turn with exact request and clears only exact echo`() = runBlocking {
        val attachment = storedAttachment(uuid(21))
        val remote = FakeAttachmentRemote().apply {
            failFirstTurn = true
            turnConversation = conversation("mensaje", listOf(attachment.id))
        }
        val coordinator = WorkSessionAttachmentCoordinator(remote) { uuid(30) }
        val ready = WorkSessionAttachmentDraft(capability = readyCapability(), capabilityLoading = false)
            .acceptCandidate(pngImage(), uuid(1), uuid(11))
            .markUploaded(uuid(1), attachment)

        val uncertain = coordinator.submit(19, ready, "mensaje").draft
        assertNotNull(uncertain.pendingTurn)
        assertEquals(AttachmentFailureCategory.TRANSPORT, uncertain.submissionFailure?.category)

        val accepted = coordinator.submit(19, uncertain, "mensaje").draft
        assertNull(accepted.pendingTurn)
        assertTrue(accepted.images.isEmpty())
        assertEquals(listOf(uuid(30), uuid(30)), remote.turnRequestIds)
        assertEquals(listOf(listOf(attachment.id), listOf(attachment.id)), remote.turnAttachmentIds)
    }

    private fun conversation(message: String, attachmentIds: List<UUID>): MobileWorkSessionConversation =
        MobileWorkSessionConversation(
            session = MobileWorkSession(
                id = 19,
                projectId = 1,
                title = "Fixture",
                status = "OPEN",
                operationalState = "NOT_STARTED",
                baseBranch = "main",
                workspaceBranch = "codex/fixture",
                pullRequestUrl = null,
                pullRequestStatus = null,
                finalCommitSha = null,
                openedAt = null,
                lastActivityAt = null,
                publishedAt = null,
                closedAt = null,
                closeBlockedState = null,
                closeBlockedReason = null,
                closeBlockedAction = null,
                closeRetryable = false
            ),
            runInProgress = false,
            canCreateTurn = true,
            latestRun = null,
            lastError = null,
            lastAgentResponse = null,
            recentTurns = listOf(
                MobileConversationTurn(
                    id = 1,
                    actor = "OPERATOR",
                    messageText = message,
                    createdAt = null,
                    attachments = attachmentIds.mapIndexed { index, id ->
                        SessionTurnAttachment(
                            id = id,
                            position = index,
                            originalFilename = "fixture-$index.png",
                            contentType = "image/png",
                            sizeBytes = 8,
                            sha256 = "a".repeat(64),
                            downloadPath = "/api/sessions/19/attachments/$id/content"
                        )
                    }
                )
            )
        )
}

private class FakeAttachmentRemote : WorkSessionAttachmentRemote {
    val uploadKeys = mutableListOf<UUID>()
    val turnRequestIds = mutableListOf<UUID>()
    val turnAttachmentIds = mutableListOf<List<UUID>>()
    var failUploadCall: Int? = null
    var failFirstTurn: Boolean = false
    var turnConversation: MobileWorkSessionConversation? = null

    override suspend fun upload(
        sessionId: Long,
        idempotencyKey: UUID,
        fileName: String,
        contentType: String,
        bytes: ByteArray
    ): WorkSessionAttachment {
        uploadKeys += idempotencyKey
        if (failUploadCall == uploadKeys.size) throw IOException("response lost")
        return storedAttachment(UUID.fromString("10000000-0000-0000-0000-${uploadKeys.size.toString().padStart(12, '0')}"))
    }

    override suspend fun createTurn(
        sessionId: Long,
        message: String,
        clientRequestId: UUID,
        attachmentIds: List<UUID>
    ): MobileWorkSessionConversation {
        turnRequestIds += clientRequestId
        turnAttachmentIds += attachmentIds
        if (failFirstTurn) {
            failFirstTurn = false
            throw IOException("response lost")
        }
        return checkNotNull(turnConversation)
    }
}

class WorkSessionImageReaderTest {

    @Test
    fun `reader accepts png jpeg and webp by content not filename`() {
        val png = readValidatedWorkSessionImage(ByteArrayInputStream(pngBytes()), "fixture.bin", "image/png", null, 64)
        val jpeg = readValidatedWorkSessionImage(ByteArrayInputStream(jpegBytes()), "fixture.bin", "image/jpeg", null, 64)
        val webp = readValidatedWorkSessionImage(ByteArrayInputStream(webpBytes()), "fixture.bin", "image/webp", null, 64)

        assertEquals("image/png", png.contentType)
        assertEquals("image/jpeg", jpeg.contentType)
        assertEquals("image/webp", webp.contentType)
        assertContentEquals(pngBytes(), png.bytes)
    }

    @Test
    fun `reader rejects type mismatch empty and stream crossing the hard bound`() {
        assertFailsWith<ImageSelectionException> {
            readValidatedWorkSessionImage(ByteArrayInputStream(jpegBytes()), "fixture.png", "image/png", null, 64)
        }
        assertFailsWith<ImageSelectionException> {
            readValidatedWorkSessionImage(ByteArrayInputStream(byteArrayOf()), "fixture.png", "image/png", null, 64)
        }
        assertFailsWith<ImageSelectionException> {
            readValidatedWorkSessionImage(ByteArrayInputStream(pngBytes() + ByteArray(64)), "fixture.png", "image/png", null, 16)
        }
    }

    @Test
    fun `reader bounds long display name without changing bytes`() {
        val result = readValidatedWorkSessionImage(
            ByteArrayInputStream(pngBytes()),
            "x".repeat(300),
            "image/png",
            pngBytes().size.toLong(),
            64
        )

        assertEquals(160, result.displayName.length)
        assertNotNull(detectImageContentType(result.bytes))
    }
}

private fun readyCapability(maxCount: Int = 4, maxFileBytes: Long = 16L * 1024L * 1024L) =
    WorkSessionAttachmentCapability(
        state = WorkSessionAttachmentCapabilityState.READY,
        blockedReason = WorkSessionAttachmentBlockedReason.NONE,
        message = "Puedes adjuntar imágenes.",
        nextAction = "Selecciona hasta $maxCount imágenes.",
        policyRevision = "atenea-real-attachments-v1",
        workerCompatibility = WorkSessionAttachmentWorkerCompatibility.COMPATIBLE,
        acceptedContentTypes = SUPPORTED_IMAGE_TYPES.toList(),
        currentSessionBytes = 0,
        maxSessionBytes = 256L * 1024L * 1024L,
        remainingSessionBytes = 256L * 1024L * 1024L,
        maxFileBytes = maxFileBytes,
        maxAttachmentsPerTurn = maxCount,
        maxAttachmentBytesPerTurn = 32L * 1024L * 1024L
    )

private fun storedAttachment(id: UUID) = WorkSessionAttachment(
    id = id,
    workSessionId = 19,
    projectId = 1,
    agentRunId = null,
    source = "OPERATOR_UPLOAD",
    kind = "IMAGE",
    originalFilename = "fixture.png",
    contentType = "image/png",
    sizeBytes = 8,
    retentionClass = "SESSION",
    retainUntil = "2026-09-10T12:00:00Z",
    sha256 = "a".repeat(64),
    createdAt = "2026-08-11T12:00:00Z",
    indexedAt = "2026-08-11T12:00:00Z"
)

private fun pngImage(size: Int = 8) = LocalWorkSessionImage("fixture.png", "image/png", pngBytes().copyOf(size))

private fun pngBytes() = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private fun jpegBytes() = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
private fun webpBytes() = "RIFF0000WEBP".toByteArray()
private fun uuid(value: Int): UUID = UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
