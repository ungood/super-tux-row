package name.onetrue.supertuxrower.bob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeTest {
    @Test
    fun `met when the average holds the target`() {
        val c = Challenge(watts = 150, seconds = 10, terms = "a secret")
        repeat(5) { c.advance(140.0, 1.0) }
        assertFalse(c.done)
        repeat(10) { c.advance(170.0, 1.0) }
        assertTrue(c.done)
        assertEquals(155.0, c.averageWatts, 1e-9)
        assertTrue(c.met)
    }

    @Test
    fun `failed when it doesn't`() {
        val c = Challenge(watts = 150, seconds = 4, terms = "")
        repeat(4) { c.advance(100.0, 1.0) }
        assertTrue(c.done)
        assertFalse(c.met)
    }
}
