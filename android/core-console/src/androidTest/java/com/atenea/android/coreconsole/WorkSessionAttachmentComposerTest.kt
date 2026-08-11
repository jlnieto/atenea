package com.atenea.android.coreconsole

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.atenea.android.api.MobileConversationTurn
import com.atenea.android.api.SessionTurnAttachment
import java.util.UUID
import org.junit.Rule
import org.junit.Test

class WorkSessionAttachmentComposerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun readyStateShowsSecondaryAttachAndOneEnabledSendAction() {
        compose.setContent {
            ConversationSurface(
                title = "Fixture",
                status = "OPEN",
                turns = emptyList(),
                input = "Describe esta captura",
                pending = false,
                placeholder = "Escribe una instrucción",
                onInputChange = {},
                onSend = {},
                onBack = {},
                onOpenCore = {},
                onRefresh = {},
                error = null,
                attachmentDraft = WorkSessionAttachmentDraft(
                    capability = readyCapabilityFixture(),
                    capabilityLoading = false
                ),
                onAttachImages = {}
            )
        }

        compose.onNodeWithText("Imágenes · 0/4 seleccionadas").assertIsDisplayed()
        compose.onNodeWithContentDescription("Adjuntar imágenes").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Enviar").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun blockedStateShowsReasonAndNextActionAndDisablesAttach() {
        compose.setContent {
            ConversationSurface(
                title = "Fixture",
                status = "OPEN",
                turns = emptyList(),
                input = "Mensaje",
                pending = false,
                placeholder = "Escribe una instrucción",
                onInputChange = {},
                onSend = {},
                onBack = {},
                onOpenCore = {},
                onRefresh = {},
                error = null,
                attachmentDraft = WorkSessionAttachmentDraft(
                    capability = blockedCapabilityFixture(),
                    capabilityLoading = false
                ),
                onAttachImages = {}
            )
        }

        compose.onNodeWithText("Imágenes no disponibles").assertIsDisplayed()
        compose.onNodeWithText("Esta WorkSession se creó antes de activar imágenes.").assertIsDisplayed()
        compose.onNodeWithText("Abre una WorkSession nueva de Atenea.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Adjuntar imágenes").assertIsNotEnabled()
    }

    @Test
    fun historicalAttachmentIsRenderedOnlyOnItsTurnAndIsActionable() {
        val attachment = historicalAttachmentFixture()
        compose.setContent {
            ConversationSurface(
                title = "Fixture",
                status = "OPEN",
                turns = listOf(
                    MobileConversationTurn(
                        id = 1,
                        actor = "OPERATOR",
                        messageText = "Primera instrucción",
                        createdAt = null,
                        attachments = listOf(attachment)
                    ),
                    MobileConversationTurn(
                        id = 2,
                        actor = "AGENT",
                        messageText = "Respuesta posterior sin adjuntos",
                        createdAt = null
                    )
                ),
                input = "",
                pending = false,
                placeholder = "Escribe una instrucción",
                onInputChange = {},
                onSend = {},
                onBack = {},
                onOpenCore = {},
                onRefresh = {},
                error = null,
                attachmentDraft = WorkSessionAttachmentDraft(
                    capability = readyCapabilityFixture(),
                    capabilityLoading = false
                ),
                onOpenHistoricalAttachment = {}
            )
        }

        compose.onNodeWithText("fixture-history.png").assertIsDisplayed()
        compose.onNodeWithText("Respuesta posterior sin adjuntos").assertIsDisplayed()
        compose.onNodeWithContentDescription("Abrir imagen 1").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun uploadingRemainsVisibleAndDisablesSend() {
        val localId = UUID.fromString("00000000-0000-0000-0000-000000000041")
        val uploading = PendingWorkSessionImage(
            localId = localId,
            uploadRequestId = UUID.fromString("00000000-0000-0000-0000-000000000042"),
            displayName = "fixture-uploading.png",
            contentType = "image/png",
            sizeBytes = 1024,
            status = PendingImageStatus.UPLOADING
        )
        compose.setContent {
            ConversationSurface(
                title = "Fixture",
                status = "OPEN",
                turns = emptyList(),
                input = "Mensaje con imagen",
                pending = false,
                placeholder = "Escribe una instrucción",
                onInputChange = {},
                onSend = {},
                onBack = {},
                onOpenCore = {},
                onRefresh = {},
                error = null,
                attachmentDraft = WorkSessionAttachmentDraft(
                    capability = readyCapabilityFixture(),
                    capabilityLoading = false,
                    images = listOf(uploading)
                )
            )
        }
        compose.onNodeWithText("Subiendo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enviar").assertIsNotEnabled()
    }

    @Test
    fun retryableLongNameErrorIsActionableWithoutOverflow() {
        val errorImage = PendingWorkSessionImage(
            localId = UUID.fromString("00000000-0000-0000-0000-000000000041"),
            uploadRequestId = UUID.fromString("00000000-0000-0000-0000-000000000042"),
            displayName = "nombre-extremadamente-largo-para-validar-overflow-y-recorte-en-movil.png",
            contentType = "image/png",
            sizeBytes = 1024,
            status = PendingImageStatus.ERROR,
            failure = AttachmentFailure(
                AttachmentFailureCategory.TRANSPORT,
                "No se pudo comunicar con Atenea. Reintenta sin cambiar la selección.",
                true
            )
        )
        compose.setContent {
            ConversationSurface(
                title = "Fixture",
                status = "OPEN",
                turns = emptyList(),
                input = "Mensaje con imagen",
                pending = false,
                placeholder = "Escribe una instrucción",
                onInputChange = {},
                onSend = {},
                onBack = {},
                onOpenCore = {},
                onRefresh = {},
                error = null,
                attachmentDraft = WorkSessionAttachmentDraft(
                    capability = readyCapabilityFixture(),
                    capabilityLoading = false,
                    images = listOf(errorImage)
                ),
                onRetryImage = {}
            )
        }
        compose.onNodeWithText("Reintentar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enviar").assertIsNotEnabled()
    }
}
