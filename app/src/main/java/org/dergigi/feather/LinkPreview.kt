package org.dergigi.feather

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
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

private data class PreviewLink(val url: String, val host: String, val youtube: YouTubeLink?)
private val webLinks = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
private val nonPages = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "svg", "mp4", "m4v", "mov", "webm", "mp3", "wav", "ogg", "pdf", "zip", "apk")

/** Parse once off-main; share the same card limit with the existing YouTube previews. */
@Composable
internal fun LinkPreviews(content: String, limit: Int = 2) {
    val links by produceState(emptyList<PreviewLink>(), content, limit) {
        value = withContext(Dispatchers.Default) {
            webLinks.findAll(content).mapNotNull { match ->
                val url = match.value.trimEnd('.', ',', ')', ']', '!', '?').toHttpUrlOrNull() ?: return@mapNotNull null
                if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in setOf(80, 443)) return@mapNotNull null
                if (url.pathSegments.lastOrNull()?.substringAfterLast('.')?.lowercase() in nonPages) return@mapNotNull null
                PreviewLink(url.toString(), url.host, YouTubeLinks.parse(url.toString(), 1).firstOrNull())
            }.distinctBy { it.url.substringBefore('#') }.take(limit.coerceIn(1, 2)).toList()
        }
    }
    links.forEach { link -> key(link.url) {
        if (link.youtube != null) YouTubePreview(link.youtube) else OpenGraphCard(link)
    } }
}

private data class LinkMetadata(val title: String?, val description: String?, val image: String?, val site: String?) {
    fun json() = JSONObject().put("title", title).put("description", description).put("image", image).put("site", site).toString()
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

    suspend fun get(raw: String, disk: DiskCache): LinkMetadata? = withContext(Dispatchers.IO) {
        val url = raw.toHttpUrlOrNull()?.newBuilder()?.scheme("https")?.fragment(null)?.build() ?: return@withContext null
        val key = "opengraph-v1:$url"
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
                        Entry(LinkMetadata(field("title"), field("description"), field("image"), field("site")), expires)
                    }
                }
            }.getOrNull()
            if (cached != null) { synchronized(cache) { cache[key] = cached }; return@withLock cached.metadata }
            val metadata = try {
                requests.withPermit { withTimeoutOrNull(12_000) { fetch(url) } }
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

    private suspend fun fetch(initial: HttpUrl): LinkMetadata? {
        var url = initial
        repeat(4) { hop ->
            if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val request = Request.Builder().url(url).header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", "Feather/${BuildConfig.VERSION_NAME} (link preview)").build()
            http.newCall(request).awaitPreview().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 3) return null
                    url = response.header("Location")?.let(url::resolve) ?: return null
                } else {
                    if (!response.isSuccessful) return null
                    val type = response.body.contentType()
                    if (type != null && type.subtype !in setOf("html", "xhtml+xml")) return null
                    val buffer = Buffer()
                    val source = response.body.source()
                    var tail = ""
                    while (buffer.size < 262_144L) {
                        currentCoroutineContext().ensureActive()
                        val oldSize = buffer.size
                        if (source.read(buffer, minOf(8_192L, 262_144L - oldSize)) == -1L) break
                        val chunk = Buffer()
                        buffer.copyTo(chunk, oldSize, buffer.size - oldSize)
                        val ending = tail + chunk.readString(Charsets.ISO_8859_1).lowercase()
                        if ("</head>" in ending) break
                        tail = ending.takeLast(16)
                    }
                    val document = Jsoup.parse(buffer.inputStream(), type?.charset()?.name(), url.toString())
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
                    return LinkMetadata(title, description, image, clean(meta("og:site_name"), 100))
                }
            }
        }
        return null
    }
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
        if (resources != null && media.active && visible) value = OpenGraph.get(link.url, resources.imageDisk)
    }
    // The small host-only card remains useful offline or when a server blocks preview requests.
    Spacer(Modifier.height(12.dp))
    Surface(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) } },
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().onGloballyPositioned {
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
