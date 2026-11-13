package name.onetrue.supertuxrower.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakePlannerTest {
    private val planner = WakePlanner(interval = 15.0, minimumGap = 8.0, lead = 8.0)

    @Test
    fun `wakes at once, then every interval`() {
        assertEquals(emptyList<String>(), planner.check(0.0, rowing = false, distance = 0.0, balance = 1500.0))
        assertNull(planner.check(10.0, false, 0.0, 1500.0))
        assertEquals(emptyList<String>(), planner.check(15.0, false, 0.0, 1500.0))
    }

    @Test
    fun `starting to row wakes it early, but not within the minimum gap`() {
        planner.check(0.0, false, 0.0, 1500.0)
        assertNull(planner.check(2.0, rowing = true, distance = 0.0, balance = 1500.0))
        assertEquals(listOf("The rower just started rowing."), planner.check(8.0, true, 5.0, 1500.0))
    }

    @Test
    fun `stopping is reported after a few still seconds`() {
        planner.check(0.0, true, 0.0, 1500.0)
        planner.check(9.0, false, 20.0, 1500.0)
        assertNull(planner.check(12.0, false, 20.0, 1500.0))
        assertEquals(listOf("The rower has stopped."), planner.check(13.5, false, 20.0, 1500.0))
    }

    @Test
    fun `balance warnings wait for the next regular wake`() {
        planner.check(0.0, true, 0.0, 1500.0)
        assertNull(planner.check(9.0, true, 100.0, 400.0))
        assertEquals(listOf("Your balance just fell below 500 tokens."), planner.check(15.0, true, 200.0, 390.0))
    }

    @Test
    fun `milestones wake it early, once`() {
        planner.check(0.0, true, 0.0, 1500.0)
        assertEquals(listOf("The rower just passed 500 metres."), planner.check(9.0, true, 510.0, 1500.0))
        assertNull(planner.check(12.0, true, 600.0, 1500.0))
    }

    @Test
    fun `words running low wake it early`() {
        planner.check(0.0, true, 0.0, 1500.0, wordsLeft = 30.0)
        assertNull(planner.check(9.0, true, 50.0, 1500.0, wordsLeft = 12.0))
        assertEquals(emptyList<String>(), planner.check(10.0, true, 60.0, 1500.0, wordsLeft = 7.0))
    }
}
