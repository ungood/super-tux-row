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
import kotlin.math.abs

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
    fun `every built-in workout with an end, rowed as written stays clear of the walls`() {
        for (workout in Library.workouts.filter { it.name != "Just row" }) {
            val runner = WorkoutRunner(workout, testProfile)
            val game = CaveGame()
            val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
            var steps = 0
            while (runner.phase != Phase.DONE && steps++ < 400_000) {
                followWorkout(runner, rower)
                val s = rower.step(dt)
                runner.update(dt, s)
                game.update(dt, runner, s)
            }
            assertEquals(workout.name, Phase.DONE, runner.phase)
            assertEquals("bonks in ${workout.name}", 0, game.bonks)
        }
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
        // Past the first piece's grace period, which starts with the first stroke.
        repeat((12 / dt).toInt()) {
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

    @Test
    fun `the cave starts wide, then has roomier and tighter stretches`() {
        val game = CaveGame()
        val later = (CaveGame.WARMUP_METRES.toInt()..5000).map { game.widthAt(it.toDouble()) }
        assertTrue("start ${game.widthAt(0.0)}", game.widthAt(0.0) > later.max())
        assertTrue("widest ${later.max()}", later.max() > 1.2)
        assertTrue("tightest ${later.min()}", later.min() in 0.7..0.8)
    }

    @Test
    fun `the cave fills the screen behind Tux from the start`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = CaveGame()
        val s = FakeRower(watts = 0.0).step(dt)
        runner.update(dt, s)
        game.update(dt, runner, s)
        // Tux sits 30% of the way across, so about 9.4 m of course shows behind him.
        assertTrue("first slice at ${game.slices.first().metre}", game.slices.first().metre <= -10)
    }

    @Test
    fun `the cave is built past the right edge`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = CaveGame()
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
        repeat((60 / dt).toInt()) {
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
            assertTrue(game.slices.last().metre >= game.metres + CaveGame.LOOKAHEAD + 1)
        }
    }

    @Test
    fun `easy rowing takes Tux deeper and darker, hard rowing brings him back up`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = CaveGame()
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
        val depths = mutableListOf<Double>()
        var steps = 0
        while (runner.phase != Phase.DONE && steps++ < 20_000) {
            followWorkout(runner, rower)
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
            if (steps % 30 == 0) depths += game.depth
        }
        // The Drift warm-up sinks; the Surge pieces climb back.
        val afterWarmup = depths[60]
        assertTrue("warm-up from ${depths.first()} to $afterWarmup", afterWarmup > depths.first() + 10)
        assertTrue("surges climb from $afterWarmup", depths.subList(60, 160).min() < afterWarmup - 5)
    }

    @Test
    fun `Tux breathes faster the harder you row`() {
        fun breathsIn20s(band: Band): Int {
            val runner = WorkoutRunner(taster, testProfile)
            val game = CaveGame()
            val rower = FakeRower(watts = testProfile.wattsFor(band))
            repeat((20 / dt).toInt()) {
                val s = rower.step(dt)
                runner.update(dt, s)
                game.update(dt, runner, s)
            }
            return game.breaths
        }
        val easy = breathsIn20s(Band.UT2)
        val hard = breathsIn20s(Band.AN)
        assertTrue("easy $easy, hard $hard", hard > easy * 1.5)
    }

    @Test
    fun `a slow start after a long wait doesn't sink Tux`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = CaveGame()
        val rower = FakeRower(watts = 0.0)
        // Sit at the start screen for a while, then start with a few feeble strokes.
        repeat((30 / dt).toInt()) { val s = rower.step(dt); runner.update(dt, s); game.update(dt, runner, s) }
        rower.watts = testProfile.twoKWatts * 0.15
        repeat((5 / dt).toInt()) {
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
            // He goes where the cave goes, but stays near its middle.
            val centre = game.sliceAt(game.metres)!!.centre
            assertTrue("${game.y} vs the cave's middle at $centre", abs(game.y - centre) < 0.4)
        }
        assertEquals(Phase.WORK, runner.phase)
        assertEquals("bonks", 0, game.bonks)
    }
}
