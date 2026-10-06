package com.atenea.android.coreconsole

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atenea.android.api.MobileConversationTurn
import com.atenea.android.api.SessionTurnAttachment
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
internal fun ConversationSurface(
    title: String,
    status: String?,
    turns: List<MobileConversationTurn>,
    input: String,
    pending: Boolean,
    placeholder: String,
    recording: Boolean = false,
    audioLevels: List<Float> = emptyList(),
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicrophoneClick: (() -> Unit)? = null,
    onBack: () -> Unit,
    onOpenCore: () -> Unit,
    onRefresh: () -> Unit,
    error: String?,
    commandContent: @Composable (() -> Unit)? = null,
    runContent: @Composable (() -> Unit)? = null,
    profileContent: @Composable (() -> Unit)? = null,
    changeContent: @Composable (() -> Unit)? = null,
    changeNeedsAttention: Boolean = false,
    surfaceKey: String = "conversation",
    composerEnabled: Boolean = true,
    composerNotice: String? = null,
    composerInputLocked: Boolean = false,
    attachmentDraft: WorkSessionAttachmentDraft? = null,
    onAttachImages: (() -> Unit)? = null,
    onRemoveImage: ((UUID) -> Unit)? = null,
    onRetryImage: ((UUID) -> Unit)? = null,
    onResetAttachmentSubmission: (() -> Unit)? = null,
    onOpenHistoricalAttachment: ((SessionTurnAttachment) -> Unit)? = null
) {
    var pane by rememberSaveable(surfaceKey) { mutableStateOf(ConversationPane.CHAT) }
    var showAttachmentHelp by remember { mutableStateOf(false) }
    val transcriptState = rememberLazyListState()
    val userDragging by transcriptState.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    var followTranscript by rememberSaveable(surfaceKey) { mutableStateOf(true) }
    var userScrollPending by remember(surfaceKey) { mutableStateOf(false) }
    var lastSeenContent by rememberSaveable(surfaceKey) { mutableStateOf<String?>(null) }
    val latestContent = turns.lastOrNull()?.let { "${it.id}:${it.messageText.length}:${it.messageText.hashCode()}" }
    BackHandler(enabled = pane != ConversationPane.CHAT) { pane = ConversationPane.CHAT }
    ConversationSystemBars()
    ConversationTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ConversationColors.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .testTag("conversation-surface")
        ) {
            ConversationTopBar(
                title = if (pane == ConversationPane.CHAT) title else pane.title,
                status = if (pane == ConversationPane.CHAT) status else title,
                pending = pending,
                onBack = { if (pane == ConversationPane.CHAT) onBack() else pane = ConversationPane.CHAT },
                onOpenCore = onOpenCore,
                onRefresh = onRefresh,
                showChange = changeContent != null,
                changeNeedsAttention = changeNeedsAttention,
                onChange = { pane = if (pane == ConversationPane.CHANGE) ConversationPane.CHAT else ConversationPane.CHANGE },
                onActivity = runContent?.let { { pane = ConversationPane.ACTIVITY } },
                onProfile = profileContent?.let { { pane = ConversationPane.PROFILE } },
                onAttachments = attachmentDraft?.let { { showAttachmentHelp = true } }
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("conversation-content")
            ) {
                if (pane == ConversationPane.CHAT) {
                    LaunchedEffect(transcriptState) {
                        snapshotFlow { Triple(userDragging, transcriptState.isScrollInProgress, transcriptState.canScrollForward) }
                            .collect { (dragging, scrolling, canForward) ->
                                if (dragging) {
                                    userScrollPending = true
                                    followTranscript = false
                                } else if (userScrollPending && !scrolling) {
                                    followTranscript = !canForward
                                    userScrollPending = false
                                }
                            }
                    }
                    LaunchedEffect(latestContent, commandContent != null, error) {
                        if (followTranscript) {
                            val count = turns.size.coerceAtLeast(1) + (if (commandContent == null) 0 else 1) + (if (error == null) 0 else 1)
                            if (count > 0) transcriptState.scrollToItem(count - 1)
                            lastSeenContent = latestContent
                        }
                    }
                    LazyColumn(
                        state = transcriptState,
                        modifier = Modifier.fillMaxSize().testTag("conversation-transcript"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (turns.isEmpty()) {
                            item { Text("Escribe una instrucción para continuar.", color = ConversationColors.secondaryText) }
                        }
                        items(turns, key = { it.id }) { turn ->
                            SelectionContainer { ConversationTurn(turn, onOpenHistoricalAttachment) }
                        }
                        commandContent?.let { command -> item(key = "pending-command") { command() } }
                        error?.let { message -> item(key = "conversation-error") {
                            SelectionContainer { Text(message, color = ConversationColors.error) }
                        } }
                    }
                    if (!followTranscript && latestContent != lastSeenContent) {
                        TextButton(
                            modifier = Modifier.align(Alignment.BottomCenter).background(ConversationColors.composerBar),
                            onClick = { scope.launch {
                                followTranscript = true
                                val count = turns.size.coerceAtLeast(1) + (if (commandContent == null) 0 else 1) + (if (error == null) 0 else 1)
                                if (count > 0) transcriptState.scrollToItem(count - 1)
                                lastSeenContent = latestContent
                            } }
                        ) { Text("Ir a los mensajes nuevos") }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (pane) {
                            ConversationPane.CHANGE -> if (changeContent != null) changeContent()
                                else Text("No se ha podido cargar el cambio. Usa Actualizar en el menú.")
                            ConversationPane.ACTIVITY -> if (runContent != null) runContent()
                                else Text("No hay un detalle de ejecución disponible.")
                            ConversationPane.PROFILE -> if (profileContent != null) profileContent()
                                else Text("El perfil de ejecución no está disponible en esta sesión.")
                            ConversationPane.CHAT -> Unit
                        }
                        error?.let { message ->
                            SelectionContainer { Text(message, color = ConversationColors.error) }
                        }
                    }
                }
            }

            if (pane == ConversationPane.CHAT) {
                composerNotice?.let { notice ->
                    TextButton(onClick = { pane = ConversationPane.PROFILE }, enabled = profileContent != null) {
                        Text(notice, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ConversationTypography.meta)
                    }
                }
                ConversationComposer(
                    input = input,
                    pending = pending,
                    placeholder = placeholder,
                    recording = recording,
                    audioLevels = audioLevels,
                    enabled = composerEnabled,
                    onInputChange = onInputChange,
                    onSend = onSend,
                    onMicrophoneClick = onMicrophoneClick,
                    inputLocked = composerInputLocked,
                    attachmentDraft = attachmentDraft,
                    onAttachImages = onAttachImages,
                    onRemoveImage = onRemoveImage,
                    onRetryImage = onRetryImage,
                    onResetAttachmentSubmission = onResetAttachmentSubmission
                )
            }
        }
        if (showAttachmentHelp) {
            AlertDialog(
                onDismissRequest = { showAttachmentHelp = false },
                title = { Text(if (attachmentDraft?.isReady == true) "Adjuntar imágenes" else "Adjuntos no disponibles") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(attachmentDraft?.capabilityFailure?.message
                        ?: attachmentDraft?.capability?.message?.takeIf { it.isNotBlank() }
                        ?: "PNG, JPEG o WebP. Usa el clip para seleccionar imágenes.")
                    if (attachmentDraft?.isReady != true) Text("Puedes continuar en esta misma conversación sin adjuntos.")
                } },
                confirmButton = { TextButton(onClick = { showAttachmentHelp = false }) { Text("Entendido") } }
            )
        }
    }
}

