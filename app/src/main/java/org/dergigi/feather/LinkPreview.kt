package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.disk.DiskCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

private data class PreviewLink(val url: String, val host: String, val youtube: YouTubeLink?, val podcast: PodcastReference? = null)
private val webLinks = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
private val nonPages = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "svg", "mp4", "m4v", "mov", "webm", "mp3", "wav", "ogg", "pdf", "zip", "apk")

/** Parse once off-main; share the same card limit with the existing YouTube previews. */
@Composable
internal fun LinkPreviews(content: String, limit: Int = 2, event: Event? = null) {
    val podcasts by produceState(emptyList<PodcastReference>(), event?.id) {
        value = withContext(Dispatchers.Default) { event?.let(::podcastReferences).orEmpty() }
    }
    val links by produceState(emptyList<PreviewLink>(), content, limit, podcasts) {
        value = withContext(Dispatchers.Default) {
            (podcasts.mapNotNull { it.url }.asSequence() + webLinks.findAll(content).map { it.value }).mapNotNull { raw ->
                val url = (FountainLinks.canonical(raw) ?: raw).trimEnd('.', ',', ')', ']', '!', '?').toHttpUrlOrNull() ?: return@mapNotNull null
                if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in setOf(80, 443)) return@mapNotNull null
                if (podcasts.none { it.url == url.toString() } && url.pathSegments.lastOrNull()?.substringAfterLast('.')?.lowercase() in nonPages) return@mapNotNull null
                PreviewLink(url.toString(), url.host, YouTubeLinks.parse(url.toString(), 1).firstOrNull(),
                    podcasts.firstOrNull { it.url == url.toString() })
            }.distinctBy { it.url.substringBefore('#') }.take((limit.coerceIn(1, 2) - podcasts.count { it.url == null }).coerceAtLeast(0)).toList()
        }
    }
    podcasts.filter { it.url == null }.take(limit).forEach { reference ->
        val context = LocalContext.current
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.podcast), null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(if (reference.episode) "Podcast episode" else "Podcast", style = MaterialTheme.typography.titleSmall)
                    Text(reference.id.substringAfter("guid:"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { context.copyLink(reference.id) }) {
                    Icon(painterResource(R.drawable.copy), "Copy podcast identifier")
                }
            }
        }
    }
    links.forEach { link -> key(link.url) {
        if (link.youtube != null) YouTubePreview(link.youtube) else OpenGraphCard(link)
    } }
}

private data class LinkMetadata(val title: String?, val description: String?, val image: String?, val site: String?,
    val mediaType: String? = null, val audio: String? = null, val duration: Long = 0) {
    fun json() = JSONObject().put("title", title).put("description", description).put("image", image).put("site", site)
        .put("mediaType", mediaType).put("audio", audio).put("duration", duration).toString()
}

