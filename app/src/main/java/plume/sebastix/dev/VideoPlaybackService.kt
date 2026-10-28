@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package plume.sebastix.dev

import kotlinx.coroutines.*
import androidx.media3.common.MediaMetadata
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Exposes the selected visible player, without creating another decoder or restarting it. */
class VideoPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkJob: Job? = null
    private val speedCommand = SessionCommand("plume.sebastix.dev.video.CYCLE_SPEED", Bundle.EMPTY)
    private val backCommand = SessionCommand("plume.sebastix.dev.video.SEEK_BACK", Bundle.EMPTY)
    private val forwardCommand = SessionCommand("plume.sebastix.dev.video.SEEK_FORWARD", Bundle.EMPTY)
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION)) refreshControls()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(1002).build().apply { setSmallIcon(R.drawable.notification_feather) })
        attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        attach()
        if (selected == null) stopSelf(startId)
        return result
    }

    private fun attach() {
        val clip = selected
        val player = clip?.player
        if (player != null && session?.player === player) return
        clearSession()
        if (player == null) return
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        clip?.sourceNote?.let { intent.action = Intent.ACTION_VIEW; intent.data = Uri.parse("nostr:${it.nostrPointer()}") }
        val activity = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setId("video").setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    if (controller.packageName == packageName || controller.isTrusted)
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                            .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                                .add(speedCommand).add(backCommand).add(forwardCommand).build())
                            .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM)
                                .remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_NEXT)
                                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build())
                            .build()
                    else MediaSession.ConnectionResult.reject()

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    val engine = session.player
                    when (customCommand.customAction) {
                        backCommand.customAction -> if (engine.isCommandAvailable(Player.COMMAND_SEEK_BACK)) engine.seekBack()
                        forwardCommand.customAction -> if (engine.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) engine.seekForward()
                        speedCommand.customAction -> engine.setPlaybackSpeed(audioSpeeds[(audioSpeeds.indexOf(engine.playbackParameters.speed) + 1) % audioSpeeds.size])
                        else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build().also { addSession(it) }
        player.addListener(listener)
        refreshControls()
        artworkJob = artworkScope.launch {
            fun show(bytes: ByteArray) {
                if (session?.player !== player || selected !== clip) return
                val index = player.currentMediaItemIndex
                val item = player.currentMediaItem ?: return
                player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                    .setArtworkUri(null).setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build())
            }
            try {
                show(VideoArtwork.fallback(this@VideoPlaybackService))
                VideoArtwork.load(this@VideoPlaybackService, clip, ::show)?.let(::show)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Optional artwork must never interrupt playback. */ }
        }
    }

    private fun refreshControls() {
        val current = session ?: return
        val player = current.player
        val buttons = listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setSessionCommand(backCommand)
                .setDisplayName(getString(R.string.ui_rewind)).setSlots(CommandButton.SLOT_BACK)
                .setEnabled(player.isCommandAvailable(Player.COMMAND_SEEK_BACK)).build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10).setSessionCommand(forwardCommand)
                .setDisplayName(getString(R.string.ui_forward)).setSlots(CommandButton.SLOT_FORWARD)
                .setEnabled(player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)).build(),
            CommandButton.Builder(CommandButton.ICON_UNDEFINED).setCustomIconResId(audioSpeedIcon(player.playbackParameters.speed))
                .setSessionCommand(speedCommand).setDisplayName(getString(R.string.ui_change_speed, audioSpeedLabel(player.playbackParameters.speed)))
                .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW).build(),
        )
        current.setCustomLayout(buttons)
        current.setMediaButtonPreferences(buttons)
    }

    private fun clearSession() {
        artworkJob?.cancel(); artworkJob = null
        session?.let { it.player.removeListener(listener); removeSession(it); it.release() }
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlayback()
        stopSelf()
    }

    override fun onDestroy() {
        artworkScope.cancel()
        clearSession()
        if (instance === this) {
            instance = null
            selected?.player?.pause()
            selected = null
        }
        super.onDestroy()
    }

    companion object {
        private var instance: VideoPlaybackService? = null
        private var selected: VisibleVideoPlayer? = null
        internal fun owns(clip: VisibleVideoPlayer) = selected === clip
        internal val active get() = selected != null

        /** Main thread; called only for a foreground video selected by sound or fullscreen. */
        internal fun select(context: Context, clip: VisibleVideoPlayer) {
            if (selected === clip) return
            selected?.player?.pause()
            selected = clip
            (context.applicationContext as PlumeApplication).audio.player?.pause()
            try {
                context.applicationContext.startService(Intent(context, VideoPlaybackService::class.java))
                instance?.attach()
            } catch (_: IllegalStateException) {
                selected = null
                instance?.attach()
            }
        }
        internal fun detach(clip: VisibleVideoPlayer) {
            if (selected !== clip) return
            selected = null
            instance?.attach()
        }
        internal fun stopPlayback() {
            selected?.player?.pause()
            selected = null
            instance?.attach()
        }
    }
}
