@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.dergigi.feather

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.size.Dimension
import coil3.size.Size
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.*


data class MediaEnvironment(val resources: MediaStore.Resources?, val active: Boolean, val playback: VisibleVideoPlayer?)
val LocalMedia = staticCompositionLocalOf { MediaEnvironment(null, false, null) }

/** One decoder/player for the entire reading screen; all geometry is reported on the main thread. */
class VisibleVideoPlayer(private val context: Context, private val resources: MediaStore.Resources) {
    private data class Candidate(val url: String, val score: Float)
    private val candidates = mutableMapOf<String, Candidate>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selectionJob: Job? = null
    private var enabled = true
    var viewport = Rect.Zero
    var activeKey by mutableStateOf<String?>(null)
        private set
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var muted by mutableStateOf(true)
        private set
    fun report(key: String, url: String, bounds: Rect, height: Float) {
        val score = MediaContent.visibleScore(bounds.top, bounds.bottom, height, viewport.top, viewport.bottom)
        if (score == null) candidates.remove(key) else candidates[key] = Candidate(url, score)
        choose()
    }
    fun remove(key: String) { candidates.remove(key); choose() }
    fun enabled(value: Boolean) { enabled = value; choose() }
    private fun choose() {
        val next = if (enabled) candidates.minByOrNull { it.value.score }?.key else null
        if (next == activeKey) { selectionJob?.cancel(); return }
        selectionJob?.cancel()
        // Stop immediately off-screen; avoid preparing decoders for items during a fling.
        if (activeKey !in candidates || !enabled) { player?.stop(); activeKey = null }
        selectionJob = scope.launch {
            if (next != null) delay(120)
            if (next != null && (!enabled || next !in candidates)) return@launch
            activeKey = next
            if (next == null) { player?.stop(); return@launch }
            val engine = player ?: ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(resources.videoSource))
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 45_000, 500, 1_500).setTargetBufferBytes(16 * 1024 * 1024).build())
                .build().also { player = it }
            muted = true
            engine.volume = 0f
            engine.repeatMode = Player.REPEAT_MODE_ONE
            engine.setMediaItem(MediaItem.fromUri(candidates.getValue(next).url))
            engine.prepare()
            engine.playWhenReady = true
        }
    }
    fun toggleMute() { muted = !muted; player?.volume = if (muted) 0f else 1f }
    fun close() { scope.cancel(); player?.release(); player = null; candidates.clear() }
}

@Composable
fun MediaHost(store: MediaStore, reading: Boolean, content: @Composable () -> Unit) {
    val resources by store.resources.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val galleryVisible = LocalGalleryVisible.current
    val active = lifecycleState.isAtLeast(Lifecycle.State.STARTED) && !galleryVisible
    val context = LocalContext.current
    val playback = remember(resources, reading) { resources?.takeIf { reading }?.let { VisibleVideoPlayer(context, it) } }
    DisposableEffect(playback, lifecycle, galleryVisible) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { playback?.enabled(false); store.background() }
            if (event == Lifecycle.Event.ON_START) playback?.enabled(!galleryVisible)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(playback) { onDispose { playback?.close() } }
    SideEffect { playback?.enabled(active) }
    CompositionLocalProvider(LocalMedia provides MediaEnvironment(resources, active, playback), content = content)
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier, description: String? = null, crop: Boolean = true, avatar: Boolean = false, widthPx: Int? = null, onImageSize: ((Int, Int) -> Unit)? = null) {
    val media = LocalMedia.current
    val context = LocalContext.current
    val safeUrl = remember(url) { MediaContent.secureUrl(url) }
    val loader = media.resources?.images ?: return
    if (safeUrl == null) return
    val week = if (avatar) System.currentTimeMillis() / (7 * 24 * 60 * 60 * 1000L) else 0L
    var retries by remember(safeUrl, media.active) { mutableIntStateOf(0) }
    var failed by remember(safeUrl, media.active) { mutableStateOf(false) }
    LaunchedEffect(failed, media.active, retries) {
        if (failed && media.active && retries < 2) { delay(if (retries == 0) 1_000 else 4_000); failed = false; retries++ }
    }
    val request = remember(safeUrl, media.active, week, retries, widthPx) {
        ImageRequest.Builder(context).data(safeUrl)
            .diskCacheKey(if (avatar) "$safeUrl:avatar:$week" else safeUrl)
            .networkCachePolicy(if (media.active) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .apply { widthPx?.let { size(Size(Dimension(it.coerceAtLeast(1)), Dimension.Undefined)) } }
            .build()
    }
    key(safeUrl, media.active, retries) {
        AsyncImage(model = request, imageLoader = loader, contentDescription = description, modifier = modifier,
            onError = { failed = true }, onSuccess = {
                failed = false
                onImageSize?.invoke(it.result.image.width, it.result.image.height)
            },
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
    }
}

@Composable
fun NoteMedia(note: Event, attachments: List<MediaContent.Attachment>, mediaKey: String = note.id) {
    val media = LocalMedia.current
    val openGallery = LocalOpenGallery.current
    val gallery = remember(note.id) { MediaContent.attachments(note, limit = 32).filter { it.kind == MediaContent.Kind.IMAGE }.map { it.url } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        attachments.forEach { attachment ->
            key(attachment.url) {
                val identity = "$mediaKey:${attachment.url}"
                val playback = media.playback
                val selected = attachment.kind == MediaContent.Kind.VIDEO && playback?.activeKey == identity
                DisposableEffect(identity, playback) { onDispose { playback?.remove(identity) } }
                if (attachment.kind == MediaContent.Kind.IMAGE) {
                    InlineImage(attachment) { openGallery(gallery, gallery.indexOf(attachment.url)) }
                } else Box(Modifier.fillMaxWidth().aspectRatio(attachment.ratio).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                    .onGloballyPositioned { coordinates ->
                        if (attachment.kind == MediaContent.Kind.VIDEO) playback?.report(identity, attachment.url, coordinates.boundsInWindow(), coordinates.size.height.toFloat())
                    }) {
                        attachment.preview?.let { RemoteImage(it, Modifier.fillMaxSize(), attachment.alt ?: "Video preview") }
                        if (selected && playback.player != null) {
                            AndroidView(factory = { PlayerView(it).apply { useController = true; controllerAutoShow = false } },
                                modifier = Modifier.fillMaxSize(),
                                onRelease = { it.player = null },
                                update = { it.player = playback.player })
                            IconButton(onClick = playback::toggleMute, modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), RoundedCornerShape(24.dp))) {
                                Icon(painterResource(if (playback.muted) R.drawable.volume_off else R.drawable.volume_up), if (playback.muted) "Unmute video" else "Mute video", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                }
            }
        }
    }
}

/** Decode at the column width; source dimensions determine height, including portrait photos. */
@Composable
private fun InlineImage(attachment: MediaContent.Attachment, onOpen: () -> Unit) {
    var ratio by remember(attachment.url) { mutableFloatStateOf(ImageRatios.get(attachment.url) ?: attachment.ratio) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        RemoteImage(attachment.url,
            Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClickLabel = "Open image gallery", onClick = onOpen),
            attachment.alt ?: "Image", crop = false, widthPx = widthPx,
            onImageSize = { width, height ->
                ImageRatios.put(attachment.url, width, height)?.let { ratio = it }
            })
    }
}
