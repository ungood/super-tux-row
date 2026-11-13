package name.onetrue.supertuxrower.bob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTest {
    @Test
    fun `words type out at the base rate`() {
        val t = Terminal()
        t.speak("Hello there")
        t.advance(0.5, baseRate = 10.0)
        assertEquals("Hello", t.all.last().visible)
    }

    @Test
    fun `a backlog speeds typing up so it never lags far`() {
        val t = Terminal(maxLag = 2.0)
        t.speak("x".repeat(400))
        t.advance(1.0, baseRate = 10.0)
        assertTrue("typed ${t.all.last().shown}", t.all.last().shown >= 200)
    }

    @Test
    fun `events appear at once, after flushing pending words`() {
        val t = Terminal()
        t.speak("Still typing this")
        t.show(Terminal.Kind.EVENT, "rower stopped")
        assertEquals("Still typing this", t.all[0].visible)
        assertEquals("rower stopped", t.all[1].visible)
        assertEquals(0, t.backlog)
    }

    @Test
    fun `speech continues a line until it breaks`() {
        val t = Terminal()
        t.speak("Hello, ")
        t.speak("world")
        t.breakLine()
        t.speak("Again\nand again")
        assertEquals(listOf("Hello, world", "Again", "and again"), t.all.map { it.text })
    }
}