@Composable
private fun ConversationTopBar(
    title: String,
    status: String?,
    pending: Boolean,
    onBack: () -> Unit,
    onOpenCore: () -> Unit,
    onRefresh: () -> Unit,
    showChange: Boolean,
    changeNeedsAttention: Boolean,
    onChange: () -> Unit,
    onActivity: (() -> Unit)?,
    onProfile: (() -> Unit)?,
    onAttachments: (() -> Unit)?
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("conversation-toolbar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver" }) {
            ConversationActionGlyph(ConversationActionIcon.Back, ConversationColors.primaryText)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            status?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = ConversationTypography.meta, color = ConversationColors.secondaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showChange) TextButton(
            onClick = onChange,
            modifier = Modifier.semantics {
                contentDescription = if (changeNeedsAttention) "Cambio, necesita atención" else "Cambio"
            }
        ) { Text(if (changeNeedsAttention) "Cambio !" else "Cambio") }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = "Opciones de conversación" }) {
                Text("⋮", style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                onProfile?.let { action -> DropdownMenuItem(text = { Text("Configuración de Codex") }, onClick = { menuOpen = false; action() }) }
                onActivity?.let { action -> DropdownMenuItem(text = { Text("Detalle de ejecución") }, onClick = { menuOpen = false; action() }) }
                onAttachments?.let { action -> DropdownMenuItem(text = { Text("Adjuntos") }, onClick = { menuOpen = false; action() }) }
                DropdownMenuItem(text = { Text(if (pending) "Actualizando…" else "Actualizar") }, enabled = !pending,
                    onClick = { menuOpen = false; onRefresh() })
                DropdownMenuItem(text = { Text("Abrir Core") }, onClick = { menuOpen = false; onOpenCore() })
            }
        }
    }
}

@Composable
private fun ConversationSystemBars() {
    val activity = LocalContext.current.findConversationActivity()
    DisposableEffect(activity) {
        val controller = activity?.let { WindowInsetsControllerCompat(it.window, it.window.decorView) }
        val statusLight = controller?.isAppearanceLightStatusBars
        val navigationLight = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            statusLight?.let { controller?.isAppearanceLightStatusBars = it }
            navigationLight?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
}

private tailrec fun Context.findConversationActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findConversationActivity()
    else -> null
}

