package name.onetrue.supertuxrower.game

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** The game's sound effects, each made by [Synth]. */
enum class Cue { CATCH, GOLD, MULTIPLIER, BONK, PIECE_START, REST_START, TICK, GO, FINISH, WHALE_SONG, ORCA, BELUGA, PUFF, CLAM }

/**
 * Makes the game's sounds from sine waves and filtered noise, so there are no sound files to source.
 * Every sound is heard hundreds of times in a long row, so they're soft and short, with rounded
 * attacks and no harsh high partials. Mono 16-bit at [RATE].
 */
object Synth {
    const val RATE = 22050

    /** How many pitches [Cue.CATCH] climbs through as the combo grows. */
    const val CATCH_STEPS = 8

    /** A major pentatonic from C5, so any run of catches sounds like a tune. */
    private val PENTATONIC = intArrayOf(0, 2, 4, 7, 9, 12, 14, 16)

    fun render(cue: Cue, variant: Int = 0): ShortArray = when (cue) {
        Cue.CATCH -> catchBlip(variant)
        Cue.GOLD -> gold()
        Cue.MULTIPLIER -> multiplier()
        Cue.BONK -> bonk()
        Cue.PIECE_START -> twoNotes(note(76), note(81), peak = 0.55)
        Cue.REST_START -> twoNotes(note(79), note(72), peak = 0.42)
        Cue.TICK -> tick()
        Cue.GO -> go()
        Cue.FINISH -> finish()
        Cue.WHALE_SONG -> whaleSong()
        Cue.ORCA -> orca()
        Cue.BELUGA -> beluga()
        Cue.PUFF -> puff()
        Cue.CLAM -> clam()
    }

    /**
     * A quiet underwater bed: a low rumble that swells and settles, with now and then a few bubbles.
     * It loops seamlessly: the extra tail is crossfaded into the head, and the swells repeat a whole
     * number of times per loop.
     */
    fun ambience(seconds: Double = 8.0): ShortArray {
        val random = Random(7)
        val n = (seconds * RATE).toInt()
        val fade = RATE / 2
        val out = FloatArray(n + fade)
        val a1 = coefficient(90.0)
        val a2 = coefficient(260.0)
        var low = 0.0
        var lower = 0.0
        var mid = 0.0
        for (i in out.indices) {
            val white = random.nextDouble() * 2 - 1
            low += a1 * (white - low)
            lower += a1 * (low - lower)
            mid += a2 * (white - mid)
            val t = i.toDouble() / RATE
            val swell = 0.75 + 0.25 * sin(2 * PI * 2 * t / seconds) + 0.1 * sin(2 * PI * 3 * t / seconds + 1.3)
            out[i] = (lower * 6.0 * swell + mid * 0.6 * (1.1 - swell)).toFloat()
        }
        // Bubbles: little rising blips, in a few small clusters.
        repeat(5) {
            var at = random.nextDouble() * (seconds - 0.6)
            repeat(2 + random.nextInt(4)) {
                val f = 500.0 + random.nextDouble() * 600
                voice(out, at, 0.07, { t -> f * (1 + t * 12) }, amp = 0.05 + random.nextDouble() * 0.04, attack = 0.004, decay = 30.0)
                at += 0.05 + random.nextDouble() * 0.15
            }
        }
        val loop = FloatArray(n)
        for (i in 0 until n) loop[i] = out[i]
        for (i in 0 until fade) {
            val w = i.toFloat() / fade
            loop[i] = out[i] * w + out[n + i] * (1 - w)
        }
        return finish(loop, peak = 0.5, fade = false)
    }

    /** A gulp: a bubble whose pitch flicks up, then a little higher pop. */
    private fun catchBlip(variant: Int): ShortArray {
        val f = note(72 + PENTATONIC[variant.coerceIn(0, CATCH_STEPS - 1)])
        val out = buffer(0.2)
        voice(out, 0.0, 0.12, { t -> f * (0.6 + 0.5 * (1 - exp(-t * 40))) }, amp = 0.8, attack = 0.004, decay = 22.0, partials = listOf(1.0 to 1.0, 2.0 to 0.15))
        voice(out, 0.055, 0.12, { f * 1.5 }, amp = 0.35, attack = 0.003, decay = 32.0, partials = listOf(1.0 to 1.0, 2.0 to 0.2))
        return finish(out, peak = 0.55)
    }

