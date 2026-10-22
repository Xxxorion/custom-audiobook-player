package com.example.audiobook

import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class AudiobookPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    companion object {
        const val ACTION_SET_SILENCE_TRIM = "com.example.audiobook.SET_SILENCE_TRIM"
        const val ACTION_SET_VOLUME_BOOST = "com.example.audiobook.SET_VOLUME_BOOST"
        const val KEY_ENABLED = "key_enabled"
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer = ExoPlayer.Builder(this).build()

        val callback = object : MediaSession.Callback {
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
                when (customCommand.action) {
                    ACTION_SET_SILENCE_TRIM -> {
                        val enabled = args.getBoolean(KEY_ENABLED, false)
                        exoPlayer?.skipSilenceEnabled = enabled
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    ACTION_SET_VOLUME_BOOST -> {
                        val enabled = args.getBoolean(KEY_ENABLED, false)
                        applyVolumeBoost(enabled)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        mediaSession = MediaSession.Builder(this, exoPlayer!!)
            .setCallback(callback)
            .build()
    }

    private fun applyVolumeBoost(enabled: Boolean) {
        val player = exoPlayer ?: return
        try {
            if (enabled) {
                if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    if (loudnessEnhancer == null) {
                        loudnessEnhancer = LoudnessEnhancer(player.audioSessionId).apply {
                            setTargetGain(1200)
                        }
                    }
                    loudnessEnhancer?.enabled = true
                }
            } else {
                loudnessEnhancer?.enabled = false
                loudnessEnhancer?.release()
                loudnessEnhancer = null
            }
        } catch (_: Exception) {}
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        } catch (_: Exception) {}
        loudnessEnhancer = null

        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }
}