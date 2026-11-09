package com.assistant.nova.core.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.content.ContextCompat

/**
 * Small offline music player for songs on the phone / SD card.
 *
 * It registers a media session, so "pause", "next song", "stop", headset buttons and
 * Bluetooth controls all reach it the same way they reach any other music app.
 * Music gets quieter automatically while Nova listens or talks, and pauses if
 * earphones are unplugged.
 */
class NovaPlayer(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var queue: List<Track> = emptyList()
    private var index = 0
    private var resumeOnFocusGain = false
    private var noisyRegistered = false

    val current: Track? get() = queue.getOrNull(index)
    val position: Int get() = index + 1
    val queueSize: Int get() = queue.size
    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** Called whenever playback changes: (track, playing). track is null once playback has stopped. */
    var onStateChanged: ((Track?, Boolean) -> Unit)? = null

    val sessionToken: MediaSession.Token get() = session.sessionToken

    fun togglePlayPause() = if (isPlaying) pause() else resume()

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (isPlaying) {
                    pause(); resumeOnFocusGain = true
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.2f, 0.2f)
                AudioManager.AUDIOFOCUS_GAIN -> {
                    player?.setVolume(1f, 1f)
                    if (resumeOnFocusGain) resume()
                    resumeOnFocusGain = false
                }
            }
        }
        .build()

    private val session = MediaSession(context, "Nova").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onStop() = stop()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
        })
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    fun play(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queue = tracks
        index = 0
        playCurrent()
    }

    private fun playCurrent() {
        val track = current ?: return stop()
        releasePlayer()
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setOnCompletionListener { next() }
            setOnErrorListener { _, _, _ -> next(); true }
            setOnPreparedListener { it.start(); updateState(PlaybackState.STATE_PLAYING) }
            try {
                setDataSource(context, track.uri)
                prepareAsync()
            } catch (_: Exception) {
                next(); return
            }
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .build(),
        )
        session.isActive = true
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED)
            noisyRegistered = true
        }
    }

    fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
        resumeOnFocusGain = false
        updateState(PlaybackState.STATE_PAUSED)
    }

    fun resume() {
        val p = player ?: return playCurrent()
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        runCatching { p.start() }
        updateState(PlaybackState.STATE_PLAYING)
    }

    fun next() {
        if (index + 1 < queue.size) {
            index++; playCurrent()
        } else {
            stop()
        }
    }

    fun previous() {
        val pos = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
        if (pos > 3_000 || index == 0) {
            runCatching { player?.seekTo(0) }
        } else {
            index--; playCurrent()
        }
    }

    fun stop() {
        releasePlayer()
        updateState(PlaybackState.STATE_STOPPED)
        session.isActive = false
        audio.abandonAudioFocusRequest(focusRequest)
        if (noisyRegistered) {
            runCatching { context.unregisterReceiver(noisy) }
            noisyRegistered = false
        }
    }

    fun release() {
        stop()
        session.release()
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private fun updateState(state: Int) {
        val pos = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(state, pos, 1f)
                .build(),
        )
        onStateChanged?.invoke(if (state == PlaybackState.STATE_STOPPED) null else current, state == PlaybackState.STATE_PLAYING)
    }
}
