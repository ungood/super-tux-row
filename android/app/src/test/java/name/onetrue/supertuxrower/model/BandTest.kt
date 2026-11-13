package name.onetrue.supertuxrower.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BandTest {
    @Test
    fun `fractions of 2k power map to bands`() {
        assertEquals(Band.REST, Band.forFraction(0.0))
        assertEquals(Band.REST, Band.forFraction(0.49))
        assertEquals(Band.UT2, Band.forFraction(0.50))
        assertEquals(Band.UT1, Band.forFraction(0.70))
        assertEquals(Band.AT, Band.forFraction(0.80))
        assertEquals(Band.TR, Band.forFraction(0.99))
        assertEquals(Band.AN, Band.forFraction(1.00))
        assertEquals(Band.AN, Band.forFraction(3.0))
    }

    @Test
    fun `position runs from 0 to 6 through the bands`() {
        assertEquals(0.0, Band.position(0.0), 1e-9)
        assertEquals(1.0, Band.position(0.50), 1e-9)
        assertEquals(1.5, Band.position(0.575), 1e-9)
        assertEquals(6.0, Band.position(5.0), 1e-9)
    }

    @Test
    fun `penguin names`() {
        assertEquals(
            listOf("Waddle", "Drift", "Glide", "Chase", "Surge", "Launch"),
            Band.entries.map { it.penguinName(rest = "Waddle") },
        )
    }
}
