package org.dergigi.feather

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2479A9), onPrimary = Color.White,
    primaryContainer = Color(0xFFF0F9FF), onPrimaryContainer = Color(0xFF293945),
    secondary = Color(0xFF2479A9), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1F2FC), onSecondaryContainer = Color(0xFF293945),
    background = Color.White, onBackground = Color(0xFF293945),
    surface = Color.White, onSurface = Color(0xFF293945),
    onSurfaceVariant = Color(0xFF647582), surfaceVariant = Color(0xFFF0F9FF),
    surfaceContainer = Color(0xFFF0F9FF), surfaceContainerLow = Color(0xFFF5FAFD),
    surfaceContainerHigh = Color(0xFFEAF4FA), surfaceContainerHighest = Color(0xFFE1EDF4),
    surfaceContainerLowest = Color.White, surfaceTint = Color(0xFF79C4F2),
    outline = Color(0xFF647582), outlineVariant = Color(0xFFEDF1F4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF79C4F2), onPrimary = Color(0xFF082F46),
    primaryContainer = Color(0xFF172D3A), onPrimaryContainer = Color(0xFFDCEFFB),
    secondary = Color(0xFF79C4F2), onSecondary = Color(0xFF082F46),
    secondaryContainer = Color(0xFF203846), onSecondaryContainer = Color(0xFFDCEFFB),
    background = Color(0xFF10171C), onBackground = Color(0xFFE5EDF2),
    surface = Color(0xFF10171C), onSurface = Color(0xFFE5EDF2),
    onSurfaceVariant = Color(0xFFA8BAC6), surfaceVariant = Color(0xFF20303B),
    surfaceContainer = Color(0xFF18232B), surfaceContainerLow = Color(0xFF141E25),
    surfaceContainerHigh = Color(0xFF20303B), surfaceContainerHighest = Color(0xFF293C48),
    surfaceContainerLowest = Color(0xFF0C1217), surfaceTint = Color(0xFF79C4F2),
    outline = Color(0xFF8398A6), outlineVariant = Color(0xFF263640),
)

@Composable
fun FeatherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