/** Preview requests never run scripts, store cookies, or share the visible-image dispatcher. */
private object OpenGraph {
    private data class Entry(val metadata: LinkMetadata?, val expires: Long)
    private val cache = object : LinkedHashMap<String, Entry>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 128
    }
    private val locks = Array(16) { Mutex() }
    private val requests = Semaphore(2)
    private val publicDns = Dns { hostname ->
        Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw UnknownHostException("Local preview address")
        }
    }
    private fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 255
        if (bytes.size == 16 && first and 254 == 252) return false
        if (bytes.size == 4 && (first == 0 || first >= 224 || (first == 100 && (bytes[1].toInt() and 255) in 64..127))) return false
        return true
    }
    private val http = OkHttpClient.Builder().dns(publicDns).followRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    suspend fun get(raw: String, disk: DiskCache, podcast: PodcastReference? = null): LinkMetadata? = withContext(Dispatchers.IO) {
        val url = (FountainLinks.canonical(raw) ?: raw).toHttpUrlOrNull()?.newBuilder()?.scheme("https")?.fragment(null)?.build() ?: return@withContext null
        val key = "opengraph-v3:$url:${podcast?.id.orEmpty()}"
        synchronized(cache) { cache[key]?.takeIf { it.expires > System.currentTimeMillis() } }?.let { return@withContext it.metadata }
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val now = System.currentTimeMillis()
            synchronized(cache) { cache[key]?.takeIf { it.expires > now } }?.let { return@withLock it.metadata }
            val cached = runCatching {
                disk.openSnapshot(key)?.use { snapshot ->
                    val expires = snapshot.metadata.toFile().readText().toLongOrNull() ?: 0L
                    if (expires <= now || snapshot.data.toFile().length() > 16_384) null else {
                        val json = JSONObject(snapshot.data.toFile().readText())
                        fun field(name: String) = json.optString(name).takeIf { it.isNotBlank() && it != "null" }
                        Entry(LinkMetadata(field("title"), field("description"), field("image"), field("site"), field("mediaType"), field("audio"), json.optLong("duration")), expires)
                    }
                }
            }.getOrNull()
            if (cached != null) { synchronized(cache) { cache[key] = cached }; return@withLock cached.metadata }
            val metadata = try {
                requests.withPermit { withTimeoutOrNull(12_000) { fetch(url, podcast) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            val expires = now + if (metadata == null) 5 * 60_000L else 7 * 86_400_000L
            synchronized(cache) { cache[key] = Entry(metadata, expires) }
            if (metadata != null) {
                val editor = runCatching { disk.openEditor(key) }.getOrNull()
                if (editor != null) try {
                    editor.metadata.toFile().writeText(expires.toString())
                    editor.data.toFile().writeText(metadata.json())
                    editor.commit()
                } catch (_: Exception) { runCatching { editor.abort() } }
            }
            metadata
        }
    }

    private suspend fun fetch(initial: HttpUrl, podcast: PodcastReference?): LinkMetadata? {
        var url = initial
        val fountain = FountainLinks.canonical(initial.toString())
        repeat(4) { hop ->
            if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val request = Request.Builder().url(url).header("Accept", "text/html,application/xhtml+xml,image/*,video/*,*/*;q=0.8")
                .header("User-Agent", "Feather/${BuildConfig.VERSION_NAME} (link preview)").build()
            http.newCall(request).awaitPreview().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 3) return null
                    url = response.header("Location")?.let(url::resolve) ?: return null
                } else {
                    if (!response.isSuccessful) return null
                    val type = response.body.contentType()
                    if (type?.type in setOf("image", "video"))
                        return LinkMetadata(null, null, null, null, "${type!!.type}/${type.subtype}")
                    if (podcast?.episode == true && type?.type == "audio")
                        return LinkMetadata("Podcast episode", null, null, url.host, audio = url.toString())
                    val xml = podcast != null && type?.subtype in setOf("xml", "rss+xml", "atom+xml")
                    if (type != null && type.subtype !in setOf("html", "xhtml+xml") && !xml) return null
                    val buffer = Buffer()
                    val source = response.body.source()
                    var tail = ""
                    val maxBytes = if (fountain != null || xml) 1_048_576L else 262_144L
                    while (buffer.size < maxBytes) {
                        currentCoroutineContext().ensureActive()
                        val oldSize = buffer.size
                        if (source.read(buffer, minOf(8_192L, maxBytes - oldSize)) == -1L) break
                        val chunk = Buffer()
                        buffer.copyTo(chunk, oldSize, buffer.size - oldSize)
                        val ending = tail + chunk.readString(Charsets.ISO_8859_1).lowercase()
                        if (fountain == null && !xml && "</head>" in ending) break
                        tail = ending.takeLast(16)
                    }
                    val document = Jsoup.parse(buffer.inputStream(), type?.charset()?.name(), url.toString(),
                        if (xml) org.jsoup.parser.Parser.xmlParser() else org.jsoup.parser.Parser.htmlParser())
                    if (xml) return podcastFeedMetadata(document, podcast!!)
                    if (fountain != null) {
                        FountainLinks.episode(document, fountain)?.let { episode ->
                            return LinkMetadata(episode.title, null, episode.image, episode.show ?: "Fountain",
                                audio = episode.audio, duration = episode.duration)
                        }
                    }
                    val head = document.head()
                    fun meta(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
                        head.select("meta[property=$name],meta[name=$name]").firstOrNull { it.attr("content").isNotBlank() }
                            ?.attr("content")?.trim()
                    }
                    fun clean(value: String?, max: Int) = value?.replace(Regex("\\s+"), " ")?.trim()?.take(max)?.takeIf { it.isNotBlank() }
                    val title = clean(meta("og:title", "twitter:title") ?: document.title(), 300)
                    val description = clean(meta("og:description", "twitter:description", "description"), 600)
                    val image = meta("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src")
                        ?.let { document.baseUri().toHttpUrlOrNull()?.resolve(it) }
                        ?.takeIf { it.scheme == "https" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() }
                        ?.takeIf { runCatching { publicDns.lookup(it.host) }.isSuccess }?.toString()
                    if (title == null && description == null && image == null) return null
                    val audio = if (podcast?.episode == true) meta("og:audio:secure_url", "og:audio", "og:audio:url")
                        ?.let { url.resolve(it) }?.toString()?.let(MediaContent::secureUrl) else null
                    return LinkMetadata(title, description, image, clean(meta("og:site_name"), 100), audio = audio)
                }
            }
        }
        return null
    }
}