    /** A sparkling arpeggio up two octaves, with glints above it. */
    private fun gold(): ShortArray {
        val out = buffer(1.0)
        listOf(84, 88, 91, 96).forEachIndexed { k, m -> bell(out, k * 0.07, 0.8 - k * 0.07, note(m), 0.5) }
        sparkles(out, Random(11), 0.15, 0.85, 8)
        return finish(out, peak = 0.6)
    }

    private fun multiplier(): ShortArray {
        val out = buffer(0.8)
        listOf(79, 84, 88).forEachIndexed { k, m -> soft(out, k * 0.08, 0.12, note(m), 0.45) }
        soft(out, 0.24, 0.55, note(91), 0.5, decay = 5.0)
        soft(out, 0.24, 0.55, note(84), 0.25, decay = 5.0)
        return finish(out, peak = 0.6)
    }

    /** A rubbery boing against the ice: a falling, wobbling low note over a muffled thump. */
    private fun bonk(): ShortArray {
        val out = buffer(0.45)
        voice(out, 0.0, 0.42, { t -> (230.0 * exp(-t * 3)) * (1 + 0.06 * sin(2 * PI * 16 * t)) }, amp = 0.7, attack = 0.006, decay = 9.0, partials = listOf(1.0 to 1.0, 2.0 to 0.3, 3.0 to 0.08))
        noise(out, 0.0, 0.08, Random(3), cutoff = 500.0, amp = 0.5, decay = 45.0)
        return finish(out, peak = 0.6)
    }

    private fun twoNotes(first: Double, second: Double, peak: Double): ShortArray {
        val out = buffer(0.9)
        bell(out, 0.0, 0.5, first, 0.6)
        bell(out, 0.16, 0.74, second, 0.6)
        return finish(out, peak)
    }

    /** A wood-block knock, short enough to count along with. */
    private fun tick(): ShortArray {
        val out = buffer(0.09)
        voice(out, 0.0, 0.09, { 1100.0 }, amp = 0.6, attack = 0.002, decay = 55.0, partials = listOf(1.0 to 1.0, 2.76 to 0.25))
        noise(out, 0.0, 0.015, Random(5), cutoff = 2500.0, amp = 0.2, decay = 200.0)
        return finish(out, peak = 0.45)
    }

    private fun go(): ShortArray {
        val out = buffer(0.5)
        voice(out, 0.0, 0.5, { 1760.0 }, amp = 0.5, attack = 0.004, decay = 6.0, partials = listOf(1.0 to 1.0, 2.0 to 0.12))
        voice(out, 0.0, 0.5, { 880.0 }, amp = 0.35, attack = 0.004, decay = 6.0)
        return finish(out, peak = 0.55)
    }

    /** Up a C major arpeggio to a held chord, glinting as it fades. */
    private fun finish(): ShortArray {
        val out = buffer(2.1)
        listOf(72, 76, 79).forEachIndexed { k, m -> soft(out, k * 0.13, 0.16, note(m), 0.5) }
        for (m in listOf(72, 76, 79, 84)) bell(out, 0.42, 1.65, note(m), 0.32, decay = 2.2)
        soft(out, 0.42, 1.6, note(48), 0.3, decay = 2.0)
        sparkles(out, Random(13), 0.5, 1.7, 10)
        return finish(out, peak = 0.65)
    }

    /** A long low moan that rises and falls, echoing as if from far away. */
    private fun whaleSong(): ShortArray {
        val out = buffer(5.0)
        val call = { t: Double ->
            val glide = when {
                t < 1.6 -> 150 + 110 * smooth(t / 1.6)
                else -> 260 - 90 * smooth((t - 1.6) / 1.6)
            }
            glide * (1 + 0.02 * sin(2 * PI * 4.5 * t))
        }
        voice(out, 0.0, 3.4, call, amp = 0.6, attack = 0.6, decay = 0.0, release = 0.9, partials = listOf(1.0 to 1.0, 2.0 to 0.45, 3.0 to 0.2, 4.0 to 0.08))
        echo(out, delay = 0.29, feedback = 0.45, cutoff = 900.0)
        return finish(lowpass(out, 1400.0), peak = 0.6)
    }

