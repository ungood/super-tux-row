package name.onetrue.supertuxrower.bob

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.exp

/**
 * Bob's voice, and the clock his typing follows when there is one. Sentences are spoken as soon as
 * they've streamed in. [spoken] says how far the speech has got, so the terminal never shows a word
 * before it's heard. The speaking rate is a smooth closed loop: it aims for a rate set by the
 * rower's speed, raised in proportion to the seconds of speech still waiting, and eases toward it
 * rather than jumping. Without a text-to-speech engine on the tablet it stays quiet and sets no pace.
 */
class Voice(context: Context) {
    private class Utterance(val start: Int, val length: Int, val rate: Float) {
        var began = 0L
        /** Characters into the utterance the engine has reported reaching, if it reports words. */
        var reached = -1
    }

    private val lock = Any()
    private val buffer = StringBuilder()
    private val queue = ArrayDeque<Utterance>()
    /** Characters handed to [add], in the same count the terminal types them. */
    private var received = 0
    /** Characters sent to the engine. */
    private var sent = 0
    /** Characters of finished utterances. */
    private var done = 0
    private var next = 0
    private val pacing = VoicePacing()

    var ready = false
        private set

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.getDefault()
    }.also {
        it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                synchronized(lock) { queue.firstOrNull()?.began = SystemClock.elapsedRealtime() }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                synchronized(lock) { queue.firstOrNull()?.reached = start }
            }

            override fun onDone(utteranceId: String?) = finish()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
        })
    }

    private fun finish() = synchronized(lock) {
        val u = queue.removeFirstOrNull() ?: return@synchronized
        done = u.start + u.length
        if (u.began > 0) pacing.measured(u.length, (SystemClock.elapsedRealtime() - u.began) / 1000.0, u.rate)
    }

    /** Adds streamed words; any sentences they complete are spoken at once. */
    fun add(text: String) = synchronized(lock) {
        received += text.length
        buffer.append(text)
        val (complete, rest) = completeSentences(buffer.toString())
        buffer.setLength(0)
        buffer.append(rest)
        if (complete.isNotEmpty()) speak(complete)
    }

    /** Speaks whatever is left, finished sentence or not. */
    fun flush() = synchronized(lock) {
        if (buffer.isNotEmpty()) speak(buffer.toString())
        buffer.setLength(0)
    }

    /** How many of the characters given to [add] have been heard; null if there's no voice to wait for. */
    fun spoken(): Int? = synchronized(lock) {
        if (!ready) return@synchronized null
        val u = queue.firstOrNull() ?: return@synchronized sent
        val into = when {
            u.reached >= 0 -> u.reached
            u.began > 0 -> (pacing.charsPerSecond * u.rate * (SystemClock.elapsedRealtime() - u.began) / 1000.0).toInt()
            else -> 0
        }
        u.start + into.coerceIn(0, u.length)
    }

    /** Seconds of speech still to come, including words not yet a whole sentence. */
    val secondsLeft: Double get() = synchronized(lock) { pacing.seconds(received - (spoken() ?: received)) }

    /** Steps the rate controller; [speed] is the boat speed in m/s. */
    fun update(dt: Double, speed: Double) = synchronized(lock) {
        pacing.update(dt, speed, received - (spoken() ?: received))
    }

    fun stop() = synchronized(lock) {
        buffer.setLength(0)
        queue.clear()
        if (ready) tts.stop()
        done = sent
        received = sent
    }

    fun shutdown() = tts.shutdown()

    private fun speak(text: String) {
        if (!ready) {
            sent += text.length
            done = sent
            return
        }
        val rate = pacing.rate.toFloat()
        queue.addLast(Utterance(sent, text.length, rate))
        sent += text.length
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "bob-${next++}")
    }
}

/**
 * The voice's rate controller, kept free of Android so it can be tested. The target rate follows
 * the rower's speed, plus a proportional push for every second of speech waiting beyond [slack];
 * the rate itself follows the target through a first-order lag of [easing] seconds.
 */
internal class VoicePacing(
    private val slack: Double = 2.0,
    private val gain: Double = 0.12,
    private val easing: Double = 4.0,
    private val min: Double = 0.85,
    private val max: Double = 1.7,
) {
    /** Characters a second the engine speaks at rate 1, learned from what it actually does. */
    var charsPerSecond = 14.0
        private set
    var rate = 1.0
        private set

    fun target(speed: Double, waiting: Double): Double =
        ((0.9 + 0.08 * speed) * (1 + gain * (waiting - slack).coerceAtLeast(0.0))).coerceIn(min, max)

    fun seconds(chars: Int): Double = chars.coerceAtLeast(0) / (charsPerSecond * rate)

    fun update(dt: Double, speed: Double, backlogChars: Int) {
        val goal = target(speed, seconds(backlogChars))
        rate += (goal - rate) * (1 - exp(-dt / easing))
    }

    /** Learns the engine's speed from an utterance of [chars] that took [seconds] at [rate]. */
    fun measured(chars: Int, seconds: Double, rate: Float) {
        if (chars < 20 || seconds < 0.5) return
        val cps = chars / seconds / rate
        charsPerSecond = (0.8 * charsPerSecond + 0.2 * cps).coerceIn(6.0, 30.0)
    }
}

/** Splits streamed text into the complete sentences in it and the unfinished rest. */
internal fun completeSentences(text: String): Pair<String, String> {
    val end = Regex("""[.!?…](["')\]]*)(\s+|$)|\n""").findAll(text)
        // Only once whitespace follows: "3." may yet become "3.5".
        .filter { it.value.last().isWhitespace() }
        .lastOrNull() ?: return "" to text
    return text.substring(0, end.range.last + 1) to text.substring(end.range.last + 1)
}
