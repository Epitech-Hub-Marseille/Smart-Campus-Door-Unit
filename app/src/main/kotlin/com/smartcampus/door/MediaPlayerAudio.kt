package com.smartcampus.door

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.smartcampus.door.core.audio.AudioPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Plays a cached sound through the speaker and returns when it ends. */
class MediaPlayerAudio : AudioPlayer {
    override suspend fun play(file: File, volumePercent: Int) {
        suspendCancellableCoroutine { continuation ->
            val player = MediaPlayer()
            continuation.invokeOnCancellation { player.release() }
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        // An urgent read-out (full volume) goes out on the alarm stream, which the alarm has pushed to its maximum, so a low
                        // media volume set by an installer cannot make it a whisper.
                        .setUsage(if (volumePercent >= 100) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                player.setDataSource(file.absolutePath)
                val level = volumePercent.coerceIn(0, 100) / 100f
                player.setVolume(level, level)
                player.setOnCompletionListener {
                    it.release()
                    continuation.resume(Unit)
                }
                player.setOnErrorListener { mp, what, _ ->
                    mp.release()
                    continuation.resumeWithException(IllegalStateException("playback failed ($what)"))
                    true
                }
                player.setOnPreparedListener { it.start() }
                player.prepareAsync()
            } catch (e: Exception) {
                player.release()
                continuation.resumeWithException(e)
            }
        }
    }
}
