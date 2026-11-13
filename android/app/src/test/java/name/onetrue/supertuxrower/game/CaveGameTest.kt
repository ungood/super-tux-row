package name.onetrue.supertuxrower.game

import name.onetrue.supertuxrower.FakeRower
import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.Library
import name.onetrue.supertuxrower.testProfile
import name.onetrue.supertuxrower.wattsFor
import name.onetrue.supertuxrower.workout.Phase
import name.onetrue.supertuxrower.workout.WorkoutRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaveGameTest {
    private val dt = 1.0 / 30
    private val taster = Library.workouts.first { it.name == "Taster" }

    /** Rows the way the workout asks: each piece's band middle, easy during rests. */
    private fun followWorkout(runner: WorkoutRunner, rower: FakeRower) {
        rower.watts = when (runner.phase) {
            Phase.WORK -> testProfile.wattsFor(runner.piece?.band ?: Band.UT1)
            else -> testProfile.twoKWatts * 0.3
        }
    }

    @Test
    fun `rowing the workout as written keeps Tux in the cave`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = CaveGame()
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
        var steps = 0
        while (runner.phase != Phase.DONE && steps++ < 20_000) {
            followWorkout(runner, rower)
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
        }
        assertEquals(Phase.DONE, runner.phase)
        assertEquals("bonks", 0, game.bonks)
        assertTrue("fish ${game.score}", game.score > 20)
    }

    @Test
    fun `stopping in the middle of a hard piece hits the floor`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = CaveGame()
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
        var steps = 0
        // Row properly into the first Surge piece, past its grace period...
        while (!(runner.index == 1 && runner.results[1].seconds > 10) && steps++ < 20_000) {
            followWorkout(runner, rower)
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
        }
        assertEquals(0, game.bonks)
        // ...then stop.
        rower.watts = 0.0
        repeat((8 / dt).toInt()) {
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
        }
        assertTrue("bonks ${game.bonks}", game.bonks >= 1)
    }

    @Test
    fun `rowing harder climbs and easier sinks`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = CaveGame()
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
        repeat((5 / dt).toInt()) {
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
        }
        // Launch power in a Drift piece: out of band, so no pull back to the middle.
        rower.watts = testProfile.wattsFor(Band.AN)
        val before = game.y
        repeat((1 / dt).toInt()) {
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
        }
        assertTrue("climbed from $before to ${game.y}", game.y > before)
    }
}
