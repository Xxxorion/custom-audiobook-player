package com.example.audiobook

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.SystemClock
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

fun buildBookMediaItems(book: Audiobook, artworkBytes: ByteArray? = null): List<MediaItem> {
    return book.tracks.map { track ->
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setAlbumTitle(book.series)
            .setDisplayTitle(track.name)
            .setExtras(Bundle().apply { putString("book_id", book.id) })
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playbackTickJob: Job? = null
    private var sleepCountdownJob: Job? = null
    private var chaptersLoadJob: Job? = null

    private var activeBookChapters: List<Chapter> = emptyList()
    private var activeChaptersBookId: String = ""
    private var lastPlayWallClockRealtimeMs: Long = 0L
    private var sleepTargetRealtimeMs: Long = 0L

    companion object {
        private const val TAG = "FablewoodService"
        const val ACTION_SET_SILENCE_TRIM = "com.example.audiobook.SET_SILENCE_TRIM"
        const val ACTION_SET_VOLUME_BOOST = "com.example.audiobook.SET_VOLUME_BOOST"
        const val ACTION_SET_SKIP_INTERVAL = "com.example.audiobook.SET_SKIP_INTERVAL"
        const val ACTION_START_TIMED_SLEEP = "com.example.audiobook.START_TIMED_SLEEP"
        const val ACTION_START_EOC_SLEEP = "com.example.audiobook.START_EOC_SLEEP"
        const val ACTION_CANCEL_SLEEP = "com.example.audiobook.CANCEL_SLEEP"

        const val KEY_ENABLED = "enabled"
        const val KEY_SKIP_SECONDS = "skip_seconds"
        const val KEY_SLEEP_MINUTES = "sleep_minutes"

        const val ACTION_NOTIFICATION_REWIND = "com.example.audiobook.ACTION_NOTIFICATION_REWIND"
        const val ACTION_NOTIFICATION_FORWARD = "com.example.audiobook.ACTION_NOTIFICATION_FORWARD"

        const val ACTION_WIDGET_PLAY_PAUSE = "com.example.audiobook.ACTION_WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_REWIND = "com.example.audiobook.ACTION_WIDGET_REWIND"
        const val ACTION_WIDGET_FORWARD = "com.example.audiobook.ACTION_WIDGET_FORWARD"

        @Volatile
        private var instance: AudiobookPlaybackService? = null

        private val _sleepTimerActiveFlow = MutableStateFlow(false)
        val sleepTimerActiveFlow: StateFlow<Boolean> = _sleepTimerActiveFlow.asStateFlow()

        private val _sleepTimeRemainingMsFlow = MutableStateFlow(0L)
        val sleepTimeRemainingMsFlow: StateFlow<Long> = _sleepTimeRemainingMsFlow.asStateFlow()

        private val _sleepEndAtChapterFlow = MutableStateFlow(false)
        val sleepEndAtChapterFlow: StateFlow<Boolean> = _sleepEndAtChapterFlow.asStateFlow()

        private val _totalListenMsFlow = MutableStateFlow(0L)
        val totalListenMsFlow: StateFlow<Long> = _totalListenMsFlow.asStateFlow()

        fun syncInitialTotalListenMs(storedMs: Long) {
            if (storedMs > _totalListenMsFlow.value) {
                _totalListenMsFlow.value = storedMs
            }
        }

        fun startTimedSleepFromUi(minutes: Int) {
            val durationMs = minutes.coerceAtLeast(1) * 60_000L
            _sleepEndAtChapterFlow.value = false
            _sleepTimeRemainingMsFlow.value = durationMs
            _sleepTimerActiveFlow.value = true
            instance?.activateTimedSleepInternal(durationMs)
        }

        fun startEndOfChapterSleepFromUi() {
            _sleepEndAtChapterFlow.value = true
            _sleepTimeRemainingMsFlow.value = 0L
            _sleepTimerActiveFlow.value = true
            instance?.activateEndOfChapterSleepInternal()
        }

        fun cancelSleepTimerFromUi() {
            _sleepTimerActiveFlow.value = false
            _sleepEndAtChapterFlow.value = false
            _sleepTimeRemainingMsFlow.value = 0L
            instance?.cancelSleepTimerInternal()
        }

        fun refreshSkipIntervalFromUi(seconds: Int) {
            instance?.updateSkipIntervalInternal(seconds)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = AudiobookRepository(applicationContext)
        val prefs = repository.getPreferences()
        val storedListenMs = prefs.getLong("total_listen_ms", 0L)
        _totalListenMsFlow.value = max(_totalListenMsFlow.value, storedListenMs)

        val initialSkipSecs = prefs.getInt("skip_interval", 15).coerceIn(5, 60)
        val initialSkipMs = initialSkipSecs * 1000L

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
                if (isPlaying) {
                    lastPlayWallClockRealtimeMs = SystemClock.elapsedRealtime()
                    ensureChaptersLoadedForCurrentBook()
                    if (_sleepTimerActiveFlow.value && !_sleepEndAtChapterFlow.value && _sleepTimeRemainingMsFlow.value > 0L) {
                        activateTimedSleepInternal(_sleepTimeRemainingMsFlow.value)
                    }
                    startServicePlaybackLoop()
                } else {
                    flushWallClockListenTime()
                    lastPlayWallClockRealtimeMs = 0L
                    playbackTickJob?.cancel()
                    repository.markPauseTime()
                    persistCurrentProgress(immediate = true)
                }
                FablewoodWidgetProvider.updateAllWidgets(
                    context = applicationContext,
                    isPlayingOverride = isPlaying
                )
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                ensureChaptersLoadedForCurrentBook()
                persistCurrentProgress(immediate = true)
                if (_sleepTimerActiveFlow.value && _sleepEndAtChapterFlow.value) {
                    exoPlayer?.pause()
                    cancelSleepTimerInternal()
                    persistCurrentProgress(immediate = true)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                    reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                ) {
                    val trackChanged = oldPosition.mediaItemIndex != newPosition.mediaItemIndex
                    if (trackChanged && _sleepTimerActiveFlow.value && _sleepEndAtChapterFlow.value) {
                        exoPlayer?.pause()
                        cancelSleepTimerInternal()
                    }
                    persistCurrentProgress(
                        positionOverrideMs = newPosition.positionMs.coerceAtLeast(0L),
                        immediate = true
                    )
                }
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

            override fun getSeekBackIncrement(): Long = getSkipIntervalMs()

            override fun getSeekForwardIncrement(): Long = getSkipIntervalMs()

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

        val initialCustomButtons = buildCustomSkipButtons(initialSkipSecs)

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCustomLayout(initialCustomButtons)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ACTION_SET_SILENCE_TRIM, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SET_VOLUME_BOOST, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SET_SKIP_INTERVAL, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_START_TIMED_SLEEP, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_START_EOC_SLEEP, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_CANCEL_SLEEP, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_NOTIFICATION_REWIND, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_NOTIFICATION_FORWARD, Bundle.EMPTY))
                        .build()

                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                        .buildUpon()
                        .add(Player.COMMAND_SEEK_BACK)
                        .add(Player.COMMAND_SEEK_FORWARD)
                        .build()

                    val currentButtons = buildCustomSkipButtons(getSkipIntervalSeconds())

                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        .setCustomLayout(currentButtons)
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
                        ACTION_SET_SKIP_INTERVAL -> {
                            val secs = args.getInt(KEY_SKIP_SECONDS, getSkipIntervalSeconds()).coerceIn(5, 60)
                            updateSkipIntervalInternal(secs)
                        }
                        ACTION_START_TIMED_SLEEP -> {
                            val mins = args.getInt(KEY_SLEEP_MINUTES, 15).coerceAtLeast(1)
                            startTimedSleepFromUi(mins)
                        }
                        ACTION_START_EOC_SLEEP -> {
                            startEndOfChapterSleepFromUi()
                        }
                        ACTION_CANCEL_SLEEP -> {
                            cancelSleepTimerFromUi()
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

    private fun buildCustomSkipButtons(skipSecs: Int): ImmutableList<CommandButton> {
        val cleanSecs = skipSecs.coerceIn(5, 60)
        val rewindButton = CommandButton.Builder()
            .setDisplayName("Rewind ${cleanSecs}s")
            .setIconResId(android.R.drawable.ic_media_rew)
            .setSessionCommand(SessionCommand(ACTION_NOTIFICATION_REWIND, Bundle.EMPTY))
            .build()

        val forwardButton = CommandButton.Builder()
            .setDisplayName("Fast Forward ${cleanSecs}s")
            .setIconResId(android.R.drawable.ic_media_ff)
            .setSessionCommand(SessionCommand(ACTION_NOTIFICATION_FORWARD, Bundle.EMPTY))
            .build()

        return ImmutableList.of(rewindButton, forwardButton)
    }

    private fun updateSkipIntervalInternal(seconds: Int) {
        val cleanSecs = seconds.coerceIn(5, 60)
        repository.getPreferences().edit().putInt("skip_interval", cleanSecs).apply()
        mediaSession?.setCustomLayout(buildCustomSkipButtons(cleanSecs))
        FablewoodWidgetProvider.updateAllWidgets(
            context = applicationContext,
            isPlayingOverride = exoPlayer?.isPlaying
        )
    }

    private fun activateTimedSleepInternal(durationMs: Long) {
        sleepCountdownJob?.cancel()
        sleepTargetRealtimeMs = SystemClock.elapsedRealtime() + durationMs
        _sleepEndAtChapterFlow.value = false
        _sleepTimeRemainingMsFlow.value = durationMs
        _sleepTimerActiveFlow.value = true

        sleepCountdownJob = serviceScope.launch {
            while (_sleepTimerActiveFlow.value && !_sleepEndAtChapterFlow.value) {
                val now = SystemClock.elapsedRealtime()
                val remaining = (sleepTargetRealtimeMs - now).coerceAtLeast(0L)
                _sleepTimeRemainingMsFlow.value = remaining
                if (remaining <= 0L) {
                    exoPlayer?.pause()
                    cancelSleepTimerInternal()
                    persistCurrentProgress(immediate = true)
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun activateEndOfChapterSleepInternal() {
        sleepCountdownJob?.cancel()
        _sleepEndAtChapterFlow.value = true
        _sleepTimeRemainingMsFlow.value = 0L
        _sleepTimerActiveFlow.value = true
        ensureChaptersLoadedForCurrentBook()
    }

    private fun cancelSleepTimerInternal() {
        sleepCountdownJob?.cancel()
        sleepCountdownJob = null
        sleepTargetRealtimeMs = 0L
        _sleepTimerActiveFlow.value = false
        _sleepEndAtChapterFlow.value = false
        _sleepTimeRemainingMsFlow.value = 0L
    }

    private fun ensureChaptersLoadedForCurrentBook() {
        val bookId = resolveActiveBookId()
        if (bookId.isEmpty() || (bookId == activeChaptersBookId && activeBookChapters.isNotEmpty())) return
        val book = repository.loadBooksCache().find { it.id == bookId } ?: return
        activeChaptersBookId = bookId
        chaptersLoadJob?.cancel()
        chaptersLoadJob = serviceScope.launch {
            activeBookChapters = repository.getBookChapters(book)
        }
    }

    private fun flushWallClockListenTime(): Long {
        val prefs = repository.getPreferences()
        val stored = prefs.getLong("total_listen_ms", 0L)
        var currentTotal = max(stored, _totalListenMsFlow.value)

        if (lastPlayWallClockRealtimeMs > 0L) {
            val now = SystemClock.elapsedRealtime()
            val delta = (now - lastPlayWallClockRealtimeMs).coerceIn(0L, 30_000L)
            lastPlayWallClockRealtimeMs = if (exoPlayer?.isPlaying == true) now else 0L
            if (delta > 0L) {
                currentTotal += delta
                prefs.edit().putLong("total_listen_ms", currentTotal).apply()
            }
        }
        _totalListenMsFlow.value = currentTotal
        return currentTotal
    }

    private fun startServicePlaybackLoop() {
        playbackTickJob?.cancel()
        playbackTickJob = serviceScope.launch {
            var secondsSinceSave = 0
            var previousChapterIdx = getCurrentChapterIndex(
                activeBookChapters,
                exoPlayer?.currentPosition ?: 0L,
                exoPlayer?.currentMediaItemIndex ?: 0
            )

            while (exoPlayer?.isPlaying == true) {
                delay(1000L)
                val player = exoPlayer ?: break
                if (!player.isPlaying) break

                flushWallClockListenTime()
                secondsSinceSave++

                val pos = player.currentPosition.coerceAtLeast(0L)
                val trackIdx = player.currentMediaItemIndex.coerceAtLeast(0)
                val dur = player.duration.coerceAtLeast(0L)
                val currChapIdx = getCurrentChapterIndex(activeBookChapters, pos, trackIdx)

                // Check End-of-Chapter Sleep Timer in Service (works with screen off & Activity gone)
                if (_sleepTimerActiveFlow.value && _sleepEndAtChapterFlow.value) {
                    val currChap = getCurrentChapter(activeBookChapters, pos, trackIdx)
                    val chapEndMs = if (currChap != null && currChap.durationMs > 0L) {
                        currChap.startMs + currChap.durationMs
                    } else {
                        dur
                    }
                    val crossedChapterBoundary = activeBookChapters.size > 1 && currChapIdx != previousChapterIdx
                    if (crossedChapterBoundary || (chapEndMs > 0L && pos >= chapEndMs - 1400L)) {
                        player.pause()
                        cancelSleepTimerInternal()
                        persistCurrentProgress(immediate = true)
                        break
                    }
                }

                // Save immediately on chapter boundary, or every 5 seconds while playing
                if (currChapIdx != previousChapterIdx) {
                    previousChapterIdx = currChapIdx
                    secondsSinceSave = 0
                    persistCurrentProgress(immediate = true)
                } else if (secondsSinceSave >= 5) {
                    secondsSinceSave = 0
                    persistCurrentProgress(immediate = true)
                }

                // Mark book completed if within 2 seconds of final track end
                if (!player.hasNextMediaItem() && dur > 0L && pos >= dur - 2000L) {
                    val bookId = resolveActiveBookId()
                    if (bookId.isNotEmpty()) {
                        repository.markBookCompleted(bookId)
                    }
                }
            }
        }
    }

    private fun resolveActiveBookId(): String {
        val player = exoPlayer
        val fromExtras = player?.currentMediaItem?.mediaMetadata?.extras?.getString("book_id")
        if (!fromExtras.isNullOrBlank()) return fromExtras
        return repository.getLastBookId() ?: ""
    }

    private fun getSkipIntervalSeconds(): Int {
        return repository.getPreferences().getInt("skip_interval", 15).coerceIn(5, 60)
    }

    private fun getSkipIntervalMs(): Long {
        return getSkipIntervalSeconds() * 1000L
    }

    private fun skipByOffsetMs(deltaMs: Long) {
        val player = exoPlayer ?: return
        ensureBookLoadedInPlayer(player)
        val duration = player.duration.let { if (it > 0L) it else Long.MAX_VALUE }
        val targetPos = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(targetPos)
        persistCurrentProgress(positionOverrideMs = targetPos, immediate = true)
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

    private fun persistCurrentProgress(positionOverrideMs: Long? = null, immediate: Boolean = true) {
        val player = exoPlayer ?: return
        if (player.mediaItemCount <= 0) return
        val bookId = resolveActiveBookId()
        if (bookId.isEmpty()) return
        val totalListen = flushWallClockListenTime()
        val pos = (positionOverrideMs ?: player.currentPosition).coerceAtLeast(0L)
        repository.saveBookProgress(
            bookId = bookId,
            trackIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = pos,
            totalListenMs = totalListen,
            immediate = immediate
        )
    }

    private fun setupLoudnessEnhancer(audioSessionId: Int, enabled: Boolean) {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            if (enabled && audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId > 0) {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(900) // +9 dB
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
        persistCurrentProgress(immediate = true)
        val player = exoPlayer
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistCurrentProgress(immediate = true)
        playbackTickJob?.cancel()
        sleepCountdownJob?.cancel()
        chaptersLoadJob?.cancel()
        serviceScope.cancel()
        if (instance === this) {
            instance = null
        }
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