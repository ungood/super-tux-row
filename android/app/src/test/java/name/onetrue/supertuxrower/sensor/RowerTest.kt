package name.onetrue.supertuxrower.sensor

import name.onetrue.supertuxrower.capture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowerTest {
    private fun Rower.feed(lines: List<Pair<Double, String>>) {
        lines.forEach { (t, line) -> handle(line, (t * 1000).toLong()) }
    }

    @Test
    fun `handshake marks the rower connected`() {
        val rower = Rower()
        rower.handle("_WR_", 0)
        assertTrue(rower.connected)
    }

    @Test
    fun `pulses add up to distance`() {
        val rower = Rower()
        // 96 pulses is a metre.
        repeat(24) { i -> rower.handle("P04", i * 24L) }
        assertEquals(1.0, rower.snapshot(24 * 24L).distance, 1e-9)
    }

    @Test
    fun `garbage lines are ignored`() {
        val rower = Rower()
        listOf("P", "PZZ", "IDS1A915", "ERROR", "\u000001").forEach { rower.handle(it, 0) }
        assertEquals(0.0, rower.snapshot(0).distance, 1e-9)
    }

    @Test
    fun `replaying a recorded session gives its distance, stroke rate and window length`() {
        val session = capture("row1.jsonl")
        val rower = Rower()
        rower.feed(session)
        val end = (session.last().first * 1000).toLong()
        val s = rower.snapshot(end)

        // 18702 pulses in the capture at 96 per metre.
        assertEquals(194.8, s.distance, 0.1)
        assertEquals(24, s.strokes)
        // This S4 sends a pulse line every ~23.9 ms, not 25.
        assertEquals(0.0239, rower.windowSeconds, 0.0005)
        assertTrue("stroke rate ${s.spm}", s.spm in 18.0..26.0)
    }

    @Test
    fun `rolling speed follows distance without stepping at each stroke`() {
        val session = capture("row1.jsonl")
        val rower = Rower()
        var lastSmooth = -1.0
        var biggestStep = 0.0
        var lastSecond = 0
        for ((t, line) in session) {
            val now = (t * 1000).toLong()
            rower.handle(line, now)
            // Sample the steady middle of the session, a few times a second.
            if (t in 10.0..30.0 && (t * 10).toInt() != lastSecond) {
                lastSecond = (t * 10).toInt()
                val smooth = rower.snapshot(now).smoothSpeed
                if (lastSmooth > 0) biggestStep = maxOf(biggestStep, kotlin.math.abs(smooth - lastSmooth))
                lastSmooth = smooth
            }
        }
        // The average speed over 10–30 s is ~3.3 m/s; one 0.1 s step should never move more than a few %.
        assertTrue("biggest step $biggestStep m/s", biggestStep < 0.15)
    }

    @Test
    fun `ping means the flywheel has stopped`() {
        val rower = Rower()
        rower.feed(capture("row1.jsonl").take(400))
        rower.handle("PING", 10_000)
        val s = rower.snapshot(10_000)
        assertEquals(0.0, s.liveSpeed, 1e-9)
        assertFalse(s.moving)
    }
}
