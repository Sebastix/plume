package org.dergigi.feather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ArticleBlock(val text: AnnotatedString? = null, val image: String? = null, val alt: String? = null)
private data class Article(val title: String?, val cover: String?, val blocks: List<ArticleBlock>)
private val markdownImage = Regex("!\\[([^]\\n]*)]\\((https?://[^\\s)]+)(?:\\s+\"[^\"]*\")?\\)")

@Composable
internal fun ArticleContent(event: Event, compact: Boolean, onReference: (NostrContent.Reference) -> Unit) {
    val context = LocalContext.current
    val navigate by rememberUpdatedState(onReference)
    val style = MaterialTheme.typography.bodyLarge
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary)
    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceContainerHighest)
    val listener = remember(context) { LinkInteractionListener { link ->
        if (link is LinkAnnotation.Url) {
            NostrContent.decode(link.url)?.let(navigate) ?: run {
                if (android.net.Uri.parse(link.url).scheme in listOf("http", "https")) context.openWebLink(link.url)
            }
        }
    } }
    val article by produceState<Article?>(null, event.id, compact, style, linkStyle, codeStyle) {
        value = withContext(Dispatchers.Default) {
            fun render(text: String): AnnotatedString = try {
                text.buildMarkdownAnnotatedString(style = style, linkTextSpanStyle = linkStyle,
                    codeSpanStyle = codeStyle, linkInteractionListener = listener).takeUnless { it.isEmpty() && text.isNotBlank() } ?: AnnotatedString(text)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { AnnotatedString(text) }
            val body = if (compact) event.tagValue("summary")?.takeIf { it.isNotBlank() } ?: event.content.take(1000) else event.content
            val blocks = mutableListOf<ArticleBlock>()
            var offset = 0
            // Bound automatic image loads; keep paragraphs in their original order.
            markdownImage.findAll(body).take(if (compact) 0 else 32).forEach { match ->
                if (match.range.first > offset) blocks += ArticleBlock(text = render(body.substring(offset, match.range.first)))
                blocks += ArticleBlock(image = MediaContent.secureUrl(match.groupValues[2]), alt = match.groupValues[1])
                offset = match.range.last + 1
            }
            if (offset < body.length) blocks += ArticleBlock(text = render(body.substring(offset)))
            Article(event.tagValue("title"), MediaContent.secureUrl(event.tagValue("image")), blocks)
        }
    }
    val model = article
    if (model == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        model.cover?.let { ArticleImage(it, model.title) }
        model.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
        model.blocks.forEach { block ->
            block.image?.let { ArticleImage(it, block.alt) }
            block.text?.takeIf { it.isNotBlank() }?.let {
                if (compact) Text(it, maxLines = 5, overflow = TextOverflow.Ellipsis, style = style)
                else SelectionContainer { Text(it, style = style) }
            }
        }
    }
}

@Composable
private fun ArticleImage(url: String, alt: String?) {
    var ratio by remember(url) { mutableFloatStateOf(16f / 9f) }
    val gallery = LocalOpenGallery.current
    Surface(onClick = { gallery(listOf(url), 0) }, color = MaterialTheme.colorScheme.surface) {
        RemoteImage(url, Modifier.fillMaxWidth().aspectRatio(ratio), alt, crop = false,
            onImageSize = { width, height -> if (width > 0 && height > 0) ratio = width.toFloat() / height })
    }
}

private data class Project(val name: String, val description: String, val url: String)

@Composable
internal fun ProjectCard(event: Event) {
    val context = LocalContext.current
    val project by produceState<Project?>(null, event.id) {
        value = withContext(Dispatchers.Default) {
            val identifier = event.tagValue("d").orEmpty()
            val web = event.tags.asSequence().filter { it.firstOrNull() == "web" }.flatMap { it.drop(1).asSequence() }
                .firstOrNull { runCatching { java.net.URI(it).let { uri -> uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() } }.getOrDefault(false) }
            Project(event.tagValue("name")?.takeIf { it.isNotBlank() } ?: identifier,
                event.tagValue("description").orEmpty(),
                web ?: "https://njump.to/${NAddress.create(30617, event.pubKey, identifier, relay = null)}")
        }
    }
    project?.let { model ->
        Surface(onClick = { context.openWebLink(model.url) }, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.project), null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (model.description.isNotBlank()) Text(model.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Icon(painterResource(R.drawable.open_external), "Open project", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
