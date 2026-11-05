package com.example.audiobook

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

fun buildBookMediaItems(book: Audiobook, artworkBytes: ByteArray? = null): List<MediaItem> {
    return book.tracks.map { track ->
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setAlbumTitle(book.series)
            .setDisplayTitle(track.name)
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            metaBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        MediaItem.Builder()
            .setUri(track.uri)
            .setMediaId(track.uri.toString())
            .setMediaMetadata(metaBuilder.build())
            .build()
    }
}

@OptIn(UnstableApi::class)
class AudiobookPlaybackService : MediaSessionService() {
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private lateinit var repository: AudiobookRepository

    companion object {
        private const val TAG = "FablewoodService"
        const val ACTION_SET_SILENCE_TRIM = "com.example.audiobook.SET_SILENCE_TRIM"
        const val ACTION_SET_VOLUME_BOOST = "com.example.audiobook.SET_VOLUME_BOOST"
        const val KEY_ENABLED = "enabled"

        const val ACTION_NOTIFICATION_REWIND = "com.example.audiobook.ACTION_NOTIFICATION_REWIND"
        const val ACTION_NOTIFICATION_FORWARD = "com.example.audiobook.ACTION_NOTIFICATION_FORWARD"

        const val ACTION_WIDGET_PLAY_PAUSE = "com.example.audiobook.ACTION_WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_REWIND = "com.example.audiobook.ACTION_WIDGET_REWIND"
        const val ACTION_WIDGET_FORWARD = "com.example.audiobook.ACTION_WIDGET_FORWARD"
    }

