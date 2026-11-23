package com.smartcampus.door

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.smartcampus.door.core.audio.LivePlayer
import com.smartcampus.door.core.protocol.LIVE_SAMPLE_RATE
import java.util.concurrent.LinkedBlockingQueue

/**
 * With [voiceCall] it is the other side of a two-way call instead: the voice-communication stream, which is the one Android's echo
 * canceller listens to, so a terminal's microphone is not fed its own speaker.
 *
 * Plays someone speaking live: 16 kHz mono PCM written as it arrives to a streaming track, on the alarm stream pushed to its maximum
 * (like an emergency read-out), so an installer's low media volume cannot make a manual announcement a whisper.
 *
 * `write` is called from the connection's thread and must not block it, so the slices are queued and a worker thread feeds the
 * track, which blocks while the hardware buffer is full. Playback starts once a short cushion (150 ms) is in the track, so the
 * uneven way the network delivers the voice does not make it crackle.
 */
class AudioTrackLive(context: Context, private val voiceCall: Boolean = false) : LivePlayer {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var worker: Thread? = null
    private var queue: LinkedBlockingQueue<ByteArray>? = null

    @Synchronized
    override fun start() {
        stopNow()
        val stream = if (voiceCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_ALARM
        runCatching { audioManager.setStreamVolume(stream, audioManager.getStreamMaxVolume(stream), 0) }
        val pending = LinkedBlockingQueue<ByteArray>()
        queue = pending
        worker = Thread({ run(pending) }, "live-voice").also { it.start() }
    }

    @Synchronized
    override fun write(pcm: ByteArray) {
        queue?.offer(pcm)
    }

    /** Lets what was already received play out, then releases the track. */
    @Synchronized
    override fun stop() {
        queue?.offer(END)
        queue = null
        worker = null
    }

    private fun stopNow() {
        queue?.clear()
        queue?.offer(END)
        queue = null
        worker = null
    }

    private fun run(pending: LinkedBlockingQueue<ByteArray>) {
        val minimum = AudioTrack.getMinBufferSize(LIVE_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(if (voiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(LIVE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes((minimum * 4).coerceAtLeast(LIVE_SAMPLE_RATE * 2))
            .build()
        try {
            var written = 0
            var playing = false
            while (true) {
                val chunk = pending.take()
                if (chunk === END) break
                track.write(chunk, 0, chunk.size)
                written += chunk.size
                if (!playing && written >= CUSHION_BYTES) {
                    track.play()
                    playing = true
                }
            }
            // A short announcement may end before the cushion filled: it still has to be heard.
            if (!playing) track.play()
            // `stop` on a streaming track plays what is in its buffer before stopping.
            track.stop()
            Thread.sleep(DRAIN_MS)
        } catch (_: InterruptedException) {
            // a new announcement replaced this one
        } finally {
            runCatching { track.release() }
        }
    }

    private companion object {
        val END = ByteArray(0)
        const val CUSHION_BYTES = LIVE_SAMPLE_RATE * 2 * 150 / 1000
        const val DRAIN_MS = 300L
    }
}
