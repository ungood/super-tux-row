package name.onetrue.supertuxrower.bob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakePlannerTest {
    private val planner = WakePlanner(minimumGap = 4.0)

    private fun check(now: Double, rowing: Boolean = true, distance: Double = 0.0, balance: Double = 4000.0, speed: Double = 0.0, typing: Double = 0.0) =
        planner.check(now, rowing, distance, balance, speed, typing)

    @Test
    fun `routine wakes come sooner the faster the boat`() {
        assertEquals(30.0, planner.interval(0.0), 1e-9)
        assertEquals(15.5, planner.interval(3.3), 1e-9)
        assertEquals(10.0, planner.interval(5.0), 1e-9)
        check(0.0, rowing = false)
        assertNull(check(25.0, rowing = false))
        assertEquals(emptyList<String>(), check(30.0, rowing = false))
    }

    @Test
    fun `a routine wake waits for his words to finish typing`() {
        check(0.0, rowing = false)
        assertNull(check(35.0, rowing = false, typing = 5.0))
        assertEquals(emptyList<String>(), check(36.0, rowing = false, typing = 0.5))
    }

    @Test
    fun `starting and stopping wake him promptly`() {
        check(0.0, rowing = false)
        assertEquals(listOf("The rower just started rowing."), check(4.0, rowing = true, typing = 10.0))
        check(5.0, rowing = false)
        assertNull(check(7.0, rowing = false))
        assertEquals(listOf("The rower has stopped."), check(8.1, rowing = false))
    }

    @Test
    fun `noted events wake him, balance warnings wait`() {
        check(0.0)
        assertNull(check(5.0, balance = 900.0))
        planner.note("Challenge met.")
        assertEquals(listOf("Your balance just fell below 1000 tokens.", "Challenge met."), check(6.0, balance = 900.0))
    }
}
