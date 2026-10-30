package plume.sebastix.dev

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Greyscale palette; the burnt-orange action colour is the only chromatic exception.
private val LightColors = lightColorScheme(
    primary = Color(0xFFA84300), onPrimary = Color.White,
    primaryContainer = Color(0xFFF2F2F2), onPrimaryContainer = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFFF0894E),
    secondary = Color(0xFFA84300), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8), onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF5F5F5F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8E8E8), onTertiaryContainer = Color(0xFF1A1A1A),
    background = Color.White, onBackground = Color(0xFF1A1A1A),
    surface = Color.White, onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF5F5F5F),
    surfaceTint = Color(0xFF1A1A1A),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE2E2E2),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFDEDEDE),
    inverseSurface = Color(0xFF1A1A1A), inverseOnSurface = Color(0xFFF2F2F2),
    error = Color(0xFFA84300), onError = Color.White,
    errorContainer = Color(0xFFF2F2F2), onErrorContainer = Color(0xFF1A1A1A),
    outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFFE2E2E2),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF0894E), onPrimary = Color(0xFF2A1405),
    primaryContainer = Color(0xFF262626), onPrimaryContainer = Color(0xFFEDEDED),
    inversePrimary = Color(0xFFA84300),
    secondary = Color(0xFFF0894E), onSecondary = Color(0xFF2A1405),
    secondaryContainer = Color(0xFF262626), onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFBDBDBD), onTertiary = Color(0xFF1A1A1A),
    tertiaryContainer = Color(0xFF262626), onTertiaryContainer = Color(0xFFEDEDED),
    background = Color(0xFF121212), onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF121212), onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceTint = Color(0xFFEDEDED),
    surfaceBright = Color(0xFF262626), surfaceDim = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF0D0D0D), surfaceContainerLow = Color(0xFF171717),
    surfaceContainer = Color(0xFF1E1E1E), surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),
    inverseSurface = Color(0xFFEDEDED), inverseOnSurface = Color(0xFF1A1A1A),
    error = Color(0xFFF0894E), onError = Color(0xFF2A1405),
    errorContainer = Color(0xFF262626), onErrorContainer = Color(0xFFEDEDED),
    outline = Color(0xFF8F8F8F), outlineVariant = Color(0xFF333333),
    scrim = Color.Black,
)

@Composable
fun PlumeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
