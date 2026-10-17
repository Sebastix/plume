@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.dergigi.feather

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*

/** Main-thread-only, process-local resume points shared across reader destinations. */
private object VideoPositions {
    private val positions = linkedMapOf<String, Long>()
    fun get(url: String): Long? = positions[url]
    fun put(url: String, position: Long) {
        positions.remove(url)
        positions[url] = position.coerceAtLeast(0L)
        while (positions.size > 64) positions.remove(positions.keys.first())
    }
}

/** A bounded window of visible clips: prepare neighbors, start the center, retain playing edges. */
class VisibleVideos(private val context: Context, private val resources: MediaStore.Resources) {
    private data class Candidate(val url: String, val bounds: Rect, val height: Float, val mime: String?)
    private val candidates = mutableMapOf<String, Candidate>()
    private val clips = mutableStateMapOf<String, VisibleVideoPlayer>()
    private val failedUntil = linkedMapOf<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selectionJob: Job? = null
    private var planned: List<String> = emptyList()
    private var enabled = true
    private var closed = false
    var viewport = Rect.Zero
        set(value) { if (field != value) { field = value; choose() } }
    var fullscreenPlayer by mutableStateOf<VisibleVideoPlayer?>(null)
        private set
    val fullscreen get() = fullscreenPlayer != null
    val preparedUrls get() = clips.values.map { it.url }.toSet()
    fun clip(key: String): VisibleVideoPlayer? = clips[key]

    fun report(key: String, url: String, bounds: Rect, height: Float, mime: String? = null) {
        if (closed) return
        candidates[key] = Candidate(url, bounds, height, mime)
        choose()
    }
    fun remove(key: String) { candidates.remove(key); choose() }
    fun enabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value
        clips.values.forEach { it.setActive(value && (!fullscreen || it === fullscreenPlayer)) }
        choose()
    }
    private fun visible(): List<String> = candidates.entries.mapNotNull { (key, candidate) ->
        if (candidate.bounds.right <= viewport.left || candidate.bounds.left >= viewport.right) return@mapNotNull null
        MediaContent.visibleScore(candidate.bounds.top, candidate.bounds.bottom, candidate.height, viewport.top, viewport.bottom)
            ?.let { key to it }
    }.sortedBy { it.second }.map { it.first }

    private fun choose() {
        if (closed || fullscreen) return
        val visible = visible()
        // No decoder or network activity for clips that have completely left the viewport.
        clips.keys.filter { it !in visible }.forEach { clips.remove(it)?.close() }
        if (!enabled) { selectionJob?.cancel(); planned = emptyList(); return }
        val now = SystemClock.elapsedRealtime()
        val available = visible.filter { (failedUntil[candidates[it]?.url] ?: 0L) <= now }
        // Playing edge clips retain their decoder until fully out of view. Three slots cover
        // the previous/current/next layout without unbounded decoders for a stack of tiny videos.
        val retained = available.filter { clips[it]?.started == true }
        val wanted = (retained + available).distinct().take(3)
        val center = available.firstOrNull { it in wanted }
        val plan = listOfNotNull(center) + wanted.filter { it != center }
        if (plan == planned && selectionJob?.isActive == true) return
        if (plan == planned && wanted.all { it in clips }) return
        selectionJob?.cancel()
        planned = plan
        selectionJob = scope.launch {
            // Let flings pass without setting up decoders, but don't restart this delay on
            // every pixel of a scroll when the same clips are still selected.
            delay(120)
            if (!enabled || fullscreen || closed) return@launch
            clips.keys.filter { it !in wanted }.forEach { clips.remove(it)?.close() }
            wanted.forEach { key ->
                if (key !in clips) {
                    val candidate = candidates[key] ?: return@forEach
                    runCatching {
                        VisibleVideoPlayer(context, resources, candidate.url, candidate.mime,
                            onFullscreen = ::setFullscreen, onAudible = ::makeAudible,
                            onError = { failure -> failed(key, failure) })
                    }.onSuccess { clips[key] = it }.onFailure { failed(key, candidate.url) }
                }
            }
            // Prepared paused players render their first frame. Promotion happens once;
            // moving off center never pauses a clip, and user pauses remain respected.
            center?.let { clips[it]?.promote() }
        }
    }
    private fun failed(key: String, url: String) {
        if (closed) return
        failedUntil.remove(url)
        failedUntil[url] = SystemClock.elapsedRealtime() + 30_000
        while (failedUntil.size > 64) failedUntil.remove(failedUntil.keys.first())
        // Defer teardown out of Media3's listener dispatch. Avoid retrying on each layout.
        scope.launch {
            yield()
            val clip = clips[key]
            if (clip?.url == url) {
                if (fullscreenPlayer === clip) clip.exitFullscreen()
                clips.remove(key)?.close()
            }
            planned = emptyList()
            choose()
        }
    }
    private fun makeAudible(selected: VisibleVideoPlayer) {
        clips.values.filter { it !== selected }.forEach { it.setVolume(0f) }
    }
    private fun setFullscreen(selected: VisibleVideoPlayer, open: Boolean) {
        if (closed) return
        selectionJob?.cancel(); planned = emptyList()
        fullscreenPlayer = if (open) selected else null
        clips.values.forEach { it.setActive(enabled && (!open || it === selected)) }
        if (!open) choose()
    }
    fun close() {
        closed = true; scope.cancel()
        clips.values.toList().forEach { it.close() }; clips.clear(); candidates.clear()
        fullscreenPlayer = null
    }
}

