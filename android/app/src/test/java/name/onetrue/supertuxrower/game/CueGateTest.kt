package name.onetrue.supertuxrower.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueGateTest {
    @Test
    fun `a creature is heard once while it stays, and again after it's been gone a while`() {
        val gate = CueGate(gap = 2.0)
        assertTrue(gate.pass(Cue.PUFF, 7, 0.0))
        // Asked every frame while it's puffed up.
        var t = 0.0
        while (t < 3) {
            t += 1.0 / 30
            assertFalse(gate.pass(Cue.PUFF, 7, t))
        }
        // Another creature, or another sound, is its own.
        assertTrue(gate.pass(Cue.PUFF, 8, t))
        assertTrue(gate.pass(Cue.CLAM, 7, t))
        assertTrue(gate.pass(Cue.PUFF, 7, t + 2.5))
    }
}
