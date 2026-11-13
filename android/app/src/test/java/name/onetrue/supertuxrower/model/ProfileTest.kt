package name.onetrue.supertuxrower.model

import name.onetrue.supertuxrower.testProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTest {
    @Test
    fun `2k power follows the standard pace-to-power relation`() {
        // 8:00 for 2000 m is 4.1667 m/s; 2.8 × 4.1667³ ≈ 202.5 W.
        assertEquals(202.5, testProfile.twoKWatts, 0.1)
    }

    @Test
    fun `the speed at full 2k power is 2k pace`() {
        assertEquals(2000.0 / 480, testProfile.speedAt(1.0), 1e-9)
    }

    @Test
    fun `watts map to the profile's bands`() {
        assertEquals(Band.TR, testProfile.band(testProfile.twoKWatts * 0.9))
        assertEquals(Band.REST, testProfile.band(10.0))
    }

    @Test
    fun formatting() {
        assertEquals("2:00.0", formatSplit(500.0 / 120))
        assertEquals("–:––", formatSplit(0.0))
        assertEquals("1:05", formatClock(65.9))
        assertEquals("0:00", formatClock(-3.0))
    }
}
