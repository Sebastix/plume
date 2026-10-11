package org.dergigi.feather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ArticleBlock(val text: AnnotatedString? = null, val image: String? = null, val alt: String? = null)
private data class Article(val title: String?, val cover: String?, val blocks: List<ArticleBlock>)
private val markdownImage = Regex("!\\[([^]\\n]*)]\\((https?://[^\\s)]+)(?:\\s+\"[^\"]*\")?\\)")

@Composable
internal fun ArticleContent(event: Event, compact: Boolean, onReference: (NostrContent.Reference) -> Unit) {
    if (compact) {
        ArticlePreview(event)
        return
    }
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
            fun render(text: String) = markdownDocument(text, style, linkStyle, codeStyle, listener)
            val body = (if (compact) event.tagValue("summary")?.takeIf { it.isNotBlank() } ?: event.content.take(1000) else event.content).withoutObjectMarkers()
            val blocks = mutableListOf<ArticleBlock>()
            var offset = 0
            // Bound automatic image loads; keep paragraphs in their original order.
            markdownImage.findAll(body).take(if (compact) 0 else 32).forEach { match ->
                if (match.range.first > offset) blocks += ArticleBlock(text = render(body.substring(offset, match.range.first)))
                blocks += ArticleBlock(image = MediaContent.secureUrl(match.groupValues[2]), alt = match.groupValues[1])
                offset = match.range.last + 1
            }
            if (offset < body.length) blocks += ArticleBlock(text = render(body.substring(offset)))
            Article(event.tagValue("title")?.withoutObjectMarkers(), MediaContent.secureUrl(event.tagValue("image")), blocks)
        }
    }
    val model = article
    if (model == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        model.cover?.let { ArticleImage(it, model.title, event) }
        model.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
        model.blocks.forEach { block ->
            block.image?.let { ArticleImage(it, block.alt, event) }
            block.text?.takeIf { it.isNotBlank() }?.let {
                if (compact) Text(it, maxLines = 5, overflow = TextOverflow.Ellipsis, style = style)
                else SelectionContainer { Text(it, style = style) }
            }
        }
    }
}

@Composable
internal fun ArticleListRow(event: Event, author: String, picture: String?, onProfile: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Read article", onClick = onOpen)
        .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ArticlePreview(event)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(onClickLabel = "Open author's profile", onClick = onProfile),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(author, size = 24, picture = picture)
                Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ArticleReadingTime(event)
            NoteTimestamp(event)
        }
    }
}

/** Estimate from the complete article, never its summary; retain only small results. */
private object ArticleReadingTimes {
    private val cache = object : LinkedHashMap<String, Int>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 128
    }
    private val urls = Regex("https?://[^\\s)<>]+")
    private val words = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    suspend fun minutes(event: Event): Int = withContext(Dispatchers.Default) {
        synchronized(cache) { cache[event.id] }?.let { return@withContext it }
        val text = urls.replace(markdownImage.replace(event.content, ""), "")
        val count = words.findAll(text).count()
        // A deliberately simple 200 words/minute estimate, rounded up.
        ((count + 199) / 200).coerceAtLeast(1).also { synchronized(cache) { cache[event.id] = it } }
    }
}

@Composable
private fun ArticleReadingTime(event: Event) {
    val minutes by produceState<Int?>(null, event.id) { value = ArticleReadingTimes.minutes(event) }
    minutes?.let { estimate ->
        Row(Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Estimated reading time: $estimate ${if (estimate == 1) "minute" else "minutes"}"
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(R.drawable.reading_time), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${estimate}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class ArticlePreviewModel(val title: String, val excerpt: String, val cover: String?)

/** A bounded, non-interactive excerpt keeps the entire preview a reliable article target. */
@Composable
private fun ArticlePreview(event: Event) {
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val model by produceState<ArticlePreviewModel?>(null, event.id, bodyStyle) {
        value = withContext(Dispatchers.Default) {
            val raw = (event.tagValue("summary")?.takeIf { it.isNotBlank() } ?: event.content).take(2_000)
            val excerpt = markdownDocument(markdownImage.replace(raw.withoutObjectMarkers(), ""),
                bodyStyle, SpanStyle(), SpanStyle(), LinkInteractionListener { }).text
                .replace(Regex("\\s+"), " ").trim()
            ArticlePreviewModel(event.tagValue("title")?.withoutObjectMarkers()?.takeIf { it.isNotBlank() }
                ?: event.content.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('#', ' ')?.take(160).orEmpty(),
                excerpt, MediaContent.secureUrl(event.tagValue("image")))
        }
    }
    val preview = model
    if (preview == null) Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.CenterStart) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(preview.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (preview.excerpt.isNotBlank()) Text(preview.excerpt, style = bodyStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        preview.cover?.let { cover ->
            RemoteImage(cover, Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)), "Article cover")
        }
    }
}

@Composable
private fun ArticleImage(url: String, alt: String?, event: Event) {
    var ratio by remember(url) { mutableFloatStateOf(16f / 9f) }
    val gallery = LocalOpenGallery.current
    Surface(onClick = { gallery(listOf(url), 0, event) }, color = MaterialTheme.colorScheme.surface) {
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
