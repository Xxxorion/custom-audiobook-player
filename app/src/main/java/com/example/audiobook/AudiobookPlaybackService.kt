package com.example.audiobook

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AudiobookPlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var volumeBoostEnabled: Boolean = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: AudiobookRepository

    companion object {
        private const val TAG = "Fablewood"
        private const val ROOT_ID = "fablewood_root"
        const val ACTION_SET_SILENCE_TRIM = "com.example.audiobook.SET_SILENCE_TRIM"
        const val ACTION_SET_VOLUME_BOOST = "com.example.audiobook.SET_VOLUME_BOOST"
        const val KEY_ENABLED = "key_enabled"
    }

    override fun onCreate() {
        super.onCreate()
        repository = AudiobookRepository(applicationContext)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        exoPlayer = ExoPlayer.Builder(applicationContext)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        if (volumeBoostEnabled) {
                            applyVolumeBoost(true)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        FablewoodWidgetProvider.updateAllWidgets(
                            context = applicationContext,
                            isPlayingOverride = isPlaying,
                            titleOverride = mediaMetadata.title?.toString(),
                            authorOverride = mediaMetadata.artist?.toString()
                        )
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        FablewoodWidgetProvider.updateAllWidgets(
                            context = applicationContext,
                            isPlayingOverride = isPlaying,
                            titleOverride = mediaMetadata.title?.toString(),
                            authorOverride = mediaMetadata.artist?.toString()
                        )
                    }
                })
            }

        val sessionIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val callback = object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                    .add(SessionCommand(ACTION_SET_SILENCE_TRIM, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SET_VOLUME_BOOST, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.accept(
                    sessionCommands,
                    connectionResult.availablePlayerCommands
                )
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    ACTION_SET_SILENCE_TRIM -> {
                        val enabled = args.getBoolean(KEY_ENABLED, false)
                        exoPlayer?.skipSilenceEnabled = enabled
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    ACTION_SET_VOLUME_BOOST -> {
                        val enabled = args.getBoolean(KEY_ENABLED, false)
                        volumeBoostEnabled = enabled
                        applyVolumeBoost(enabled)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootItem = MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Fablewood Audiobooks")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                if (parentId != ROOT_ID) {
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
                val books = repository.loadBooksCache()
                val items = books.map { book ->
                    MediaItem.Builder()
                        .setMediaId("book:${book.id}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(book.title)
                                .setArtist(book.author)
                                .setAlbumTitle(book.series)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                                .build()
                        )
                        .build()
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val single = mediaItems.singleOrNull()
                if (single != null && single.mediaId.startsWith("book:")) {
                    val bookId = single.mediaId.removePrefix("book:")
                    val book = repository.loadBooksCache().find { it.id == bookId }
                    if (book != null) {
                        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                        serviceScope.launch {
                            val coverBmp = repository.getOrFetchCoverArt(book)
                            val artBytes = repository.bitmapToJpegBytes(coverBmp)
                            val savedTrack = repository.getBookTrackIndex(book.id)
                            val savedPos = repository.getBookPosition(book.id)
                            val lastPause = repository.getPreferences().getLong("last_pause_time", 0L)
                            val resumePos = calculateSmartRewindPos(savedPos, lastPause)
                            val trackItems = buildBookMediaItems(book, artBytes)
                            val validIdx = if (savedTrack in trackItems.indices) savedTrack else 0
                            future.set(MediaSession.MediaItemsWithStartPosition(trackItems, validIdx, resumePos))
                        }
                        return future
                    }
                }
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
        }

        mediaLibrarySession = MediaLibrarySession.Builder(this, exoPlayer!!, callback)
            .setSessionActivity(pendingIntent)
            .build()
    }

    private fun applyVolumeBoost(enabled: Boolean) {
        val player = exoPlayer ?: return
        if (!enabled) {
            releaseLoudnessEnhancer()
            return
        }
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) {
            Log.w(TAG, "Audio session ID not ready yet; deferring volume boost")
            return
        }
        releaseLoudnessEnhancer()
        try {
            val enhancer = LoudnessEnhancer(sessionId)
            val candidateGains = intArrayOf(1200, 1000, 800, 600, 0)
            var appliedGain = 0
            for (gain in candidateGains) {
                try {
                    enhancer.setTargetGain(gain)
                    appliedGain = gain
                    break
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Target gain ${gain}mB rejected by device, stepping down", e)
                }
            }
            enhancer.enabled = true
            loudnessEnhancer = enhancer
            Log.i(TAG, "LoudnessEnhancer enabled with gain ${appliedGain}mB")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize LoudnessEnhancer", t)
        }
    }

    private fun releaseLoudnessEnhancer() {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing LoudnessEnhancer", t)
        }
        loudnessEnhancer = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        FablewoodWidgetProvider.updateAllWidgets(applicationContext, isPlayingOverride = false)
        serviceScope.cancel()
        releaseLoudnessEnhancer()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        exoPlayer = null
        super.onDestroy()
    }
}

fun buildBookMediaItems(book: Audiobook, artworkJpegBytes: ByteArray? = null): List<MediaItem> {
    return book.tracks.map { track ->
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setAlbumTitle(book.series)
            .setDisplayTitle(track.name)

        if (artworkJpegBytes != null && artworkJpegBytes.isNotEmpty()) {
            try {
                metaBuilder.setArtworkData(artworkJpegBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            } catch (t: Throwable) {
                Log.w("Fablewood", "Failed setting artwork data on MediaItem", t)
            }
        }

        MediaItem.Builder()
            .setUri(track.uri)
            .setMediaId(track.uri.toString())
            .setMediaMetadata(metaBuilder.build())
            .build()
    }
}
// END OF FILE