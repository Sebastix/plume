@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.dergigi.feather

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** A single on-demand player, released when the preview closes. */
@Composable
internal fun ComposerVideoPreview(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resources = (context.applicationContext as FeatherApplication).media.resources.value
    val player = remember(url, resources) {
        ExoPlayer.Builder(context).apply {
            resources?.let { setMediaSourceFactory(DefaultMediaSourceFactory(it.videoSource)) }
        }.build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            AndroidView(factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.Default.Close, stringResource(R.string.ui_close_video_preview), tint = Color.White)
            }
        }
    }
}