/** Match an RSS enclosure to the exact external episode GUID, never guess the latest episode. */
private fun podcastFeedMetadata(document: org.jsoup.nodes.Document, reference: PodcastReference): LinkMetadata? {
    val channel = document.selectFirst("channel") ?: return null
    val show = channel.children().firstOrNull { it.tagName() == "title" }?.text()?.take(300)
    val showImage = channel.children().firstOrNull { it.tagName() == "itunes:image" }?.attr("href")
        ?: channel.selectFirst("image > url")?.text()
    if (!reference.episode) return LinkMetadata(show, null, MediaContent.secureUrl(showImage), show)
    val guid = reference.id.removePrefix("podcast:item:guid:")
    val item = channel.select("item").firstOrNull { it.selectFirst("guid")?.text() == guid } ?: return null
    val enclosure = item.select("enclosure").firstOrNull { it.attr("type").startsWith("audio/") } ?: return null
    val audio = MediaContent.secureUrl(enclosure.attr("url")) ?: return null
    val image = item.children().firstOrNull { it.tagName() == "itunes:image" }?.attr("href") ?: showImage
    val duration = item.children().firstOrNull { it.tagName() == "itunes:duration" }?.text()
        ?.split(':')?.takeIf { it.size in 1..3 }?.map { it.toDoubleOrNull() }
        ?.takeIf { parts -> parts.all { it != null && it.isFinite() && it >= 0 } }
        ?.fold(0.0) { total, part -> total * 60 + part!! }?.takeIf { it <= 604800 }?.times(1000)?.toLong() ?: 0
    return LinkMetadata(item.selectFirst("title")?.text()?.take(300), null, MediaContent.secureUrl(image), show,
        audio = audio, duration = duration)
}

private suspend fun Call.awaitPreview(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}

@Composable
private fun OpenGraphCard(link: PreviewLink) {
    val context = LocalContext.current
    val view = LocalView.current
    val media = LocalMedia.current
    var visible by remember(link.url) { mutableStateOf(false) }
    val metadata by produceState<LinkMetadata?>(null, link.url, media.resources, media.active, visible) {
        val resources = media.resources
        if (resources != null && media.active && visible) {
            value = OpenGraph.get(link.url, resources.imageDisk, link.podcast)
            value?.mediaType?.let { DetectedMedia.put(link.url, it) }
        }
    }
    val episode = metadata?.takeIf { it.audio != null }
    if (episode != null) {
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                val bounds = it.boundsInWindow()
                visible = bounds.bottom > 0 && bounds.top < view.height
            }) {
            Column(Modifier.padding(12.dp)) {
                AudioPlayerContent("podcast:${episode.audio}",
                    AudioInfo(episode.audio, null, episode.title, "", episode.image, episode.duration, emptyList()),
                    compact = true, author = episode.site ?: link.host, smallArtwork = true)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(episode.site ?: link.host, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { context.openWebLink(link.url) }) {
                        Icon(painterResource(R.drawable.open_external), "Open podcast source", Modifier.size(18.dp))
                    }
                }
            }
        }
        return
    }
    // The small host-only card remains useful offline or when a server blocks preview requests.
    Spacer(Modifier.height(12.dp))
    Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(
            onClickLabel = "Open link", onLongClickLabel = "Copy link",
            onClick = { context.openWebLink(link.url) }, onLongClick = { context.copyLink(link.url) }
        ).onGloballyPositioned {
            val bounds = it.boundsInWindow()
            visible = bounds.bottom > 0 && bounds.top < view.height
        }) {
        Column {
            metadata?.image?.let { RemoteImage(it, Modifier.fillMaxWidth().aspectRatio(1.91f), metadata?.title ?: link.host) }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                metadata?.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                metadata?.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(metadata?.site?.takeIf { !it.equals(link.host, true) }?.let { "$it · ${link.host}" } ?: link.host,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
