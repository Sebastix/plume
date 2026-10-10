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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
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
    var fullscreen by mutableStateOf(false)
        private set
    private var inlineView: PlayerView? = null
    private var fullscreenView: PlayerView? = null
    private var resumeFullscreen = false
    var muted by mutableStateOf(true)
        private set
    fun report(key: String, url: String, bounds: Rect, height: Float) {
        val score = MediaContent.visibleScore(bounds.top, bounds.bottom, height, viewport.top, viewport.bottom)
        if (score == null) candidates.remove(key) else candidates[key] = Candidate(url, score)
        choose()
    }
    fun remove(key: String) { candidates.remove(key); choose() }
    fun enabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (fullscreen) {
            if (!value) { resumeFullscreen = player?.playWhenReady == true; player?.pause() }
            else if (resumeFullscreen) player?.play()
        } else choose()
    }
    fun enterFullscreen() {
        if (player == null || activeKey == null) return
        selectionJob?.cancel()
        fullscreen = true
    }
    fun exitFullscreen() {
        if (!fullscreen) return
        PlayerView.switchTargetView(player!!, fullscreenView, inlineView)
        fullscreenView = null
        fullscreen = false
        choose()
    }
    fun bindInline(view: PlayerView) {
        inlineView = view
        if (!fullscreen && view.player !== player) view.player = player
    }
    fun unbindInline(view: PlayerView) {
        view.player = null
        if (inlineView === view) inlineView = null
    }
    fun bindFullscreen(view: PlayerView) {
        PlayerView.switchTargetView(player!!, inlineView, view)
        fullscreenView = view
    }
    fun unbindFullscreen(view: PlayerView) {
        view.player = null
        if (fullscreenView === view) fullscreenView = null
    }
    private fun choose() {
        if (fullscreen) return // Pin the same decoder and timeline while the feed is covered.
        // Retain the current clip until it leaves the viewport, rather than swapping
        // players whenever another visible clip becomes closer to the center.
        val next = if (enabled) activeKey?.takeIf { it in candidates }
            ?: candidates.minByOrNull { it.value.score }?.key else null
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
                .setAudioAttributes(AudioAttributes.DEFAULT, false)
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 45_000, 500, 1_500).setTargetBufferBytes(16 * 1024 * 1024).build())
                .build().also { player = it }
            setVolume(0f)
            engine.setPlaybackSpeed(1f)
            engine.repeatMode = Player.REPEAT_MODE_ONE
            engine.setMediaItem(MediaItem.fromUri(candidates.getValue(next).url))
            engine.prepare()
            engine.playWhenReady = true
        }
    }
    fun setVolume(value: Float) {
        val nextMuted = value <= 0f
        if (muted != nextMuted) player?.setAudioAttributes(AudioAttributes.DEFAULT, !nextMuted)
        player?.volume = value.coerceIn(0f, 1f)
        muted = nextMuted
    }
    fun toggleMute() { setVolume(if (muted) 1f else 0f) }
    fun close() {
        scope.cancel()
        inlineView?.player = null; fullscreenView?.player = null
        inlineView = null; fullscreenView = null
        player?.release(); player = null; candidates.clear()
    }
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
    CompositionLocalProvider(LocalMedia provides MediaEnvironment(resources, active, playback)) {
        content()
        if (playback?.fullscreen == true) FullscreenVideo(playback)
    }
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
    var decoded by remember(safeUrl) { mutableStateOf<coil3.Image?>(null) }
    var visible by remember(safeUrl) { mutableStateOf(false) }
    val view = LocalView.current
    ImageAnimation(decoded, visible && media.active && media.playback?.fullscreen != true)
    key(safeUrl, media.active, retries) {
        AsyncImage(model = request, imageLoader = loader, contentDescription = description, modifier = modifier.onGloballyPositioned {
                val bounds = it.boundsInWindow()
                visible = bounds.bottom > 0 && bounds.top < view.height && bounds.right > 0 && bounds.left < view.width
            },
            onError = { failed = true }, onSuccess = {
                failed = false
                decoded = it.result.image
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
                            var feedback by remember(identity) { mutableStateOf<VideoFeedback?>(null) }
                            var feedbackTick by remember(identity) { mutableIntStateOf(0) }
                            LaunchedEffect(feedbackTick) { delay(900); feedback = null }
                            AndroidView(factory = { GestureVideoView(it, playback, false) { value -> feedback = value; feedbackTick++ } },
                                modifier = Modifier.fillMaxSize(),
                                onRelease = playback::unbindInline,
                                update = playback::bindInline)
                            VideoGestureFeedback(feedback, Modifier.align(Alignment.Center))
                            IconButton(onClick = playback::enterFullscreen, modifier = Modifier.align(Alignment.TopStart).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), RoundedCornerShape(24.dp))) {
                                Icon(painterResource(R.drawable.fullscreen), "Fullscreen video", tint = MaterialTheme.colorScheme.primary)
                            }
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