@Composable
private fun ConversationTurn(
    turn: MobileConversationTurn,
    onOpenAttachment: ((SessionTurnAttachment) -> Unit)?
) {
    val operator = turn.actor == "OPERATOR"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RenderedConversationText(turn.messageText, operator)
        if (turn.attachments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                turn.attachments.sortedBy { it.position }.forEach { attachment ->
                    HistoricalAttachmentRow(attachment, onOpenAttachment)
                }
            }
        }
        turn.executionProfile?.let { profile ->
            Text(
                "${profile.modelId} · ${profile.reasoningEffort} · Codex ${profile.codexVersion}",
                style = ConversationTypography.meta,
                color = ConversationColors.action
            )
        }
        turn.createdAt?.let {
            Text(
                it.formatDateTimeForDisplay(),
                style = ConversationTypography.timestamp,
                color = ConversationColors.mutedText
            )
        }
    }
}

@Composable
private fun HistoricalAttachmentRow(
    attachment: SessionTurnAttachment,
    onOpenAttachment: ((SessionTurnAttachment) -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConversationColors.attachmentBackground, RoundedCornerShape(5.dp))
            .clickable(enabled = onOpenAttachment != null) { onOpenAttachment?.invoke(attachment) }
            .semantics { contentDescription = "Abrir imagen ${attachment.position + 1}" }
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AttachmentImageGlyph(ConversationColors.action)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                attachment.originalFilename,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = ConversationColors.primaryText,
                style = ConversationTypography.meta
            )
            Text(
                "Imagen ${attachment.position + 1} · ${formatAttachmentBytes(attachment.sizeBytes)}",
                color = ConversationColors.mutedText,
                style = ConversationTypography.timestamp
            )
        }
        Text("Abrir", color = ConversationColors.action, style = ConversationTypography.action)
    }
}

@Composable
private fun RenderedConversationText(text: String, operator: Boolean) {
    val blocks = remember(text) { text.toConversationBlocks() }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { block ->
            if (block.code) {
                ConversationCodeBlock(block.text)
            } else {
                RenderedParagraph(block.text, operator)
            }
        }
    }
}

@Composable
private fun ConversationCodeBlock(code: String) {
    val horizontalScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConversationColors.codeBackground, RoundedCornerShape(6.dp))
            .border(1.dp, ConversationColors.codeBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            code.trimEnd(),
            modifier = Modifier.horizontalScroll(horizontalScroll),
            color = ConversationColors.codeText,
            style = ConversationTypography.code
        )
    }
}

@Composable
private fun RenderedParagraph(text: String, operator: Boolean) {
    val lines = remember(text) { text.lines() }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        lines.forEachIndexed { index, line ->
            if (line.trim().isBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                return@forEachIndexed
            }

            val trimmed = line.trimStart()
            val headingLevel = when {
                trimmed.startsWith("### ") -> 3
                trimmed.startsWith("## ") -> 2
                trimmed.startsWith("# ") -> 1
                else -> 0
            }
            val quote = trimmed.startsWith("> ")
            val bullet = trimmed.matches(Regex("^[-*]\\s+.*"))
            val numbered = trimmed.matches(Regex("^\\d+\\.\\s+.*"))
            val content = when {
                headingLevel > 0 -> trimmed.replace(Regex("^#{1,3}\\s+"), "")
                quote -> trimmed.replace(Regex("^>\\s+"), "")
                bullet -> trimmed.replace(Regex("^[-*]\\s+"), "")
                numbered -> trimmed.replace(Regex("^\\d+\\.\\s+"), "")
                else -> line
            }
            val prefix = when {
                quote -> "> "
                bullet -> "- "
                numbered -> "${Regex("^(\\d+)\\.").find(trimmed)?.groupValues?.getOrNull(1).orEmpty()}. "
                else -> ""
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (headingLevel > 0 && index > 0) 4.dp else 0.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (prefix.isNotBlank()) {
                    Text(
                        prefix,
                        color = ConversationColors.action,
                        style = ConversationTypography.body
                    )
                }
                Text(
                    renderInlineMarkdown(content),
                    color = when {
                        headingLevel > 0 -> ConversationColors.action
                        quote -> ConversationColors.quoteText
                        operator -> ConversationColors.operatorText
                        else -> ConversationColors.primaryText
                    },
                    style = ConversationTypography.body.copy(
                        fontWeight = if (headingLevel > 0) FontWeight.Bold else FontWeight.Normal
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ConversationComposer(
    input: String,
    pending: Boolean,
    placeholder: String,
    recording: Boolean,
    audioLevels: List<Float>,
    enabled: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicrophoneClick: (() -> Unit)?,
    inputLocked: Boolean,
    attachmentDraft: WorkSessionAttachmentDraft?,
    onAttachImages: (() -> Unit)?,
    onRemoveImage: ((UUID) -> Unit)?,
    onRetryImage: ((UUID) -> Unit)?,
    onResetAttachmentSubmission: (() -> Unit)?
) {
    val hasInput = input.isNotBlank()
    val showSend = hasInput || recording
    val attachmentSendReady = attachmentDraft?.let { draft ->
        !draft.hasUploadInProgress && draft.images.all { it.status == PendingImageStatus.READY }
    } ?: true
    val actionEnabled = enabled && attachmentSendReady && !pending && (showSend || onMicrophoneClick != null)
    val actionBackground = if (actionEnabled) ConversationColors.sendBackground else ConversationColors.disabledAction
    val actionBorder = if (actionEnabled) ConversationColors.sendBorder else ConversationColors.secondaryBorder
    val actionIcon = if (actionEnabled) ConversationColors.sendText else ConversationColors.mutedText

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConversationColors.composerBar)
            .testTag("conversation-composer")
            .padding(horizontal = 0.dp, vertical = 6.dp)
    ) {
        attachmentDraft?.takeIf { it.images.isNotEmpty() || it.isSubmissionLocked }?.let { draft ->
            AttachmentComposerState(
                draft = draft,
                onRemoveImage = onRemoveImage,
                onRetryImage = onRetryImage,
                onResetSubmission = onResetAttachmentSubmission
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 156.dp)
                    .testTag("conversation-input")
                    .background(ConversationColors.composerField)
                    .padding(
                        start = if (attachmentDraft != null) 54.dp else 10.dp,
                        top = 12.dp,
                        end = 60.dp,
                        bottom = 14.dp
                    ),
                enabled = enabled && !inputLocked && !pending && !recording,
                minLines = 1,
                maxLines = 5,
                textStyle = ConversationTypography.input.copy(color = ConversationColors.primaryText),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (recording) {
                            RecordingWaveform(
                                levels = audioLevels,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .padding(end = 50.dp)
                            )
                        } else if (input.isBlank()) {
                            Text(
                                placeholder,
                                color = ConversationColors.placeholder,
                                style = ConversationTypography.input
                            )
                        }
                        inner()
                    }
                }
            )
            if (attachmentDraft != null) {
                val attachEnabled = enabled && !pending && attachmentDraft.canAddImage()
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 4.dp, bottom = 8.dp)
                        .size(48.dp)
                        .border(1.dp, ConversationColors.secondaryBorder, CircleShape)
                        .clickable(enabled = attachEnabled) { onAttachImages?.invoke() }
                        .semantics { contentDescription = "Adjuntar imágenes" },
                    contentAlignment = Alignment.Center
                ) {
                    PaperclipIcon(if (attachEnabled) ConversationColors.action else ConversationColors.mutedText)
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 8.dp)
                    .size(48.dp)
                    .border(1.dp, actionBorder, CircleShape)
                    .background(actionBackground, CircleShape)
                    .clickable(enabled = actionEnabled) {
                        if (showSend) onSend() else onMicrophoneClick?.invoke()
                    }
                    .semantics { contentDescription = if (showSend) "Enviar" else "Dictar" },
                contentAlignment = Alignment.Center
            ) {
                if (showSend) SendUpIcon(color = actionIcon)
                else MicrophoneIcon(color = actionIcon)
            }
        }
    }
}

