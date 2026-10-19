package org.dergigi.feather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reserve space within the third line; preserve link annotations in the visible prefix. */
@Composable
internal fun CompactBiography(text: AnnotatedString, hasQuotes: Boolean, onExpand: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)
    val placeholder = Placeholder(24.sp, 24.sp, PlaceholderVerticalAlign.Center)
    BoxWithConstraints {
        val width = constraints.maxWidth
        val preview = remember(text, hasQuotes, width, style, measurer) {
            val limits = Constraints(maxWidth = width)
            val full = measurer.measure(text, style = style, maxLines = 3, constraints = limits)
            if (!full.hasVisualOverflow && !hasQuotes) text else {
                fun candidate(end: Int): AnnotatedString {
                    var cut = end
                    if (cut > 0 && cut < text.length && text[cut - 1].isHighSurrogate() && text[cut].isLowSurrogate()) cut--
                    while (cut > 0 && text[cut - 1].isWhitespace()) cut--
                    return buildAnnotatedString {
                        append(text.subSequence(0, cut))
                        if (end < text.length) append("…")
                        appendInlineContent("expand", "\uFFFC")
                    }
                }
                fun fits(value: AnnotatedString): Boolean = !measurer.measure(value, style = style,
                    maxLines = 3, constraints = limits,
                    placeholders = listOf(AnnotatedString.Range(placeholder, value.length - 1, value.length))).hasVisualOverflow
                var low = 0
                var high = if (full.hasVisualOverflow) full.getLineEnd(full.lineCount - 1) else text.length
                while (low < high) {
                    val middle = (low + high + 1) / 2
                    if (fits(candidate(middle))) low = middle else high = middle - 1
                }
                candidate(low)
            }
        }
        LinkText(preview, style = style, maxLines = 3, inlineContent = mapOf(
            "expand" to InlineTextContent(placeholder) {
                Box(Modifier.clickable(onClickLabel = "Expand biography", onClick = onExpand), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.KeyboardArrowDown, "Expand biography", Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        ))
    }
}
