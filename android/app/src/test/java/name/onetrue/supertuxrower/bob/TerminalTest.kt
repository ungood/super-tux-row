package name.onetrue.supertuxrower.bob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTest {
    /** What the screen would show: escape codes dropped, carriage returns ignored. */
    private fun plain(s: String) = s.replace(Regex("\u001b\\[[0-9;]*[A-Za-z]"), "").replace("\r", "")

    @Test
    fun `words type out at the base rate`() {
        val t = Terminal()
        t.speak("Hello there")
        assertEquals("● Hello", plain(t.advance(0.5, baseRate = 10.0)))
    }

    @Test
    fun `a backlog speeds typing up so it never lags far`() {
        val t = Terminal(maxLag = 2.0)
        t.speak("word ".repeat(80))
        val typed = plain(t.advance(1.0, baseRate = 10.0))
        assertTrue("typed ${typed.length}", typed.length >= 200)
    }

    @Test
    fun `long text wraps at word boundaries with a hanging indent`() {
        val t = Terminal().apply { columns = 20 }
        t.speak("The quick brown fox jumps over the lazy dog")
        t.breakLine()
        val lines = plain(t.advance(10.0, baseRate = 100.0)).split('\n')
        assertEquals(listOf("● The quick brown", "  fox jumps over the", "  lazy dog"), lines)
        assertTrue(lines.all { it.length <= 20 })
    }

    @Test
    fun `a word still arriving waits rather than being split`() {
        val t = Terminal().apply { columns = 20 }
        t.speak("Hello wor")
        assertEquals("● Hello", plain(t.advance(10.0, baseRate = 100.0)))
        t.speak("ld")
        t.breakLine()
        assertEquals(" world", plain(t.advance(10.0, baseRate = 100.0)))
    }

    @Test
    fun `blocks appear at once, after flushing pending words, with a gap between them`() {
        val t = Terminal()
        t.speak("Still typing this")
        t.show(Terminal.Kind.TOOL, "Bash(ls)")
        t.show(Terminal.Kind.RESULT, "a\nb")
        assertEquals("● Still typing this\n\n● Bash(ls)\n  ⎿  a\n     b", plain(t.advance(0.0, baseRate = 10.0)))
    }

    @Test
    fun `the status line shows when idle and is cleared by output`() {
        val t = Terminal()
        t.status("✻ Thinking…")
        assertEquals("✻ Thinking…", plain(t.advance(0.1, 10.0)))
        t.status(null)
        t.speak("Hi")
        t.breakLine()
        val out = t.advance(1.0, 10.0)
        assertTrue(out.contains("\u001b[2K"))
        assertEquals("● Hi", plain(out))
    }
}
