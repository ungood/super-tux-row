package name.onetrue.supertuxrower.game

import name.onetrue.supertuxrower.FakeRower
import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.Library
import name.onetrue.supertuxrower.model.Steering
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

    /** A game whose first 800 m all have a floor, to bonk on. */
    private fun gameWithFloor() = (0..500).map { CaveGame(kotlin.random.Random(it)) }
        .first { g -> (0..800 step 5).all { !g.terrainAt(it.toDouble()).floorOpen } }

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
        val game = gameWithFloor()
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
        // Seeded, so no golden fish's boost happens to hold him at the middle just then.
        val game = CaveGame(kotlin.random.Random(1))
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
        assertTrue("widest ${later.max()}", later.max() > 1.25)
        // Never as tight as it once was: it felt claustrophobic.
        assertTrue("tightest ${later.min()}", later.min() in 0.85..0.95)
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

    @Test
    fun `Tux drifts through a rest at the same pace however you row`() {
        fun positionMidRest(restWatts: Double): Double {
            val runner = WorkoutRunner(taster, testProfile)
            val game = CaveGame()
            val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
            var steps = 0
            while (!(runner.phase == Phase.REST && runner.restFraction >= 0.5) && steps++ < 20_000) {
                rower.watts = if (runner.phase == Phase.REST) restWatts else testProfile.wattsFor(runner.piece?.band ?: Band.UT1)
                val s = rower.step(dt)
                runner.update(dt, s)
                game.update(dt, runner, s)
            }
            return game.course!!.position(runner)
        }
        assertEquals(positionMidRest(0.0), positionMidRest(testProfile.twoKWatts), 0.5)
    }

    @Test
    fun `a workout goes from near the surface into the deep`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = CaveGame()
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
        var shallowest = Double.MAX_VALUE
        var deepest = 0.0
        var steps = 0
        while (runner.phase != Phase.DONE && steps++ < 20_000) {
            followWorkout(runner, rower)
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
            if (runner.phase == Phase.WORK) {
                shallowest = minOf(shallowest, game.depth)
                deepest = maxOf(deepest, game.depth)
            }
        }
        assertTrue("shallowest $shallowest", shallowest < 20)
        assertTrue("deepest $deepest", deepest > 150)
    }

    @Test
    fun `catches climb the scale with the streak, and the end gets a fanfare`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = CaveGame(kotlin.random.Random(1))
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
        val heard = mutableListOf<Pair<Cue, Int>>()
        var steps = 0
        while (game.finishedFor < 1 && steps++ < 20_000) {
            followWorkout(runner, rower)
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
            while (game.cues.isNotEmpty()) heard += game.cues.removeFirst()
        }
        val catches = heard.filter { it.first == Cue.CATCH }.map { it.second }
        assertTrue("catches ${catches.size}", catches.size > 10)
        assertTrue(catches.all { it in 0 until Synth.CATCH_STEPS })
        // A streak's first catches go up a step at a time.
        assertTrue("catches $catches", catches.windowed(3).any { it == listOf(0, 1, 2) })
        assertEquals(Cue.FINISH to 0, heard.last())
        assertEquals(1, heard.count { it.first == Cue.FINISH })
        assertEquals(0, heard.count { it.first == Cue.BONK })
    }

    @Test
    fun `a bonk is heard`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = gameWithFloor()
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
        var steps = 0
        while (!(runner.index == 1 && runner.results[1].seconds > 10) && steps++ < 20_000) {
            followWorkout(runner, rower)
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
        }
        game.cues.clear()
        rower.watts = 0.0
        repeat((8 / dt).toInt()) {
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
        }
        assertEquals(game.bonks, game.cues.count { it.first == Cue.BONK })
    }

    @Test
    fun `the terrain changes between cave and open water, and starts with room`() {
        val game = CaveGame(kotlin.random.Random(3))
        val kinds = (0..20_000 step 10).map { game.terrainAt(it.toDouble()) }
        assertTrue(kinds.first() != CaveGame.Terrain.CAVE)
        assertEquals(CaveGame.Terrain.entries.toSet(), kinds.toSet())
        // Stretches run at least 150 m and never repeat back to back.
        val runs = kinds.zipWithNext().count { (a, b) -> a != b }
        assertTrue("runs $runs", runs in 50..140)
    }

    @Test
    fun `in open water Tux doesn't bonk, he stops at the screen's edge`() {
        val runner = WorkoutRunner(taster, testProfile)
        // A seed whose first stretch has no floor.
        val game = (0..50).map { CaveGame(kotlin.random.Random(it)) }.first { it.terrainAt(0.0) == CaveGame.Terrain.NO_FLOOR }
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
        var steps = 0
        while (!(runner.index == 0 && runner.phase == Phase.WORK && runner.results[0].seconds > 10) && steps++ < 20_000) {
            followWorkout(runner, rower)
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
        }
        // Stop rowing: he sinks, with no floor to hit.
        rower.watts = 0.0
        repeat((10 / dt).toInt()) {
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
        }
        assertEquals(CaveGame.Terrain.NO_FLOOR, game.terrainAt(game.metres))
        assertEquals(0, game.bonks)
        assertTrue("y ${game.y}, camera ${game.camera}", game.y < game.camera - 1.5)
    }

    @Test
    fun `relaxed steering drifts off target more slowly than sharp`() {
        fun drift(steering: Steering): Double {
            val profile = testProfile.copy(steering = steering)
            val runner = WorkoutRunner(Library.workouts.first { it.name == "Steady 30" }, profile)
            val game = CaveGame(kotlin.random.Random(1))
            val rower = FakeRower(watts = profile.wattsFor(Band.UT1))
            repeat((20 / dt).toInt()) {
                val s = rower.step(dt)
                runner.update(dt, s)
                game.update(dt, runner, s)
            }
            // Row in UT2, below the target, and see how far below the middle of the cave he sinks.
            rower.watts = profile.twoKWatts * 0.52
            repeat((6 / dt).toInt()) {
                val s = rower.step(dt)
                runner.update(dt, s)
                game.update(dt, runner, s)
            }
            return game.sliceAt(game.metres)!!.centre - game.y
        }
        val relaxed = drift(Steering.RELAXED)
        val sharp = drift(Steering.SHARP)
        assertTrue("relaxed $relaxed, sharp $sharp", relaxed > 0.05 && sharp > relaxed * 1.5)
    }

    @Test
    fun `drifting food lives at its own depths`() {
        val runner = WorkoutRunner(taster, testProfile)
        val game = CaveGame(kotlin.random.Random(2))
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT2))
        val seen = mutableListOf<Pair<CaveGame.Food, Double>>()
        var steps = 0
        while (runner.phase != Phase.DONE && steps++ < 20_000) {
            followWorkout(runner, rower)
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
            for (f in game.fish) if (seen.none { it.first == f.food && f.metre == f.metre }) seen += f.food to game.lightAt(f.height)
        }
        val drifters = seen.filter { it.first in CaveGame.DRIFTERS }
        assertTrue("drifters ${drifters.size}", drifters.isNotEmpty())
        assertTrue(drifters.all { (food, light) -> light in food.minLight..food.maxLight })
    }

    /** A game rowed steadily into its first piece, past the grace period, ready to feed. */
    private fun rowing(): Triple<CaveGame, WorkoutRunner, FakeRower> {
        val runner = WorkoutRunner(Library.workouts.first { it.name == "Steady 30" }, testProfile)
        val game = CaveGame(kotlin.random.Random(1))
        val rower = FakeRower(watts = testProfile.wattsFor(Band.UT1))
        repeat((15 / dt).toInt()) {
            val s = rower.step(dt)
            runner.update(dt, s)
            game.update(dt, runner, s)
        }
        game.fish.clear()
        return Triple(game, runner, rower)
    }

    private fun step(game: CaveGame, runner: WorkoutRunner, rower: FakeRower, seconds: Double) = repeat((seconds / dt).toInt()) {
        val s = rower.step(dt)
        runner.update(dt, s)
        game.update(dt, runner, s)
    }

    @Test
    fun `each kind scores differently, and drifters do something`() {
        assertEquals(CaveGame.Food.entries.size, CaveGame.Food.entries.map { it.points }.toSet().size)
        assertTrue(CaveGame.DRIFTERS.all { it.label != null && it.seconds > 0 })
    }

    @Test
    fun `a pufferfish puffs Tux up, so he catches things further off`() {
        val (game, runner, rower) = rowing()
        game.fish.addLast(CaveGame.Fish(game.metres + 1.5, game.y, CaveGame.Food.PUFFER))
        step(game, runner, rower, 1.0)
        assertTrue(game.effect(CaveGame.Food.PUFFER) > 0)
        assertEquals("Puffed up", game.fish.first().bonus)
        // Further off than he could reach before.
        val far = CaveGame.Fish(game.metres + 2.0, game.y + CaveGame.CATCH_RADIUS * 1.5, CaveGame.Food.FISH)
        game.fish.addLast(far)
        step(game, runner, rower, 1.0)
        assertTrue(far.eaten)
    }

    @Test
    fun `a seahorse steadies Tux`() {
        val (game, runner, rower) = rowing()
        game.fish.addLast(CaveGame.Fish(game.metres + 1.5, game.y, CaveGame.Food.SEAHORSE))
        step(game, runner, rower, 1.0)
        assertTrue(game.graceLeft > 4)
        assertEquals(0.0, game.holdLeft, 0.0)
    }

    @Test
    fun `at the end Tux swims off ahead, then laps across the screen`() {
        val (game, runner, rower) = rowing()
        assertEquals(0.0, game.swimAhead, 0.0)
        runner.finish()
        step(game, runner, rower, CaveGame.FIRST_LAP - 0.1)
        assertTrue("ahead ${game.swimAhead}", game.swimAhead > CaveGame.LAP_END * 0.9)
        step(game, runner, rower, 0.5)
        assertTrue("ahead ${game.swimAhead}", game.swimAhead < 0)
    }
}
