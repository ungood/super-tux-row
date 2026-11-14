package name.onetrue.supertuxrower.bob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceTest {
    @Test
    fun `each pace asks for its band of the rower's 2k power`() {
        assertEquals(100..130, Pace.DRIFT.watts(200.0))
        assertEquals(200..230, Pace.LAUNCH.watts(200.0))
    }

    @Test
    fun `paces are named as Bob names them`() {
        assertEquals(Pace.CHASE, Pace.of(" Chase"))
        assertNull(Pace.of("sprint"))
    }

    @Test
    fun `he thinks at the pace the rower actually holds, up to the one he asked for`() {
        // 2k power 200 W: Glide is 130–150 W, Chase 150–170 W.
        assertEquals(Pace.GLIDE, Pace.CHASE.thinkingAt(140.0, 200.0))
        assertEquals(Pace.CHASE, Pace.CHASE.thinkingAt(220.0, 200.0))
        assertEquals(Pace.DRIFT, Pace.CHASE.thinkingAt(0.0, 200.0))
        assertEquals(true, Pace.CHASE.held(160.0, 200.0))
        assertEquals(false, Pace.CHASE.held(180.0, 200.0))
    }

    @Test
    fun `tool output previews show a few lines and count the rest`() {
        assertEquals("a\nb\n… +2 lines", BobAgent.preview("a\nb\nc\nd\n", lines = 2))
    }
}

class VoiceTest {
    @Test
    fun `finished sentences are spoken as they arrive, the rest waits`() {
        assertEquals("Hello there. " to "I am", completeSentences("Hello there. I am"))
        assertEquals("" to "It costs 3.5", completeSentences("It costs 3.5"))
        assertEquals("One! Two? " to "", completeSentences("One! Two? "))
        assertEquals("Line one\n" to "next", completeSentences("Line one\nnext"))
    }
}

class VoicePacingTest {
    @Test
    fun `the rate eases toward its target instead of jumping`() {
        val p = VoicePacing()
        val behind = 14 * 10 // ten seconds of speech waiting
        p.update(0.5, speed = 2.5, backlogChars = behind)
        assertTrue("moved a little: ${p.rate}", p.rate > 1.0 && p.rate < 1.1)
        repeat(60) { p.update(0.5, speed = 2.5, backlogChars = behind) }
        assertTrue("near the target: ${p.rate}", p.rate > 1.5)
        // Caught up: it comes back down gradually, not in one step.
        p.update(0.5, speed = 2.5, backlogChars = 0)
        assertTrue("still fast: ${p.rate}", p.rate > 1.4)
    }

    @Test
    fun `a few seconds of backlog is slack, not a reason to hurry`() {
        val p = VoicePacing()
        assertEquals(p.target(2.5, waiting = 0.0), p.target(2.5, waiting = 2.0), 1e-9)
        assertTrue(p.target(2.5, waiting = 6.0) > p.target(2.5, waiting = 2.0))
        assertEquals(1.7, p.target(2.5, waiting = 100.0), 1e-9)
    }
}

class PacedTerminalTest {
    private fun plain(s: String) = s.replace(Regex("\u001b\\[[0-9;]*[A-Za-z]"), "").replace("\r", "")

    @Test
    fun `with a voice, words appear only as they're heard, and tools wait their turn`() {
        val t = Terminal()
        var heard = 0
        t.paceBy { heard }
        t.speak("Hello there friend. ")
        t.show(Terminal.Kind.TOOL, "Bash(ls)")
        assertEquals("", plain(t.advance(1.0, 100.0)))
        heard = 6
        assertEquals("● Hello", plain(t.advance(1.0, 100.0)))
        heard = 20
        assertEquals(" there friend.\n\n● Bash(ls)", plain(t.advance(1.0, 100.0)))
    }
}
