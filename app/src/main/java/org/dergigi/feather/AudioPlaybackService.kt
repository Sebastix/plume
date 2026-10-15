@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.dergigi.feather

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** The service owns playback, so closing a screen does not stop the audio. */
class AudioPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val speedCommand = SessionCommand("org.dergigi.feather.audio.CYCLE_SPEED", Bundle.EMPTY)

    private fun speedButtons(speed: Float): List<CommandButton> {
        val icon = when (speed) {
            1f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
            1.5f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
            2f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
            else -> CommandButton.ICON_PLAYBACK_SPEED
        }
        return listOf(CommandButton.Builder(icon).setSessionCommand(speedCommand)
            .setDisplayName("Speed ${audioSpeedLabel(speed)}; change speed")
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW).build())
    }
    private val media get() = (application as FeatherApplication).media

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The UI only connects after the media cache has initialized off-thread.
        val resources = media.resources.value ?: return
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resources.videoSource))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10000, 30000, 1000, 2000)
                .setTargetBufferBytes(4 * 1024 * 1024).build())
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(30000)
            .build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.setHandleAudioBecomingNoisy(true)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                session?.setMediaButtonPreferences(speedButtons(playbackParameters.speed))
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { media.audioActive = playWhenReady }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) player.pause()
            }
            override fun onPlayerError(error: PlaybackException) {
                player.pause()
                AppMessages.show("Audio: ${walletErrorText(error.message ?: error.errorCodeName)}")
            }
        })
        session = MediaSession.Builder(this, player)
            .setMediaButtonPreferences(speedButtons(player.playbackParameters.speed))
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    if (controller.packageName == packageName || controller.isTrusted)
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                            .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(speedCommand).build())
                            .build()
                    else MediaSession.ConnectionResult.reject()

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction != speedCommand.customAction)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                    val index = audioSpeeds.indexOf(session.player.playbackParameters.speed)
                    session.player.setPlaybackSpeed(audioSpeeds[(index + 1) % audioSpeeds.size])
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(
                    if (controller.packageName == packageName) mediaItems.take(1).filter {
                        MediaContent.secureUrl(it.localConfiguration?.uri?.toString()) != null
                    } else emptyList())
            }).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun releasePlayback() {
        session?.run { player.release(); release() }
        session = null
        media.audioActive = false
    }

    override fun onDestroy() {
        releasePlayback()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: AudioPlaybackService? = null
        /** Main thread, before the shared cache is closed/replaced on IO. */
        internal fun beforeCacheChange() {
            instance?.let { it.releasePlayback(); it.stopSelf() }
        }
    }
}