/** One clip's native controls and timeline, shared by inline and fullscreen views. */
class VisibleVideoPlayer(
    context: Context,
    resources: MediaStore.Resources,
    val url: String,
    mime: String?,
    private val onFullscreen: (VisibleVideoPlayer, Boolean) -> Unit,
    private val onAudible: (VisibleVideoPlayer) -> Unit,
    private val onError: (String) -> Unit,
) {
    var player: ExoPlayer? = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(resources.videoSource))
        .setAudioAttributes(AudioAttributes.DEFAULT, false)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 12_000, 500, 1_500)
            .setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
        .build()
        private set
    var started = false
        private set
    var muted by mutableStateOf(true)
        private set
    var fullscreen by mutableStateOf(false)
        private set
    private var active = true
    private var resume = false
    private var inlineView: PlayerView? = null
    private var fullscreenView: PlayerView? = null

    init {
        try {
        player?.apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) VideoRatios.put(url, size.width * size.pixelWidthHeightRatio / size.height)
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (active && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) started = true
                }
                override fun onPlayerError(error: PlaybackException) { onError(url) }
            })
            setMediaItem(MediaItem.Builder().setUri(url).setMimeType(mime).build())
            VideoPositions.get(url)?.let { seekTo(it) }
            prepare() // Media3 renders the first frame even with playWhenReady=false.
        }
        } catch (error: Exception) {
            player?.release(); player = null
            throw error
        }
    }
    fun promote() {
        if (started || !active) return
        started = true
        player?.play()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        if (!value) rememberPosition()
        active = value
        if (!value) {
            resume = player?.playWhenReady == true
            player?.pause()
            player?.stop() // Release decoder/loading while covered or backgrounded; retain position.
        } else {
            VideoPositions.get(url)?.let { player?.seekTo(it) }
            player?.prepare()
            if (resume) player?.play()
        }
    }
    private fun rememberPosition() {
        val engine = player ?: return
        // Prepared neighbors must not replace a watched clip's position with zero.
        // Live streams should keep their default live position instead of an old offset.
        if (active && started && !engine.isCurrentMediaItemLive) {
            VideoPositions.put(url, engine.currentPosition)
        }
    }
    fun enterFullscreen() {
        if (player == null || fullscreen) return
        promote()
        fullscreen = true
        onFullscreen(this, true)
    }
    fun exitFullscreen() {
        if (!fullscreen) return
        player?.let { PlayerView.switchTargetView(it, fullscreenView, inlineView) }
        fullscreenView = null
        fullscreen = false
        onFullscreen(this, false)
    }
    fun bindInline(view: PlayerView) {
        inlineView = view
        if (!fullscreen) {
            if (view.player !== player) view.player = player
            view.setFullscreenButtonState(false)
        }
    }
    fun unbindInline(view: PlayerView) {
        view.player = null
        if (inlineView === view) inlineView = null
    }
    fun bindFullscreen(view: PlayerView) {
        player?.let { PlayerView.switchTargetView(it, inlineView, view) }
        fullscreenView = view
    }
    fun unbindFullscreen(view: PlayerView) {
        view.player = null
        if (fullscreenView === view) fullscreenView = null
    }
    fun setVolume(value: Float) {
        val nextMuted = value <= 0f
        if (!nextMuted) onAudible(this)
        if (muted != nextMuted) player?.setAudioAttributes(AudioAttributes.DEFAULT, !nextMuted)
        player?.volume = value.coerceIn(0f, 1f)
        muted = nextMuted
    }
    fun toggleMute() { setVolume(if (muted) 1f else 0f) }
    fun close() {
        rememberPosition()
        inlineView?.player = null; fullscreenView?.player = null
        inlineView = null; fullscreenView = null
        player?.release(); player = null
    }
}
