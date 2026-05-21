package com.atenea.android.coreconsole

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
internal fun DeliverableMarkdown(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseDeliverableMarkdown(markdown) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> MarkdownHeading(block)
                is MarkdownBlock.Paragraph -> MarkdownParagraph(block.text)
                is MarkdownBlock.Bullet -> MarkdownBullet(block)
                is MarkdownBlock.Numbered -> MarkdownNumbered(block)
                is MarkdownBlock.Code -> MarkdownCode(block.text)
            }
        }
    }
}

@Composable
private fun MarkdownHeading(block: MarkdownBlock.Heading) {
    Text(
        renderDeliverableInlineMarkdown(block.text),
        style = when (block.level) {
            1 -> MaterialTheme.typography.titleMedium
            2 -> MaterialTheme.typography.titleSmall
            else -> MaterialTheme.typography.labelLarge
        },
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = if (block.first) 0.dp else 10.dp)
    )
}

@Composable
private fun MarkdownParagraph(text: String) {
    Text(
        renderDeliverableInlineMarkdown(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun MarkdownBullet(block: MarkdownBlock.Bullet) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.level * 18).dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            renderDeliverableInlineMarkdown(block.text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MarkdownNumbered(block: MarkdownBlock.Numbered) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.level * 18).dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "${block.number}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            renderDeliverableInlineMarkdown(block.text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MarkdownCode(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace
    )
}

private fun parseDeliverableMarkdown(markdown: String): List<MarkdownBlock> {
    val normalized = markdown.normalizeDeliverableMarkdown()
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var inCode = false
    var codeLanguageSeen = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
            paragraph.clear()
        }
    }

    fun flushCode() {
        blocks += MarkdownBlock.Code(code.joinToString("\n").trimEnd())
        code.clear()
        codeLanguageSeen = false
    }

    normalized.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            if (inCode) {
                flushCode()
            } else {
                flushParagraph()
            }
            inCode = !inCode
            codeLanguageSeen = false
            return@forEach
        }

        if (inCode) {
            if (!codeLanguageSeen && trimmed.matches(Regex("^[A-Za-z0-9_+-]+$"))) {
                codeLanguageSeen = true
            } else {
                code += line
            }
            return@forEach
        }

        if (trimmed.isBlank()) {
            flushParagraph()
            return@forEach
        }

        val heading = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownBlock.Heading(
                level = heading.groupValues[1].length,
                text = heading.groupValues[2].trim(),
                first = blocks.isEmpty()
            )
            return@forEach
        }

        val bullet = Regex("^([-*])\\s+(.+)$").find(trimmed)
        if (bullet != null) {
            flushParagraph()
            blocks += MarkdownBlock.Bullet(
                level = (line.takeWhile { it == ' ' }.length / 2).coerceAtMost(3),
                text = bullet.groupValues[2].trim()
            )
            return@forEach
        }

        val numbered = Regex("^(\\d+)\\.\\s+(.+)$").find(trimmed)
        if (numbered != null) {
            flushParagraph()
            blocks += MarkdownBlock.Numbered(
                level = (line.takeWhile { it == ' ' }.length / 2).coerceAtMost(3),
                number = numbered.groupValues[1],
                text = numbered.groupValues[2].trim()
            )
            return@forEach
        }

        paragraph += trimmed
    }

    if (inCode) {
        flushCode()
    }
    flushParagraph()
    return blocks
}

private fun String.normalizeDeliverableMarkdown(): String =
    replace("\r\n", "\n")
        .replace(Regex("(?<!^)\\s*(#{1,6}\\s+)"), "\n$1")
        .replace(Regex("(#{1,6})([^#\\s])"), "$1 $2")
        .replace(Regex("(Read|Hours)([A-ZÁÉÍÓÚÑ])"), "$1\n$2")
        .replace(Regex("([^\\n])\\s+([-*]\\s+)"), "$1\n$2")
        .replace(Regex("([^\\n])\\s+(\\d+\\.\\s+)"), "$1\n$2")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .trim()

@Composable
private fun renderDeliverableInlineMarkdown(text: String) = buildAnnotatedString {
    val regex = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`|\\[[^\\]]+\\]\\([^)]+\\))")
    var cursor = 0
    regex.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val value = match.value
        when {
            value.startsWith("**") && value.endsWith("**") -> withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(value.removePrefix("**").removeSuffix("**"))
            }
            value.startsWith("`") && value.endsWith("`") -> withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            ) {
                append(value.removePrefix("`").removeSuffix("`"))
            }
            value.startsWith("[") -> {
                val link = Regex("^\\[([^\\]]+)]\\(([^)]+)\\)").find(value)
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                    append(link?.groupValues?.getOrNull(1).orEmpty().ifBlank { value })
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

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String, val first: Boolean) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val level: Int, val text: String) : MarkdownBlock
    data class Numbered(val level: Int, val number: String, val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
}
