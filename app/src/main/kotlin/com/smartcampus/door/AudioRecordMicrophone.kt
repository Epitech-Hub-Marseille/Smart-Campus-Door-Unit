package com.smartcampus.door

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.smartcampus.door.core.audio.Microphone
import com.smartcampus.door.core.protocol.LIVE_SAMPLE_RATE

/**
 * The terminal's microphone as 16 kHz mono 16-bit PCM, read in 100 ms slices on a thread of its own. The voice-communication source
 * is used because Android applies echo cancellation and noise suppression to it on most devices, which matters for a speaker
 * that stands in a corridor. Returns false when the microphone cannot be opened (permission refused, no microphone, in use).
 */
class AudioRecordMicrophone : Microphone {
    @Volatile private var record: AudioRecord? = null
    private var reader: Thread? = null

    @SuppressLint("MissingPermission") // checked by the caller; a refusal makes the constructor or startRecording fail, handled below
    @Synchronized
    override fun start(onChunk: (ByteArray) -> Unit): Boolean {
        stop()
        val slice = LIVE_SAMPLE_RATE / 10 * 2
        val minimum = AudioRecord.getMinBufferSize(LIVE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return false
        val opened = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, LIVE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, slice * 4))
        }.getOrNull() ?: return false
        if (opened.state != AudioRecord.STATE_INITIALIZED) {
            opened.release()
            return false
        }
        if (runCatching { opened.startRecording() }.isFailure || opened.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            opened.release()
            return false
        }
        record = opened
        reader = Thread({
            val buffer = ByteArray(slice)
            while (record === opened) {
                val n = opened.read(buffer, 0, buffer.size)
                if (n <= 0) break
                onChunk(buffer.copyOf(n))
            }
        }, "live-microphone").also { it.start() }
        return true
    }

    @Synchronized
    override fun stop() {
        val current = record ?: return
        record = null
        runCatching { current.stop() }
        runCatching { current.release() }
        reader = null
    }
}