@Composable
private fun AttachmentComposerState(
    draft: WorkSessionAttachmentDraft,
    onRemoveImage: ((UUID) -> Unit)?,
    onRetryImage: ((UUID) -> Unit)?,
    onResetSubmission: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        val capability = draft.capability
        val headline = when {
            draft.capabilityLoading -> "Imágenes · comprobando disponibilidad"
            draft.isReady -> "Imágenes · ${draft.images.size}/${capability?.maxAttachmentsPerTurn ?: 4} seleccionadas"
            else -> "Imágenes no disponibles"
        }
        Text(
            headline,
            color = if (draft.isReady) ConversationColors.action else ConversationColors.secondaryText,
            style = ConversationTypography.meta.copy(fontWeight = FontWeight.SemiBold)
        )
        when {
            draft.capabilityFailure != null -> AttachmentActionMessage(draft.capabilityFailure.message, draft.capabilityFailure.category)
            !draft.isReady && capability != null -> {
                Text(
                    capability.message.ifBlank { "Esta sesión no admite imágenes." },
                    color = ConversationColors.secondaryText,
                    style = ConversationTypography.meta,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                capability.nextAction.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = ConversationColors.mutedText, style = ConversationTypography.timestamp, maxLines = 2)
                }
            }
            draft.isReady && draft.images.isEmpty() -> Text(
                "PNG, JPEG o WebP · hasta ${capability?.maxAttachmentsPerTurn ?: 4} · ${formatAttachmentBytes(capability?.maxAttachmentBytesPerTurn ?: 0)} por turno",
                color = ConversationColors.mutedText,
                style = ConversationTypography.timestamp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (draft.images.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                draft.images.forEach { image ->
                    PendingImageChip(image, draft.isSubmissionLocked, onRemoveImage, onRetryImage)
                }
            }
        }
        draft.submissionFailure?.let { failure ->
            AttachmentActionMessage(failure.message, failure.category)
        }
        if (draft.isSubmissionLocked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Envío pendiente: reintenta exactamente.",
                    modifier = Modifier.weight(1f),
                    color = ConversationColors.warning,
                    style = ConversationTypography.meta
                )
                Text(
                    "Editar borrador",
                    modifier = Modifier.clickable { onResetSubmission?.invoke() },
                    color = ConversationColors.action,
                    style = ConversationTypography.action
                )
            }
        }
    }
}