    override fun onCreate() {
        super.onCreate()
        repository = AudiobookRepository(applicationContext)
        val prefs = repository.getPreferences()
        val initialSkipMs = (prefs.getInt("skip_interval", 15).coerceIn(5, 60)) * 1000L

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(initialSkipMs)
            .setSeekForwardIncrementMs(initialSkipMs)
            .build()

        player.skipSilenceEnabled = prefs.getBoolean("silence_trim", false)

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupLoudnessEnhancer(audioSessionId, prefs.getBoolean("volume_boost", false))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    repository.markPauseTime()
                    persistCurrentProgress()
                }
                FablewoodWidgetProvider.updateAllWidgets(
                    context = applicationContext,
                    isPlayingOverride = isPlaying
                )
            }
        })

        exoPlayer = player

        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .build()
            }

            override fun seekBack() {
                skipByOffsetMs(-getSkipIntervalMs())
            }

            override fun seekForward() {
                skipByOffsetMs(getSkipIntervalMs())
            }
        }

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rewindButton = CommandButton.Builder()
            .setDisplayName("Rewind 15s")
            .setIconResId(android.R.drawable.ic_media_rew)
            .setSessionCommand(SessionCommand(ACTION_NOTIFICATION_REWIND, Bundle.EMPTY))
            .build()

        val forwardButton = CommandButton.Builder()
            .setDisplayName("Fast Forward 15s")
            .setIconResId(android.R.drawable.ic_media_ff)
            .setSessionCommand(SessionCommand(ACTION_NOTIFICATION_FORWARD, Bundle.EMPTY))
            .build()

        val customButtonsList = ImmutableList.of(rewindButton, forwardButton)

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCustomLayout(customButtonsList)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ACTION_SET_SILENCE_TRIM, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SET_VOLUME_BOOST, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_NOTIFICATION_REWIND, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_NOTIFICATION_FORWARD, Bundle.EMPTY))
                        .build()

                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                        .buildUpon()
                        .add(Player.COMMAND_SEEK_BACK)
                        .add(Player.COMMAND_SEEK_FORWARD)
                        .build()

                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        .setCustomLayout(customButtonsList)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    val enabled = args.getBoolean(KEY_ENABLED, false)
                    when (customCommand.customAction) {
                        ACTION_SET_SILENCE_TRIM -> {
                            exoPlayer?.skipSilenceEnabled = enabled
                        }
                        ACTION_SET_VOLUME_BOOST -> {
                            val sessionId = exoPlayer?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
                            setupLoudnessEnhancer(sessionId, enabled)
                        }
                        ACTION_NOTIFICATION_REWIND -> {
                            skipByOffsetMs(-getSkipIntervalMs())
                        }
                        ACTION_NOTIFICATION_FORWARD -> {
                            skipByOffsetMs(getSkipIntervalMs())
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    private fun getSkipIntervalMs(): Long {
        val secs = repository.getPreferences().getInt("skip_interval", 15).coerceIn(5, 60)
        return secs * 1000L
    }

    private fun skipByOffsetMs(deltaMs: Long) {
        val player = exoPlayer ?: return
        ensureBookLoadedInPlayer(player)
        val duration = player.duration.let { if (it > 0L) it else Long.MAX_VALUE }
        val targetPos = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(targetPos)
        persistCurrentProgress(positionOverrideMs = targetPos)
        FablewoodWidgetProvider.updateAllWidgets(
            context = applicationContext,
            isPlayingOverride = player.isPlaying
        )
    }

    private fun ensureBookLoadedInPlayer(player: ExoPlayer): Audiobook? {
        val books = repository.loadBooksCache()
        val lastId = repository.getLastBookId()
        val activeBook = books.find { it.id == lastId } ?: books.firstOrNull() ?: return null

        if (player.mediaItemCount == 0) {
            val savedTrack = repository.getBookTrackIndex(activeBook.id)
            val savedPos = repository.getBookPosition(activeBook.id)
            val lastPause = repository.getPreferences().getLong("last_pause_time", 0L)
            val rewindPos = calculateSmartRewindPos(savedPos, lastPause)
            val items = buildBookMediaItems(activeBook, null)
            player.setMediaItems(items)
            val validIdx = if (savedTrack in items.indices) savedTrack else 0
            player.seekTo(validIdx, rewindPos)
            player.prepare()
        }
        return activeBook
    }

    private fun persistCurrentProgress(positionOverrideMs: Long? = null) {
        val player = exoPlayer ?: return
        if (player.mediaItemCount <= 0) return
        val lastId = repository.getLastBookId() ?: return
        if (lastId.isEmpty()) return
        val totalListen = repository.getPreferences().getLong("total_listen_ms", 0L)
        val pos = (positionOverrideMs ?: player.currentPosition).coerceAtLeast(0L)
        repository.saveBookProgress(
            bookId = lastId,
            trackIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = pos,
            totalListenMs = totalListen,
            immediate = true
        )
    }

    private fun setupLoudnessEnhancer(audioSessionId: Int, enabled: Boolean) {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            if (enabled && audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId > 0) {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(900)
                    setEnabled(true)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LoudnessEnhancer setup failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val player = exoPlayer ?: return START_STICKY
        when (intent?.action) {
            ACTION_WIDGET_PLAY_PAUSE -> {
                val book = ensureBookLoadedInPlayer(player)
                if (player.isPlaying) {
                    player.pause()
                } else if (book != null) {
                    repository.touchBookLastPlayed(book.id)
                    player.playWhenReady = true
                    player.play()
                }
                FablewoodWidgetProvider.updateAllWidgets(
                    context = applicationContext,
                    isPlayingOverride = player.isPlaying,
                    titleOverride = book?.title,
                    authorOverride = book?.author
                )
            }
            ACTION_WIDGET_REWIND, ACTION_NOTIFICATION_REWIND -> {
                skipByOffsetMs(-getSkipIntervalMs())
            }
            ACTION_WIDGET_FORWARD, ACTION_NOTIFICATION_FORWARD -> {
                skipByOffsetMs(getSkipIntervalMs())
            }
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = exoPlayer
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistCurrentProgress()
        try {
            loudnessEnhancer?.release()
        } catch (_: Throwable) {}
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        super.onDestroy()
    }
}
// END OF FILE