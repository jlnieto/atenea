package com.atenea.android.coreconsole

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OperatorLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1E252B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6EAED),
    onPrimaryContainer = Color(0xFF111416),
    secondary = Color(0xFF56616A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7EAEC),
    onSecondaryContainer = Color(0xFF161A1D),
    tertiary = Color(0xFF16784A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDF2E6),
    onTertiaryContainer = Color(0xFF082A19),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF171717),
    surface = Color(0xFFFAFAF8),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFE4E1DC),
    onSurfaceVariant = Color(0xFF5E615F),
    outline = Color(0xFFC9C6C0),
    outlineVariant = Color(0xFFE4E1DC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAF8),
    surfaceContainer = Color(0xFFF2F1EE),
    surfaceContainerHigh = Color(0xFFECEAE6),
    surfaceContainerHighest = Color(0xFFE2E0DA)
)

private val OperatorShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp)
)

private val OperatorTypography = Typography(
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 19.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp
    )
)

@Composable
fun AteneaOperatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OperatorLightColors,
        typography = OperatorTypography,
        shapes = OperatorShapes,
        content = content
    )
}
