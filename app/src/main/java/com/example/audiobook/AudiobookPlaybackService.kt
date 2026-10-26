package com.example.audiobook

import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
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
    private var volumeBoostEnabled: Boolean = false

    companion object {
        const val ACTION_SET_SILENCE_TRIM = "com.example.audiobook.SET_SILENCE_TRIM"
        const val ACTION_SET_VOLUME_BOOST = "com.example.audiobook.SET_VOLUME_BOOST"
        const val KEY_ENABLED = "key_enabled"
    }

    override fun onCreate() {
        super.onCreate()

        // Speech audio attributes ensure Android pauses (instead of ducking) during GPS directions or calls
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true) // Auto-pause when Bluetooth or headphones disconnect
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        if (volumeBoostEnabled) {
                            applyVolumeBoost(true)
                        }
                    }
                })
            }

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
        }

        mediaSession = MediaSession.Builder(this, exoPlayer!!)
            .setCallback(callback)
            .build()
    }

    private fun applyVolumeBoost(enabled: Boolean) {
        val player = exoPlayer ?: return
        try {
            if (enabled) {
                val sessionId = player.audioSessionId
                if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
                    loudnessEnhancer?.release()
                    loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                        setTargetGain(1200) // +12 dB boost for quiet audiobook recordings
                        this.enabled = true
                    }
                }
            } else {
                loudnessEnhancer?.enabled = false
                loudnessEnhancer?.release()
                loudnessEnhancer = null
            }
        } catch (_: Throwable) {}
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        } catch (_: Throwable) {}
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
// END OF FILE