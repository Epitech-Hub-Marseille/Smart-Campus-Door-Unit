package com.smartcampus.door.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders one loop of each emergency alarm from a few oscillators, so the door unit needs no sound
 * files: the alarm works offline, cannot be tampered with in transit and starts instantly.
 *
 * Each kind has its own pattern, in the meaning people already know (see the functions below), and
 * every loop ends in silence or on its own first note, so it repeats without a click.
 */
object AlarmSynth {
    const val SAMPLE_RATE = 22_050

    /** Where each alarm is loud enough to be heard on a small speaker: no content below ~100 Hz alone. */
    fun cycle(kind: String, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val track = when (kind) {
            "fire" -> fire(sampleRate)
            "biohazard" -> biohazard(sampleRate)
            "nuclear" -> nuclear(sampleRate)
            "bomb" -> bomb(sampleRate)
            "seismic" -> seismic(sampleRate)
            "power" -> power(sampleRate)
            else -> fire(sampleRate) // an unknown kind from a newer server is still an alarm
        }
        return track.render()
    }

    // The same alarms, with the same numbers, as the Faculty screens (alarmSynth.ts). Each follows the convention people know for
    // its meaning but is our own sound. Pulses use odd harmonics (hollow, carries on a small speaker) with a short attack and
    // release, and every loop ends in silence or on its own first note, so it repeats without a click.
    private val hollow = listOf(1.0 to 1.0, 3.0 to 0.33, 5.0 to 0.2, 7.0 to 0.1)
    private val horn = listOf(1.0 to 1.0, 2.0 to 0.5, 3.0 to 0.35, 4.0 to 0.15)
    private val siren = listOf(1.0 to 1.0, 2.0 to 0.25, 3.0 to 0.12)
    private val bell = listOf(1.0 to 1.0, 2.76 to 0.35, 5.4 to 0.12)

    /** ISO 8201 "temporal 3": three pulses of 0.5 s with 0.5 s between, then 1.5 s of silence (4 s). */
    private fun fire(rate: Int) = Track(4.0, rate).apply {
        for (i in 0 until 3) tone(i * 1.0, 0.5, 960.0, harmonics = hollow, attack = 0.006, release = 0.02)
    }

    /** A "hi-lo": four pairs of a high and a low tone, then a breath (3.2 s): leave calmly and at once. */
    private fun bomb(rate: Int) = Track(3.2, rate).apply {
        for (i in 0 until 4) {
            tone(i * 0.7, 0.35, 990.0, harmonics = siren, attack = 0.008, release = 0.02)
            tone(i * 0.7 + 0.35, 0.35, 660.0, harmonics = siren, attack = 0.008, release = 0.02)
        }
    }

    /** A slow wail, up for two seconds and down for two, with a slight tremble (4 s): stay where you are. */
    private fun biohazard(rate: Int) = Track(4.0, rate).apply {
        tone(0.0, 2.0, 520.0, 880.0, harmonics = siren, attack = 0.03, release = 0.03, tremoloHz = 5.0, tremoloDepth = 0.2)
        tone(2.0, 2.0, 880.0, 520.0, harmonics = siren, attack = 0.03, release = 0.03, tremoloHz = 5.0, tremoloDepth = 0.2)
    }

    /** A deep horn that slowly falls, with a short high ping as it starts (4 s): shelter now. */
    private fun nuclear(rate: Int) = Track(4.0, rate).apply {
        tone(0.0, 3.4, 330.0, 262.0, harmonics = horn, attack = 0.06, release = 0.2, tremoloHz = 3.0, tremoloDepth = 0.18)
        tone(0.0, 0.25, 1760.0, gain = 0.3, harmonics = siren, attack = 0.004, release = 0.15)
    }

    /** A fast yelp, a quick rising sweep eight times, over a low rumble, then a pause (3.6 s). */
    private fun seismic(rate: Int) = Track(3.6, rate).apply {
        for (i in 0 until 8) tone(i * 0.3, 0.27, 700.0, 1500.0, harmonics = siren, attack = 0.005, release = 0.03)
        tone(0.0, 2.5, 95.0, 85.0, gain = 0.55, harmonics = listOf(1.0 to 1.0, 2.0 to 0.8, 3.0 to 0.5, 4.0 to 0.3), attack = 0.1, release = 0.3, tremoloHz = 8.0, tremoloDepth = 0.5)
    }

    /** A soft, falling three-note bell (6 s). Not a siren: nobody is in danger, they need to be informed. */
    private fun power(rate: Int) = Track(6.0, rate).apply {
        for ((i, hz) in listOf(659.0, 523.0, 440.0).withIndex()) tone(i * 0.6, 1.4, hz, harmonics = bell, attack = 0.006, release = 0.5, decay = 2.0)
    }

    private class Track(val seconds: Double, val rate: Int) {
        private val samples = DoubleArray((seconds * rate).toInt())

        /**
         * Adds one note. [from]/[to] sweep the pitch linearly; [harmonics] are (multiple, level);
         * [fmIndex]/[fmRatio] add phase modulation; [decay] makes the note die away like a bell.
         */
        fun tone(
            at: Double,
            length: Double,
            from: Double,
            to: Double = from,
            gain: Double = 1.0,
            harmonics: List<Pair<Double, Double>> = listOf(1.0 to 1.0),
            attack: Double = 0.01,
            release: Double = 0.05,
            tremoloHz: Double = 0.0,
            tremoloDepth: Double = 0.0,
            fmIndex: Double = 0.0,
            fmRatio: Double = 1.0,
            decay: Double = 0.0,
        ) {
            val start = (at * rate).toInt()
            val count = (length * rate).toInt()
            val norm = harmonics.sumOf { it.second }
            var phase = 0.0
            for (n in 0 until count) {
                val index = start + n
                if (index >= samples.size) break
                val t = n.toDouble() / rate
                phase += 2 * PI * (from + (to - from) * (n.toDouble() / count)) / rate
                val modulated = phase + fmIndex * sin(fmRatio * phase)
                val voice = harmonics.sumOf { (multiple, level) -> level * sin(multiple * modulated) } / norm
                val envelope = min(1.0, t / attack) * min(1.0, (length - t) / release) * exp(-decay * t)
                val tremolo = 1 - tremoloDepth * (0.5 + 0.5 * sin(2 * PI * tremoloHz * t))
                samples[index] += voice * envelope * tremolo * gain
            }
        }

        fun render(): ShortArray {
            val peak = max(1e-9, samples.maxOf(::abs))
            return ShortArray(samples.size) { (samples[it] / peak * PEAK_LEVEL * Short.MAX_VALUE).toInt().toShort() }
        }
    }

    /** Headroom below full scale, so the speaker amplifier does not clip the loudest note. */
    private const val PEAK_LEVEL = 0.9
}
