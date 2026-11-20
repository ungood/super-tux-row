package name.onetrue.supertuxrower.game

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class SynthTest {
    private fun peak(s: ShortArray) = s.maxOf { abs(it.toInt()) } / 32768.0

    /** Zero crossings per second, a rough measure of pitch. */
    private fun crossings(s: ShortArray): Double {
        var n = 0
        for (i in 1 until s.size) if ((s[i - 1] < 0) != (s[i] < 0)) n++
        return n.toDouble() * Synth.RATE / s.size
    }

    private fun all() = Cue.entries.flatMap { cue ->
        (0 until if (cue == Cue.CATCH) Synth.CATCH_STEPS else 1).map { v -> "$cue $v" to Synth.render(cue, v) }
    }

    @Test
    fun `every cue is audible, within range and starts and ends silent`() {
        for ((name, s) in all()) {
            assertTrue("$name is empty", s.isNotEmpty())
            assertTrue("$name is too quiet: ${peak(s)}", peak(s) > 0.3)
            assertTrue("$name is too loud: ${peak(s)}", peak(s) <= 0.8)
            assertTrue("$name starts with a click", abs(s.first().toInt()) < 300)
            assertTrue("$name ends with a click", abs(s.last().toInt()) < 300)
        }
    }

    @Test
    fun `catches climb in pitch with the combo`() {
        val rates = (0 until Synth.CATCH_STEPS).map { crossings(Synth.render(Cue.CATCH, it)) }
        for (k in 1 until rates.size) assertTrue("step $k: $rates", rates[k] > rates[k - 1])
    }

    @Test
    fun `the ambience loops without a seam`() {
        val s = Synth.ambience()
        assertTrue(peak(s) in 0.2..0.8)
        // Across the loop point it moves no more than it does between neighbouring samples.
        val biggestStep = (1 until s.size).maxOf { abs(s[it] - s[it - 1]) }
        assertTrue(abs(s.last() - s.first()) <= biggestStep)
    }

    @Test
    fun `renders are the same each time`() {
        assertTrue(Synth.render(Cue.BELUGA).contentEquals(Synth.render(Cue.BELUGA)))
        assertTrue(Synth.ambience().contentEquals(Synth.ambience()))
    }

    /** Not a check: leaves every sound as a WAV in build/sounds, to listen to. */
    @Test
    fun `write the sounds out`() {
        val dir = File("build/sounds").apply { mkdirs() }
        for ((name, s) in all()) {
            val file = if (name.startsWith("CATCH")) "catch${name.substringAfter(' ')}" else name.substringBefore(' ').lowercase()
            File(dir, "$file.wav").writeBytes(Synth.wav(s))
        }
        File(dir, "ambience.wav").writeBytes(Synth.wav(Synth.ambience()))
    }
}