@Composable
private fun PendingImageChip(
    image: PendingWorkSessionImage,
    locked: Boolean,
    onRemoveImage: ((UUID) -> Unit)?,
    onRetryImage: ((UUID) -> Unit)?
) {
    Row(
        modifier = Modifier
            .widthIn(min = 170.dp, max = 220.dp)
            .background(ConversationColors.attachmentBackground, RoundedCornerShape(5.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val preview = image.preview
        if (preview != null && !preview.isRecycled) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(34.dp)
            )
        } else {
            Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                AttachmentImageGlyph(ConversationColors.action)
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                image.displayName,
                color = ConversationColors.primaryText,
                style = ConversationTypography.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when (image.status) {
                    PendingImageStatus.SELECTED -> "Preparando"
                    PendingImageStatus.UPLOADING -> "Subiendo"
                    PendingImageStatus.READY -> "Lista · ${formatAttachmentBytes(image.sizeBytes)}"
                    PendingImageStatus.ERROR -> image.failure?.message ?: "Error de subida"
                },
                color = if (image.status == PendingImageStatus.ERROR) ConversationColors.error else ConversationColors.mutedText,
                style = ConversationTypography.timestamp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!locked) {
            val retryable = image.status == PendingImageStatus.ERROR && image.failure?.retryable == true
            Text(
                if (retryable) "Reintentar" else "Quitar",
                modifier = Modifier
                    .clickable {
                        if (retryable) onRetryImage?.invoke(image.localId) else onRemoveImage?.invoke(image.localId)
                    }
                    .padding(4.dp),
                color = ConversationColors.action,
                style = ConversationTypography.action
            )
        }
    }
}