    /** A dark swell: a low cluster a semitone apart with a trembling high pair, rising then cut off. */
    private fun orca(): ShortArray {
        val out = buffer(2.1)
        for ((f, a) in listOf(110.0 to 0.5, 116.54 to 0.4, 164.81 to 0.3)) {
            voice(out, 0.0, 2.0, { f }, amp = a, attack = 1.3, decay = 0.0, release = 0.6, partials = listOf(1.0 to 1.0, 2.0 to 0.5, 3.0 to 0.3, 4.0 to 0.15, 5.0 to 0.08))
        }
        for (f in listOf(622.25, 659.26)) {
            voice(out, 0.3, 1.7, { f }, amp = 0.12, attack = 1.0, decay = 0.0, release = 0.5, tremolo = 9.0)
        }
        return finish(lowpass(out, 2000.0), peak = 0.6)
    }

    /** Belugas chatter: whistles that swoop up and down, and trains of clicks. */
    private fun beluga(): ShortArray {
        val random = Random(17)
        val out = buffer(2.1)
        var at = 0.05
        while (at < 1.8) {
            val from = 1400 + random.nextDouble() * 1600
            val to = 1400 + random.nextDouble() * 2200
            val len = 0.07 + random.nextDouble() * 0.14
            voice(out, at, len, { t -> from + (to - from) * smooth(t / len) }, amp = 0.35, attack = 0.012, decay = 0.0, release = 0.03)
            at += len + 0.03 + random.nextDouble() * 0.12
            if (random.nextDouble() < 0.35) {
                val rate = 30 + random.nextDouble() * 40
                val clicks = 5 + random.nextInt(8)
                for (k in 0 until clicks) noise(out, at + k / rate, 0.006, random, cutoff = 3000.0, amp = 0.5, decay = 500.0)
                at += clicks / rate + 0.05
            }
        }
        return finish(echoed(out), peak = 0.5)
    }

    /** A pufferfish inflating: air rushing in under a rising bloop. */
    private fun puff(): ShortArray {
        val out = buffer(0.3)
        voice(out, 0.0, 0.28, { t -> 110 + 260 * smooth(t / 0.2) }, amp = 0.6, attack = 0.02, decay = 6.0, release = 0.06, partials = listOf(1.0 to 1.0, 2.0 to 0.25))
        noise(out, 0.0, 0.2, Random(19), cutoff = 900.0, amp = 0.35, decay = 12.0, attack = 0.04)
        return finish(out, peak = 0.5)
    }

    /** A clam shutting: a muffled clap. */
    private fun clam(): ShortArray {
        val out = buffer(0.18)
        noise(out, 0.0, 0.1, Random(23), cutoff = 1100.0, amp = 0.7, decay = 50.0)
        voice(out, 0.0, 0.16, { t -> 190 * exp(-t * 4) }, amp = 0.6, attack = 0.002, decay = 28.0)
        return finish(out, peak = 0.5)
    }

    /** A bell-like note: a pure tone with faint inharmonic partials, ringing down. */
    private fun bell(out: FloatArray, at: Double, length: Double, f: Double, amp: Double, decay: Double = 4.5) =
        voice(out, at, length, { f }, amp = amp, attack = 0.004, decay = decay, partials = listOf(1.0 to 1.0, 2.0 to 0.25, 3.01 to 0.1, 4.17 to 0.05))

    /** A rounded, hollow note, like a soft square wave. */
    private fun soft(out: FloatArray, at: Double, length: Double, f: Double, amp: Double, decay: Double = 8.0) =
        voice(out, at, length, { f }, amp = amp, attack = 0.006, decay = decay, partials = listOf(1.0 to 1.0, 3.0 to 0.2, 5.0 to 0.06))

    private fun sparkles(out: FloatArray, random: Random, from: Double, to: Double, count: Int) {
        repeat(count) {
            val f = 2200 + random.nextDouble() * 1800
            voice(out, from + random.nextDouble() * (to - from), 0.12, { f }, amp = 0.08 + random.nextDouble() * 0.06, attack = 0.003, decay = 30.0)
        }
    }

