package com.atenea.android.coreconsole

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class OperationalLevel(val label: String) {
    OK("OK"),
    WARNING("Atención"),
    CRITICAL("Crítico"),
    UNKNOWN("Sin datos")
}

@Composable
internal fun StatusPill(
    level: OperationalLevel,
    modifier: Modifier = Modifier
) {
    val colors = level.colors()
    Text(
        text = level.label,
        modifier = modifier
            .background(colors.container, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = colors.content,
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
internal fun AteneaPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

@Composable
internal fun MetricLine(
    label: String,
    value: String,
    level: OperationalLevel = OperationalLevel.UNKNOWN
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            color = level.metricColor(),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
internal fun ErrorPanel(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            modifier = Modifier.padding(12.dp),
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
internal fun AteneaButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        onClick = onClick
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AteneaOutlinedButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        modifier = modifier.heightIn(min = 40.dp),
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        onClick = onClick
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AteneaTextButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        onClick = onClick
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MenuGlyph(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 20.sp,
    onClick: () -> Unit
) {
    Text(
        text = "☰",
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 0.dp),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = fontSize
    )
}

private data class PillColors(
    val container: Color,
    val content: Color
)

@Composable
private fun OperationalLevel.colors(): PillColors = when (this) {
    OperationalLevel.OK -> PillColors(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer
    )
    OperationalLevel.WARNING -> PillColors(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer
    )
    OperationalLevel.CRITICAL -> PillColors(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer
    )
    OperationalLevel.UNKNOWN -> PillColors(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun OperationalLevel.metricColor(): Color = when (this) {
    OperationalLevel.CRITICAL -> MaterialTheme.colorScheme.error
    OperationalLevel.WARNING -> MaterialTheme.colorScheme.secondary
    OperationalLevel.OK -> MaterialTheme.colorScheme.tertiary
    OperationalLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurface
}
