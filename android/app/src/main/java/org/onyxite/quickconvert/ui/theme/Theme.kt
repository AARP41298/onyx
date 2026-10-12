package org.onyxite.quickconvert.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF2C2416)
private val Beige = Color(0xFFF6EBD2)
private val Paper = Color(0xFFFFF8EA)
private val Green = Color(0xFF2F7D46)

private val QuickConvertColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F0DC),
    onPrimaryContainer = Color(0xFF12321C),
    secondary = Color(0xFF6B5428),
    onSecondary = Color.White,
    background = Beige,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3E4C4),
    onSurfaceVariant = Color(0xFF5C5140),
    outline = Color(0xFFC9B48A),
)

@Composable
fun QuickConvertTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QuickConvertColors,
        content = content,
    )
}