    /**
     * Adds a note to [out] from [at] seconds for [length]. [frequency] gives its pitch at each moment
     * (seconds since it started), so glides are smooth. It rises over [attack], dies away at [decay]
     * per second (0 holds), and fades out over [release] at the end so it never clicks.
     */
    private fun voice(
        out: FloatArray, at: Double, length: Double, frequency: (Double) -> Double,
        amp: Double, attack: Double, decay: Double, release: Double = 0.012,
        partials: List<Pair<Double, Double>> = listOf(1.0 to 1.0), tremolo: Double = 0.0,
    ) {
        val start = (at * RATE).toInt()
        val count = (length * RATE).toInt().coerceAtMost(out.size - start)
        val norm = partials.sumOf { it.second }
        var phase = 0.0
        for (i in 0 until count) {
            val t = i.toDouble() / RATE
            val f = frequency(t)
            phase += 2 * PI * f / RATE
            var v = 0.0
            for ((ratio, a) in partials) if (f * ratio < RATE / 2.2) v += a * sin(phase * ratio)
            val envelope = (t / attack).coerceAtMost(1.0) * exp(-t * decay) *
                ((length - t) / release).coerceIn(0.0, 1.0) *
                (if (tremolo > 0) 0.65 + 0.35 * sin(2 * PI * tremolo * t) else 1.0)
            out[start + i] += (amp * envelope * v / norm).toFloat()
        }
    }

    /** Adds a burst of low-passed noise, for thumps, claps and air. */
    private fun noise(out: FloatArray, at: Double, length: Double, random: Random, cutoff: Double, amp: Double, decay: Double, attack: Double = 0.001) {
        val start = (at * RATE).toInt()
        val count = (length * RATE).toInt().coerceAtMost(out.size - start)
        val a = coefficient(cutoff)
        var y = 0.0
        var z = 0.0
        for (i in 0 until count) {
            val t = i.toDouble() / RATE
            y += a * (random.nextDouble() * 2 - 1 - y)
            z += a * (y - z)
            val envelope = (t / attack).coerceAtMost(1.0) * exp(-t * decay) * ((length - t) / 0.004).coerceIn(0.0, 1.0)
            out[start + i] += (amp * 3 * envelope * z).toFloat()
        }
    }

    /** Feeds a darkened copy back in after [delay], like sound carrying through water. */
    private fun echo(out: FloatArray, delay: Double, feedback: Double, cutoff: Double) {
        val d = (delay * RATE).toInt()
        val a = coefficient(cutoff)
        var y = 0.0
        for (i in d until out.size) {
            y += a * (out[i - d] - y)
            out[i] += (y * feedback).toFloat()
        }
    }

    private fun echoed(out: FloatArray): FloatArray {
        echo(out, delay = 0.11, feedback = 0.3, cutoff = 2500.0)
        return out
    }

    private fun lowpass(out: FloatArray, cutoff: Double): FloatArray {
        val a = coefficient(cutoff)
        var y = 0.0
        for (i in out.indices) {
            y += a * (out[i] - y)
            out[i] = y.toFloat()
        }
        return out
    }

    /** Scales to [peak] and, with [fade], ramps the ends to silence so starting and stopping don't click. */
    private fun finish(out: FloatArray, peak: Double, fade: Boolean = true): ShortArray {
        if (fade) {
            val ramp = (0.004 * RATE).toInt()
            for (i in 0 until minOf(ramp, out.size)) {
                val w = i.toFloat() / ramp
                out[i] *= w
                out[out.size - 1 - i] *= w
            }
        }
        val max = out.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val scale = peak / max * Short.MAX_VALUE
        return ShortArray(out.size) { (out[it] * scale).toInt().toShort() }
    }

    /** A sound as a WAV file, for SoundPool to load and for listening to the sounds outside the app. */
    fun wav(samples: ShortArray): ByteArray {
        val bytes = ByteArrayOutputStream(44 + samples.size * 2)
        fun int(v: Int) = repeat(4) { bytes.write((v shr (8 * it)) and 0xFF) }
        fun short(v: Int) = repeat(2) { bytes.write((v shr (8 * it)) and 0xFF) }
        bytes.write("RIFF".toByteArray())
        int(36 + samples.size * 2)
        bytes.write("WAVEfmt ".toByteArray())
        int(16)
        short(1)
        short(1)
        int(RATE)
        int(RATE * 2)
        short(2)
        short(16)
        bytes.write("data".toByteArray())
        int(samples.size * 2)
        for (s in samples) short(s.toInt())
        return bytes.toByteArray()
    }

    private fun buffer(seconds: Double) = FloatArray((seconds * RATE).toInt())

    /** A MIDI note's frequency: 69 is A4, 72 is C5. */
    private fun note(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun smooth(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** A one-pole low-pass filter's coefficient for [cutoff] Hz. */
    private fun coefficient(cutoff: Double) = 1 - exp(-2 * PI * cutoff / RATE)
}
