package org.dergigi.feather

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.*
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import org.intellij.markdown.MarkdownElementTypes.IMAGE
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

/** Keep the renderer's formatting, inline images and table layout; add the shared link gesture. */
internal val articleLinkComponents = markdownComponents(
    paragraph = { ArticleLinkText(it.content, it.node, it.typography.paragraph) },
    heading1 = { ArticleLinkText(it.content, it.node, it.typography.h1, heading = true) },
    heading2 = { ArticleLinkText(it.content, it.node, it.typography.h2, heading = true) },
    heading3 = { ArticleLinkText(it.content, it.node, it.typography.h3, heading = true) },
    heading4 = { ArticleLinkText(it.content, it.node, it.typography.h4, heading = true) },
    heading5 = { ArticleLinkText(it.content, it.node, it.typography.h5, heading = true) },
    heading6 = { ArticleLinkText(it.content, it.node, it.typography.h6, heading = true) },
    setextHeading1 = { ArticleLinkText(it.content, it.node, it.typography.h1, heading = true) },
    setextHeading2 = { ArticleLinkText(it.content, it.node, it.typography.h2, heading = true) },
    table = { model ->
        MarkdownTable(model.content, model.node, model.typography.table,
            headerBlock = { content, node, width, style -> ArticleLinkTableRow(content, node, width, style, header = true) },
            rowBlock = { content, node, width, style -> ArticleLinkTableRow(content, node, width, style, header = false) })
    },
)

@Composable
@Suppress("DEPRECATION") // Preserve the renderer's table color compatibility.
private fun ArticleLinkText(content: String, node: ASTNode, style: TextStyle, heading: Boolean = false, tableCell: Boolean = false) {
    val settings = annotatorSettings()
    val child = if (heading) node.findChildOfType(MarkdownTokenTypes.ATX_CONTENT)
        ?: node.findChildOfType(MarkdownTokenTypes.SETEXT_CONTENT) ?: node else node
    val text = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content = content, node = child, annotatorSettings = settings)
        pop()
    }
    val layout = remember { TextLinkLayout() }
    val references = LocalReferenceLinkHandler.current
    val modifier = (if (heading) Modifier.semantics { heading() } else Modifier)
        .then(linkTextModifier(text, layout) { references.find(it).takeIf(String::isNotEmpty) ?: it })
    if (tableCell) MarkdownBasicText(text = text, modifier = modifier, style = style,
        color = LocalMarkdownColors.current.tableText, maxLines = 1, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout.result = it })
    else MarkdownText(content = text, modifier = modifier, style = style, onTextLayout = { result, _ -> layout.result = result })
}

@Composable
private fun ArticleLinkTableRow(content: String, node: ASTNode, width: Dp, style: TextStyle, header: Boolean) {
    val padding = LocalMarkdownDimens.current.tableCellPadding
    val components = LocalMarkdownComponents.current
    Row(Modifier.widthIn(width).then(if (header) Modifier.height(IntrinsicSize.Max) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        node.children.filter { it.type == CELL }.forEach { cell ->
            Column(Modifier.padding(padding).weight(1f)) {
                if (cell.children.any { it.type == IMAGE }) {
                    MarkdownElement(node = cell, components = components, content = content, includeSpacer = false)
                } else ArticleLinkText(content, cell, if (header) style.copy(fontWeight = FontWeight.Bold) else style, tableCell = true)
            }
        }
    }
}
