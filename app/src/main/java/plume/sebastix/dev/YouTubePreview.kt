package plume.sebastix.dev

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal data class YouTubeLink(val id: String, val url: String) {
    val thumbnail get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}

internal object YouTubeLinks {
    private val urls = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    fun parse(content: String, limit: Int = 2): List<YouTubeLink> = urls.findAll(content).mapNotNull { match ->
        val raw = match.value.trimEnd('.', ',', ')', ']', '!', '?')
        val url = raw.toHttpUrlOrNull() ?: return@mapNotNull null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return@mapNotNull null
        val path = url.pathSegments
        val id = when (url.host) {
            "youtu.be" -> path.firstOrNull()
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "www.youtube-nocookie.com" -> when (path.firstOrNull()) {
                "watch" -> url.queryParameter("v")
                "shorts", "live", "embed" -> path.getOrNull(1)
                else -> null
            }
            else -> null
        } ?: return@mapNotNull null
        if (!videoId.matches(id)) null else YouTubeLink(id, url.newBuilder().scheme("https").build().toString())
    }.distinctBy { it.id }.take(limit).toList()
}

/** Small oEmbed responses, serialized and cached inside the existing media disk budget. */
private object YouTubeMetadata {
    data class Details(val title: String, val author: String)
    private data class Entry(val details: Details?, val expires: Long)
    private val mutex = Mutex()
    private val memory = object : LinkedHashMap<String, Entry>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 128
    }
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build()
    private fun decode(text: String): Details = JSONObject(text).let {
        Details(it.getString("title").take(300), it.optString("author_name").take(150))
    }
    suspend fun get(link: YouTubeLink, resources: MediaStore.Resources): Details? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            memory[link.id]?.takeIf { it.expires > now }?.let { return@withLock it.details }
            val key = "youtube-oembed-v1:${link.id}"
            val disk = resources.imageDisk
            val cached = runCatching {
                disk.openSnapshot(key)?.use { snapshot ->
                    val expires = snapshot.metadata.toFile().readText().toLongOrNull() ?: 0L
                    if (expires <= now || snapshot.data.toFile().length() > 32_768) null
                    else Entry(decode(snapshot.data.toFile().readText()), expires)
                }
            }.getOrNull()
            if (cached != null) { memory[link.id] = cached; return@withLock cached.details }
            val details = try {
                val endpoint = "https://www.youtube.com/oembed".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("url", "https://www.youtube.com/watch?v=${link.id}")
                    .addQueryParameter("format", "json").build()
                val call = http.newCall(Request.Builder().url(endpoint).build())
                val response = suspendCancellableCoroutine<Response> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (!continuation.isCancelled) continuation.resumeWithException(e)
                        }
                        override fun onResponse(call: Call, response: Response) {
                            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
                        }
                    })
                }
                response.use {
                    require(it.isSuccessful)
                    val source = it.body.source()
                    // Read a bounded body even if the server omits or lies about Content-Length.
                    require(!source.request(32_769))
                    val text = source.readUtf8()
                    val parsed = decode(text)
                    val editor = runCatching { disk.openEditor(key) }.getOrNull()
                    if (editor != null) try {
                        editor.metadata.toFile().writeText((now + 7 * 86_400_000L).toString())
                        editor.data.toFile().writeText(text)
                        editor.commit()
                    } catch (_: Exception) { runCatching { editor.abort() } }
                    parsed
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            memory[link.id] = Entry(details, now + if (details == null) 60_000L else 7 * 86_400_000L)
            details
        }
    }
}

@Composable
internal fun YouTubePreview(link: YouTubeLink) {
    val context = LocalContext.current
    val view = LocalView.current
    val media = LocalMedia.current
    var visible by remember(link.id) { mutableStateOf(false) }
    val details by produceState<YouTubeMetadata.Details?>(null, link.id, media.resources, media.active, visible) {
        val resources = media.resources
        if (resources != null && media.active && visible) value = YouTubeMetadata.get(link, resources)
    }
    Spacer(Modifier.height(12.dp))
    Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(
            onClickLabel = "Open in YouTube", onLongClickLabel = "Copy link",
            onClick = { context.openWebLink(link.url) }, onLongClick = { context.copyLink(link.url) }
        ).onGloballyPositioned {
            val bounds = it.boundsInWindow()
            visible = bounds.bottom > 0 && bounds.top < view.height
        }) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                RemoteImage(link.thumbnail, Modifier.fillMaxSize(), details?.title ?: "YouTube video")
                Icon(Icons.Default.PlayArrow, stringResource(R.string.ui_open_in_youtube), Modifier.size(48.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), CircleShape).padding(10.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                details?.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(details?.author?.takeIf { it.isNotBlank() }?.let { "$it · YouTube" } ?: "YouTube",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