@Composable
private fun AttachmentActionMessage(message: String, category: AttachmentFailureCategory) {
    Text(
        "${attachmentFailureLabel(category)} · $message",
        color = if (category == AttachmentFailureCategory.TRANSPORT) ConversationColors.warning else ConversationColors.error,
        style = ConversationTypography.meta,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

private fun attachmentFailureLabel(category: AttachmentFailureCategory): String = when (category) {
    AttachmentFailureCategory.TRANSPORT -> "Conexión"
    AttachmentFailureCategory.CAPACITY -> "Límite"
    AttachmentFailureCategory.VALIDATION -> "Validación"
    AttachmentFailureCategory.POLICY -> "Política"
    AttachmentFailureCategory.OWNERSHIP -> "Propiedad"
    AttachmentFailureCategory.AUTHORIZATION -> "Permisos"
    AttachmentFailureCategory.CONFLICT -> "Conflicto"
    AttachmentFailureCategory.UNKNOWN -> "Error"
}

@Composable
private fun RecordingWaveform(
    levels: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val bars = levels.takeLast(34).ifEmpty { List(18) { 0.08f } }
        val gap = 3.dp.toPx()
        val barWidth = 2.dp.toPx()
        val totalWidth = bars.size * barWidth + (bars.size - 1) * gap
        val left = ((size.width - totalWidth).coerceAtLeast(0f)) / 2f
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.88f
        bars.forEachIndexed { index, rawLevel ->
            val level = rawLevel.coerceIn(0.02f, 1f)
            val barHeight = (maxBarHeight * level).coerceAtLeast(4.dp.toPx())
            val x = left + index * (barWidth + gap)
            drawLine(
                color = ConversationColors.action,
                start = Offset(x, centerY - barHeight / 2f),
                end = Offset(x, centerY + barHeight / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

private enum class ConversationActionIcon {
    Back,
    Core,
    Refresh
}

@Composable
private fun ConversationActionGlyph(icon: ConversationActionIcon, color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        fun p(x: Float, y: Float) = Offset(size.width * x / 24f, size.height * y / 24f)
        when (icon) {
            ConversationActionIcon.Back -> {
                drawLine(color, p(18f, 12f), p(6f, 12f), strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, p(6f, 12f), p(10.5f, 7.5f), strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, p(6f, 12f), p(10.5f, 16.5f), strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            ConversationActionIcon.Core -> {
                val path = Path().apply {
                    moveTo(p(12f, 4f).x, p(12f, 4f).y)
                    lineTo(p(13.8f, 8.2f).x, p(13.8f, 8.2f).y)
                    lineTo(p(18f, 10f).x, p(18f, 10f).y)
                    lineTo(p(13.8f, 11.8f).x, p(13.8f, 11.8f).y)
                    lineTo(p(12f, 16f).x, p(12f, 16f).y)
                    lineTo(p(10.2f, 11.8f).x, p(10.2f, 11.8f).y)
                    lineTo(p(6f, 10f).x, p(6f, 10f).y)
                    lineTo(p(10.2f, 8.2f).x, p(10.2f, 8.2f).y)
                    close()
                }
                drawPath(path, color, style = Stroke(strokeWidth))
                val smallPath = Path().apply {
                    moveTo(p(18.5f, 4.5f).x, p(18.5f, 4.5f).y)
                    lineTo(p(19.2f, 6.2f).x, p(19.2f, 6.2f).y)
                    lineTo(p(21f, 7f).x, p(21f, 7f).y)
                    lineTo(p(19.2f, 7.7f).x, p(19.2f, 7.7f).y)
                    lineTo(p(18.5f, 9.5f).x, p(18.5f, 9.5f).y)
                    lineTo(p(17.7f, 7.7f).x, p(17.7f, 7.7f).y)
                    lineTo(p(16f, 7f).x, p(16f, 7f).y)
                    lineTo(p(17.7f, 6.2f).x, p(17.7f, 6.2f).y)
                    close()
                }
                drawPath(smallPath, color, style = Stroke(strokeWidth))
            }
            ConversationActionIcon.Refresh -> {
                drawArc(
                    color = color,
                    startAngle = 205f,
                    sweepAngle = 215f,
                    useCenter = false,
                    style = Stroke(strokeWidth)
                )
                drawLine(color, p(6f, 8f), p(6f, 5f), strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, p(6f, 5f), p(9f, 5f), strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawArc(
                    color = color,
                    startAngle = 25f,
                    sweepAngle = 215f,
                    useCenter = false,
                    style = Stroke(strokeWidth)
                )
                drawLine(color, p(18f, 16f), p(18f, 19f), strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, p(18f, 19f), p(15f, 19f), strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun SendUpIcon(color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val strokeWidth = 2.0.dp.toPx()
        drawLine(
            color,
            Offset(size.width * 0.5f, size.height * 0.78f),
            Offset(size.width * 0.5f, size.height * 0.22f),
            strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            color,
            Offset(size.width * 0.5f, size.height * 0.22f),
            Offset(size.width * 0.30f, size.height * 0.42f),
            strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            color,
            Offset(size.width * 0.5f, size.height * 0.22f),
            Offset(size.width * 0.70f, size.height * 0.42f),
            strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@Composable
private fun MicrophoneIcon(color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val strokeWidth = 2.0.dp.toPx()
        val centerX = size.width * 0.5f
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.37f, size.height * 0.14f),
            size = Size(size.width * 0.26f, size.height * 0.48f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.13f, size.width * 0.13f),
            style = Stroke(strokeWidth)
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.24f, size.height * 0.38f),
            size = Size(size.width * 0.52f, size.height * 0.32f),
            style = Stroke(strokeWidth, cap = StrokeCap.Round)
        )
        drawLine(
            color,
            Offset(centerX, size.height * 0.70f),
            Offset(centerX, size.height * 0.86f),
            strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color,
            Offset(size.width * 0.35f, size.height * 0.86f),
            Offset(size.width * 0.65f, size.height * 0.86f),
            strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun PaperclipIcon(color: Color) {
    Canvas(modifier = Modifier.size(21.dp)) {
        val stroke = 1.8.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.68f, size.height * 0.26f)
            cubicTo(
                size.width * 0.84f, size.height * 0.42f,
                size.width * 0.82f, size.height * 0.62f,
                size.width * 0.66f, size.height * 0.77f
            )
            lineTo(size.width * 0.38f, size.height * 0.77f)
            cubicTo(
                size.width * 0.20f, size.height * 0.77f,
                size.width * 0.16f, size.height * 0.54f,
                size.width * 0.30f, size.height * 0.40f
            )
            lineTo(size.width * 0.56f, size.height * 0.20f)
        }
        drawPath(path, color = color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun AttachmentImageGlyph(color: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.08f, size.height * 0.12f),
            size = Size(size.width * 0.84f, size.height * 0.76f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = Stroke(stroke)
        )
        drawCircle(color, radius = size.width * 0.08f, center = Offset(size.width * 0.68f, size.height * 0.34f))
        val landscape = Path().apply {
            moveTo(size.width * 0.16f, size.height * 0.73f)
            lineTo(size.width * 0.38f, size.height * 0.48f)
            lineTo(size.width * 0.53f, size.height * 0.63f)
            lineTo(size.width * 0.65f, size.height * 0.51f)
            lineTo(size.width * 0.85f, size.height * 0.73f)
        }
        drawPath(landscape, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

private data class ConversationBlock(val text: String, val code: Boolean)

private fun List<MobileConversationTurn>.toFormattedConversationTranscript(): AnnotatedString =
    buildAnnotatedString {
        this@toFormattedConversationTranscript.forEachIndexed { index, turn ->
            if (index > 0) {
                append("\n\n")
                withStyle(SpanStyle(color = ConversationColors.divider, fontSize = 11.sp)) {
                    append("------------------------")
                }
                append("\n\n")
            }
            withStyle(
                SpanStyle(
                    color = ConversationColors.action,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            ) {
                append(if (turn.actor == "OPERATOR") "OPERADOR" else "CODEX")
                val createdAt = turn.createdAt
                if (createdAt != null) {
                    append(" · ")
                    append(createdAt.formatDateTimeForDisplay())
                }
            }
            append("\n\n")

            turn.messageText.toConversationBlocks().forEachIndexed { blockIndex, block ->
                if (blockIndex > 0) {
                    append("\n")
                }
                if (block.code) {
                    appendCodeBlock(block.text)
                } else {
                    appendMarkdownParagraph(block.text)
                }
            }
        }
    }

private fun AnnotatedString.Builder.appendMarkdownParagraph(text: String) {
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (line.trim().isBlank()) {
            append("\n")
            return@forEach
        }

        val trimmed = line.trimStart()
        val headingLevel = when {
            trimmed.startsWith("### ") -> 3
            trimmed.startsWith("## ") -> 2
            trimmed.startsWith("# ") -> 1
            else -> 0
        }
        val quote = trimmed.startsWith("> ")
        val bullet = trimmed.matches(Regex("^[-*]\\s+.*"))
        val numbered = trimmed.matches(Regex("^\\d+\\.\\s+.*"))
        val content = when {
            headingLevel > 0 -> trimmed.replace(Regex("^#{1,3}\\s+"), "")
            quote -> trimmed.replace(Regex("^>\\s+"), "")
            bullet -> trimmed.replace(Regex("^[-*]\\s+"), "")
            numbered -> trimmed.replace(Regex("^\\d+\\.\\s+"), "")
            else -> line
        }
        val prefix = when {
            quote -> "> "
            bullet -> "- "
            numbered -> "${Regex("^(\\d+)\\.").find(trimmed)?.groupValues?.getOrNull(1).orEmpty()}. "
            else -> ""
        }
        val lineStart = length
        if (prefix.isNotBlank()) {
            withStyle(SpanStyle(color = ConversationColors.action, fontWeight = FontWeight.Bold)) {
                append(prefix)
            }
        }
        appendInlineMarkdownSpans(
            content,
            baseStyle = SpanStyle(
                color = when {
                    headingLevel > 0 -> ConversationColors.action
                    quote -> ConversationColors.quoteText
                    else -> ConversationColors.primaryText
                },
                fontWeight = if (headingLevel > 0) FontWeight.Bold else null,
                fontSize = when (headingLevel) {
                    1 -> 16.sp
                    2 -> 15.sp
                    3 -> 14.sp
                    else -> 13.sp
                }
            )
        )
        val lineEnd = length
        if (quote || bullet || numbered) {
            addStyle(SpanStyle(color = if (quote) ConversationColors.quoteText else ConversationColors.primaryText), lineStart, lineEnd)
        }
        append("\n")
    }
}

private fun AnnotatedString.Builder.appendCodeBlock(code: String) {
    withStyle(
        SpanStyle(
            color = ConversationColors.codeText,
            background = ConversationColors.codeBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    ) {
        code.trimEnd().lineSequence().forEach { line ->
            append("  ")
            append(line)
            append("\n")
        }
    }
    append("\n")
}

private fun AnnotatedString.Builder.appendInlineMarkdownSpans(text: String, baseStyle: SpanStyle) {
    val regex = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`|\\[[^\\]]+\\]\\([^)]+\\))")
    var cursor = 0
    regex.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            withStyle(baseStyle) {
                append(text.substring(cursor, match.range.first))
            }
        }
        val value = match.value
        when {
            value.startsWith("**") && value.endsWith("**") -> withStyle(baseStyle.copy(fontWeight = FontWeight.Bold)) {
                append(value.removePrefix("**").removeSuffix("**"))
            }
            value.startsWith("`") && value.endsWith("`") -> withStyle(
                baseStyle.copy(
                    color = ConversationColors.action,
                    background = ConversationColors.codeBackground,
                    fontFamily = FontFamily.Monospace
                )
            ) {
                append(value.removePrefix("`").removeSuffix("`"))
            }
            value.startsWith("[") -> {
                val link = Regex("^\\[([^\\]]+)]\\(([^)]+)\\)").find(value)
                val label = link?.groupValues?.getOrNull(1).orEmpty()
                val target = link?.groupValues?.getOrNull(2).orEmpty()
                withStyle(baseStyle.copy(color = ConversationColors.action, fontWeight = FontWeight.Bold)) {
                    append(label.ifBlank { value })
                }
                if (target.isNotBlank()) {
                    withStyle(baseStyle.copy(color = ConversationColors.mutedText)) {
                        append(" ($target)")
                    }
                }
            }
            else -> withStyle(baseStyle) {
                append(value)
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        withStyle(baseStyle) {
            append(text.substring(cursor))
        }
    }
}

private fun String.toConversationBlocks(): List<ConversationBlock> {
    val result = mutableListOf<ConversationBlock>()
    var cursor = 0
    while (cursor < length) {
        val opening = indexOf("```", startIndex = cursor)
        if (opening < 0) {
            val plain = substring(cursor).trimEnd()
            if (plain.isNotBlank()) {
                result += ConversationBlock(plain.normalizeConversationText(), false)
            }
            break
        }
        if (opening > cursor) {
            val plain = substring(cursor, opening).trimEnd()
            if (plain.isNotBlank()) {
                result += ConversationBlock(plain.normalizeConversationText(), false)
            }
        }
        val contentStart = opening + 3
        val closing = indexOf("```", startIndex = contentStart)
        if (closing < 0) {
            val plain = substring(opening).trimEnd()
            if (plain.isNotBlank()) {
                result += ConversationBlock(plain.normalizeConversationText(), false)
            }
            break
        }
        val code = substring(contentStart, closing).toCodeBlockText()
        if (code.isNotBlank()) {
            result += ConversationBlock(code, true)
        }
        cursor = closing + 3
    }
    return result.ifEmpty { listOf(ConversationBlock(trim(), false)) }
}

private fun String.toCodeBlockText(): String {
    val cleaned = trim('\r', '\n', ' ', '\t')
    if (cleaned.isBlank()) {
        return ""
    }
    val firstLine = cleaned.lineSequence().firstOrNull().orEmpty().trim()
    val rest = cleaned.substringAfter('\n', missingDelimiterValue = "").trim('\r', '\n')
    if (firstLine in CODE_LANGUAGES) {
        return rest
    }
    CODE_LANGUAGES.forEach { language ->
        val prefix = "$language "
        if (cleaned.startsWith(prefix)) {
            return cleaned.removePrefix(prefix).trim()
        }
        if (language in GLUED_CODE_LANGUAGES && cleaned.length > language.length && cleaned.startsWith(language)) {
            val next = cleaned[language.length]
            if (next.isLetterOrDigit() || next == '/' || next == '.' || next == '_' || next == '-') {
                return cleaned.substring(language.length).trim()
            }
        }
    }
    return cleaned
}

private fun String.normalizeConversationText(): String =
    replace("\r\n", "\n")
        .replace(Regex("```(bash|text|json|js|ts|tsx|html|css|sh|sql|xml|yaml|yml)(?!\\n)"), "```$1\n")
        .replace(Regex("(^|\\n)(#{1,3}\\s+[^\\n#]*?)([a-záéíóúñ])([A-ZÁÉÍÓÚÑ])"), "$1$2$3\n$4")
        .replace(Regex("([A-Za-zÁÉÍÓÚÑáéíóúñ])\\s+(\\d+[.)]\\s+)"), "$1\n$2")
        .replace(Regex("(:\\s+)(\\d+[.)]\\s+)"), "$1\n$2")
        .replace(Regex("([^\\n])\\s*(\\d+[.)]\\s+)(?=[A-Za-zÁÉÍÓÚÑáéíóúñ])"), "$1\n$2")
        .replace(Regex("([^\\n])\\s+([-*]\\s+)"), "$1\n$2")
        .replace(Regex("([a-záéíóúñ])((?:Si|El|La|Los|Las|Un|Una|Este|Esta|Esto|Estado|Ahora|Además|También|Pero|Como|Cuando|Puedo|Puedes|Recomiendo|Confirmo|Confirmó|Comando|Resultado)\\s+)"), "$1\n$2")
        .replace(Regex("([^\\n])(-\\s+)"), "$1\n$2")

private fun renderInlineMarkdown(text: String) = buildAnnotatedString {
    val regex = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`|\\[[^\\]]+\\]\\([^)]+\\))")
    var cursor = 0
    regex.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val value = match.value
        when {
            value.startsWith("**") && value.endsWith("**") -> withStyle(
                SpanStyle(color = ConversationColors.action, fontWeight = FontWeight.Bold)
            ) {
                append(value.removePrefix("**").removeSuffix("**"))
            }
            value.startsWith("`") && value.endsWith("`") -> withStyle(
                SpanStyle(color = ConversationColors.action, fontFamily = FontFamily.Monospace)
            ) {
                append(value.removePrefix("`").removeSuffix("`"))
            }
            value.startsWith("[") -> {
                val match = Regex("^\\[([^\\]]+)]\\(([^)]+)\\)").find(value)
                val label = match?.groupValues?.getOrNull(1).orEmpty()
                val target = match?.groupValues?.getOrNull(2).orEmpty()
                withStyle(SpanStyle(color = ConversationColors.action)) {
                    append(label.ifBlank { value })
                }
                if (target.isNotBlank()) {
                    append("($target)")
                }
            }
            else -> append(value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

internal object ConversationTypography {
    val body = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
    val code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp
    )
    val meta = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp
    )
    val timestamp = TextStyle(
        fontSize = 10.sp,
        lineHeight = 15.sp
    )
    val action = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    )
    val input = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
}

internal object ConversationColors {
    val background = Color(0xFF151918)
    val composerBar = Color(0xFF1C2220)
    val composerField = Color(0xFF222A27)
    val divider = Color(0xFFF0F0F0)
    val primaryText = Color(0xFFE7ECE9)
    val secondaryText = Color(0xFFB0BAB6)
    val mutedText = Color(0xFFA2ADA7)
    val placeholder = Color(0xFFABB8B1)
    val action = Color(0xFF66D8C6)
    val disabledAction = Color(0xFF16211F)
    val sendBackground = Color(0xFF253433)
    val sendBorder = Color(0xFFF1F5F3)
    val sendText = Color(0xFFFFFFFF)
    val operator = Color(0xFF179489)
    val operatorText = Color(0xFFE7ECE9)
    val codeBackground = Color(0xFF2F3331)
    val codeBorder = Color(0xFF48504C)
    val codeText = Color(0xFFD4E4DD)
    val messageDivider = Color(0xFF5E6763)
    val attachmentBackground = Color(0xFF303936)
    val secondaryBorder = Color(0xFF67736E)
    val quoteText = Color(0xFFA9D8BC)
    val error = Color(0xFFFFB4A9)
    val warning = Color(0xFFFFD28A)
}

private val CODE_LANGUAGES = setOf(
    "bash",
    "text",
    "json",
    "js",
    "ts",
    "tsx",
    "html",
    "css",
    "sh",
    "sql",
    "xml",
    "yaml",
    "yml"
)

private val GLUED_CODE_LANGUAGES = setOf("bash", "sh")
