@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.dergigi.feather

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val voiceKinds = listOf(1222, 1244)
internal val podcastKinds = listOf(54, 10154)

internal data class AudioInfo(val url: String?, val mime: String?, val title: String?, val description: String,
    val image: String?, val duration: Long, val waveform: List<Float>)

/** Called off the display thread; never retrieve audio just to discover its duration. */
internal fun audioInfo(event: Event): AudioInfo {
    val voice = event.kind in voiceKinds
    val audio = event.tags.firstOrNull { it.firstOrNull() == "audio" && MediaContent.secureUrl(it.getOrNull(1)) != null }
    val url = if (voice) MediaContent.secureUrl(event.content.trim()) else audio?.getOrNull(1)
    val imeta = event.tags.firstOrNull { it.firstOrNull() == "imeta" && it.contains("url $url") }
        ?.drop(1)?.mapNotNull { val split = it.indexOf(' '); if (split > 0) it.take(split) to it.substring(split + 1) else null }?.toMap().orEmpty()
    val duration = (imeta["duration"] ?: event.tagValue("duration"))?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0 && it <= 604800 }?.let { (it * 1000).toLong() } ?: 0L
    val waveform = imeta["waveform"].orEmpty().splitToSequence(' ').take(100).mapNotNull(String::toFloatOrNull)
        .filter { it.isFinite() && it >= 0 }.toList()
    return AudioInfo(url, audio?.getOrNull(2)?.takeIf { it.startsWith("audio/") } ?: imeta["m"]?.takeIf { it.startsWith("audio/") },
        event.tagValue("title")?.take(300), if (voice) "" else (event.tagValue("description") ?: event.content).take(32768),
        MediaContent.secureUrl(event.tagValue("image")), duration, waveform)
}

/** One explicit, cached audio player per active reading screen; never autoplay. */
class AudioPlayback internal constructor(private val context: Context, private val resources: MediaStore.Resources) {
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var activeId by mutableStateOf<String?>(null)
        private set
    var requested by mutableStateOf(false)
        private set
    internal fun toggle(id: String, info: AudioInfo) {
        val url = info.url ?: return
        val current = player ?: ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resources.videoSource))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10000, 30000, 1000, 2000).setTargetBufferBytes(4 * 1024 * 1024).build())
            .build().also { created ->
                created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                created.setHandleAudioBecomingNoisy(true)
                created.trackSelectionParameters = created.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                created.addListener(object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { requested = playWhenReady }
                    override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) { created.pause(); requested = false } }
                    override fun onPlayerError(error: PlaybackException) {
                        created.pause(); requested = false
                        AppMessages.show("Audio: ${walletErrorText(error.message ?: error.errorCodeName)}")
                    }
                })
                player = created
            }
        if (activeId != id) {
            activeId = id
            current.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(info.mime).build())
            current.prepare(); current.play()
        } else if (current.playWhenReady) current.pause()
        else {
            if (current.playbackState == Player.STATE_ENDED) current.seekTo(0)
            if (current.playbackState == Player.STATE_IDLE) current.prepare()
            current.play()
        }
        requested = current.playWhenReady
    }
    fun pause() { player?.pause(); requested = false }
    fun close() { player?.release(); player = null; activeId = null; requested = false }
}

@Composable
internal fun AudioContent(note: Event, compact: Boolean) {
    val info by produceState<AudioInfo?>(null, note.id) { value = withContext(Dispatchers.Default) { audioInfo(note) } }
    val data = info ?: return
    val media = LocalMedia.current
    val audio = media.audio
    val player = audio?.player?.takeIf { audio.activeId == note.id }
    var position by remember(note.id) { mutableLongStateOf(0) }
    var duration by remember(note.id, data.duration) { mutableLongStateOf(data.duration) }
    var buffering by remember(note.id) { mutableStateOf(false) }
    var dragging by remember(note.id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(player, media.active) {
        if (player == null) { position = 0; buffering = false; return@LaunchedEffect }
        while (media.active) {
            position = player.currentPosition.coerceAtLeast(0)
            if (player.duration > 0) duration = player.duration
            buffering = player.playbackState == Player.STATE_BUFFERING
            delay(250)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        data.image?.let { RemoteImage(it, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)), data.title ?: "Podcast artwork") }
        data.title?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = if (compact) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis) }
        if (data.description.isNotBlank()) Text(data.description, style = MaterialTheme.typography.bodyLarge,
            maxLines = if (compact) 4 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        if (data.url != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp)) {
                if (data.waveform.isNotEmpty()) {
                    val color = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp)) {
                        val max = data.waveform.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                        val step = size.width / data.waveform.size
                        data.waveform.forEachIndexed { index, amplitude ->
                            val h = (amplitude / max * size.height).coerceAtLeast(2f)
                            drawLine(color, Offset((index + .5f) * step, (size.height - h) / 2),
                                Offset((index + .5f) * step, (size.height + h) / 2), strokeWidth = (step * .55f).coerceAtLeast(1f))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = media.active && audio != null, onClick = { audio?.toggle(note.id, data) }) {
                        if (buffering && audio?.requested == true) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else if (player != null && audio?.requested == true) Icon(painterResource(R.drawable.pause), "Pause audio")
                        else Icon(Icons.Default.PlayArrow, "Play audio")
                    }
                    Slider(value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { player?.seekTo((it * duration).toLong()) }; dragging = null },
                        enabled = player != null && duration > 0, modifier = Modifier.weight(1f))
                    Text(if (duration > 0) "${audioTime(position)} / ${audioTime(duration)}" else audioTime(position),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}
private fun audioTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
