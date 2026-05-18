package com.atenea.android.coreconsole

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atenea.android.api.MobileConversationTurn
import kotlinx.coroutines.delay

@Composable
internal fun ConversationSurface(
    title: String,
    status: String?,
    turns: List<MobileConversationTurn>,
    input: String,
    pending: Boolean,
    placeholder: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onOpenCore: () -> Unit,
    onRefresh: () -> Unit,
    error: String?,
    commandContent: @Composable (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    val latestTurnSignature = turns.lastOrNull()?.let { "${it.id}:${it.messageText.length}" }.orEmpty()

    LaunchedEffect(latestTurnSignature, turns.size, error) {
        if (turns.isNotEmpty()) {
            delay(80)
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ConversationColors.background)
    ) {
        ConversationTopBar(
            pending = pending,
            onBack = onBack,
            onOpenCore = onOpenCore,
            onRefresh = onRefresh
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            error?.let {
                Text(
                    it,
                    modifier = Modifier.padding(bottom = 10.dp),
                    color = ConversationColors.error,
                    style = ConversationTypography.meta
                )
            }
            commandContent?.invoke()
            if (turns.isEmpty()) {
                Text(
                    "Sin mensajes visibles todavia.",
                    modifier = Modifier.padding(vertical = 18.dp),
                    color = ConversationColors.secondaryText,
                    style = ConversationTypography.body
                )
            }
            turns.forEachIndexed { index, turn ->
                Column {
                    if (index > 0) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(ConversationColors.divider)
                        )
                    }
                    ConversationTurn(turn)
                }
            }
        }

        ConversationComposer(
            input = input,
            pending = pending,
            placeholder = placeholder,
            onInputChange = onInputChange,
            onSend = onSend
        )
    }
}

@Composable
private fun ConversationTopBar(
    pending: Boolean,
    onBack: () -> Unit,
    onOpenCore: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConversationColors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConversationTextAction(
                "Volver a sesión",
                ConversationActionIcon.Back,
                onBack,
                modifier = Modifier.weight(1f)
            )
            ConversationTextAction(
                "Abrir Core",
                ConversationActionIcon.Core,
                onOpenCore,
                modifier = Modifier.weight(1f),
                alignCenter = true
            )
            ConversationTextAction(
                if (pending) "Actualizando" else "Actualizar",
                ConversationActionIcon.Refresh,
                onRefresh,
                enabled = !pending,
                modifier = Modifier.weight(1f),
                alignEnd = true
            )
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ConversationColors.divider)
        )
    }
}

@Composable
private fun ConversationTextAction(
    text: String,
    icon: ConversationActionIcon,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    alignCenter: Boolean = false,
    alignEnd: Boolean = false
) {
    val color = if (enabled) ConversationColors.action else ConversationColors.mutedText
    Row(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = when {
            alignCenter -> Arrangement.Center
            alignEnd -> Arrangement.End
            else -> Arrangement.Start
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConversationActionGlyph(icon, color)
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            color = color,
            style = ConversationTypography.action,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
        )
    }
}

@Composable
private fun ConversationTurn(turn: MobileConversationTurn) {
    val operator = turn.actor == "OPERATOR"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RenderedConversationText(turn.messageText, operator)
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
private fun RenderedConversationText(text: String, operator: Boolean) {
    val blocks = remember(text) { text.toConversationBlocks() }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { block ->
            if (block.code) {
                Text(
                    block.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ConversationColors.codeBackground)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    color = ConversationColors.codeText,
                    style = ConversationTypography.code
                )
            } else {
                RenderedParagraph(block.text, operator)
            }
        }
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
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConversationColors.composerBar)
            .padding(horizontal = 0.dp, vertical = 6.dp)
    ) {
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 156.dp)
                .background(ConversationColors.composerField)
                .padding(start = 10.dp, top = 12.dp, end = 60.dp, bottom = 14.dp),
            enabled = !pending,
            minLines = 1,
            maxLines = 5,
            textStyle = ConversationTypography.input.copy(color = ConversationColors.primaryText),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (input.isBlank()) {
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
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 4.dp, bottom = 8.dp)
                .size(40.dp)
                .border(1.dp, ConversationColors.sendBorder, CircleShape)
                .background(
                    if (!pending && input.isNotBlank()) ConversationColors.sendBackground else ConversationColors.disabledAction,
                    CircleShape
                )
                .clickable(enabled = !pending && input.isNotBlank(), onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            SendUpIcon(color = ConversationColors.sendText)
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
    Canvas(modifier = Modifier.size(12.dp)) {
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

private data class ConversationBlock(val text: String, val code: Boolean)

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

private object ConversationTypography {
    val body = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
    val code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp
    )
    val meta = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
    val timestamp = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 15.sp
    )
    val action = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
    val input = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
}

private object ConversationColors {
    val background = Color(0xFF3F3F3F)
    val composerBar = Color(0xFF363636)
    val composerField = Color(0xFF585858)
    val divider = Color(0xFFF0F0F0)
    val primaryText = Color(0xFFE7ECE9)
    val secondaryText = Color(0xFFB0BAB6)
    val mutedText = Color(0xFF838D89)
    val placeholder = Color(0xFF98A29E)
    val action = Color(0xFF179489)
    val disabledAction = Color(0xFF16211F)
    val sendBackground = Color(0xFF253433)
    val sendBorder = Color(0xFFF1F5F3)
    val sendText = Color(0xFFFFFFFF)
    val operator = Color(0xFF179489)
    val operatorText = Color(0xFFE7ECE9)
    val codeBackground = Color(0xFF2F3331)
    val codeText = Color(0xFF179489)
    val quoteText = Color(0xFFA9D8BC)
    val error = Color(0xFFFFB4A9)
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
