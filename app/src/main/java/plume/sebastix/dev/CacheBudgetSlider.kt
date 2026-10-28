package plume.sebastix.dev

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Fullness is relative to the chosen budget, not the slider's maximum. */
@Composable
internal fun cacheUsageColor(usedBytes: Long?, budgetMb: Int): Color {
    if (usedBytes == null) return MaterialTheme.colorScheme.onSurfaceVariant
    val fraction = (usedBytes.toDouble() / (budgetMb.toLong() * 1024 * 1024)).toFloat().coerceIn(0f, 1f)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val green = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
    val orange = if (dark) Color(0xFFFFB74D) else Color(0xFFEF6C00)
    val red = if (dark) Color(0xFFEF9A9A) else Color(0xFFC62828)
    return when {
        fraction <= 0.5f -> green
        fraction < 0.75f -> lerp(green, orange, (fraction - 0.5f) / 0.25f)
        else -> lerp(orange, red, ((fraction - 0.75f) / 0.20f).coerceAtMost(1f))
    }
}

/** Usage and budget share a byte-proportional scale; the thumb keeps the existing limits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CacheBudgetSlider(
    selectedIndex: Int,
    usedBytes: Long?,
    onSelect: (Int) -> Unit,
    onFinished: (Int) -> Unit,
) {
    val options = MediaContent.cacheOptionsMb
    val maximum = options.last().toFloat()
    val budget = options[selectedIndex].toFloat()
    val usedMb = (usedBytes ?: 0L).toDouble() / (1024 * 1024)
    val colors = MaterialTheme.colorScheme
    val usageColor = cacheUsageColor(usedBytes, options[selectedIndex])
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Slider(
        value = budget,
        onValueChange = { value -> onSelect(options.indices.minBy { abs(options[it] - value) }) },
        valueRange = 0f..maximum,
        onValueChangeFinished = { onFinished(selectedIndex) },
        modifier = Modifier.semantics {
            setProgress { requested ->
                val nearest = options.indices.minBy { abs(options[it] - requested) }
                val next = if (nearest != selectedIndex) nearest else when {
                    requested > budget -> (selectedIndex + 1).coerceAtMost(options.lastIndex)
                    requested < budget -> (selectedIndex - 1).coerceAtLeast(0)
                    else -> selectedIndex
                }
                if (next != selectedIndex) { onSelect(next); onFinished(next) }
                next != selectedIndex
            }
            contentDescription = "Media cache storage limit"
            stateDescription = "${budget.toInt()} megabytes; " +
                if (usedBytes == null) "usage loading" else "${usedMb.toInt()} megabytes used"
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val radius = size.height / 2
                val length = (size.width - size.height).coerceAtLeast(0f)
                fun point(fraction: Float): Offset {
                    val x = radius + length * fraction.coerceIn(0f, 1f)
                    return Offset(if (rtl) size.width - x else x, radius)
                }
                fun segment(fraction: Float, color: androidx.compose.ui.graphics.Color) {
                    if (fraction > 0f) drawLine(color, point(0f), point(fraction), size.height, StrokeCap.Round)
                }
                segment(1f, colors.surfaceVariant)
                segment(budget / maximum, colors.primary.copy(alpha = 0.35f))
                segment((usedMb / maximum).toFloat(), usageColor)
                options.forEach { mb ->
                    drawCircle(colors.onSurfaceVariant.copy(alpha = 0.5f), 1.dp.toPx(), point(mb / maximum))
                }
            }
        },
    )
}
