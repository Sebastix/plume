package org.dergigi.feather

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.markdownAnnotator
import kotlinx.coroutines.CancellationException
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** The library's String convenience overload selects only the first paragraph.
 * Walk every document block instead. Unknown blocks retain their original text
 * so formatting support can never silently discard part of an article.
 * Call on Dispatchers.Default, not during composition. */
internal fun markdownDocument(
    text: String,
    style: TextStyle,
    linkStyle: SpanStyle,
    codeStyle: SpanStyle,
    listener: LinkInteractionListener,
): AnnotatedString = try {
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    val settings = DefaultAnnotatorSettings(linkTextSpanStyle = TextLinkStyles(linkStyle),
        codeSpanStyle = codeStyle, annotator = markdownAnnotator(), linkInteractionListener = listener)
    buildAnnotatedString {
        tree.children.forEach { node ->
            val raw = text.substring(node.startOffset, node.endOffset)
            if (raw.isBlank()) return@forEach
            val heading = node.type.toString().let { it.startsWith("ATX_") || it.startsWith("SETEXT_") }
            val rendered = when {
                node.type == MarkdownElementTypes.PARAGRAPH -> text.buildMarkdownAnnotatedString(node, style, settings)
                heading -> {
                    val title = raw.replace(Regex("^#{1,6}\\s+"), "")
                        .replace(Regex("\\s+#+\\s*$"), "")
                        .replace(Regex("\\n[=-]+\\s*$"), "")
                    title.buildMarkdownAnnotatedString(style = style.copy(fontWeight = FontWeight.Bold), annotatorSettings = settings)
                }
                else -> AnnotatedString(raw)
            }.takeUnless { it.isEmpty() } ?: AnnotatedString(raw)
            if (length > 0) append("\n\n")
            append(rendered)
        }
    }.takeUnless { it.isEmpty() && text.isNotBlank() } ?: AnnotatedString(text)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    AnnotatedString(text)
}
