package name.onetrue.supertuxrower.workout

import name.onetrue.supertuxrower.FakeRower
import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.Goal
import name.onetrue.supertuxrower.model.Piece
import name.onetrue.supertuxrower.model.Rest
import name.onetrue.supertuxrower.model.Workout
import name.onetrue.supertuxrower.model.repeat
import name.onetrue.supertuxrower.testProfile
import name.onetrue.supertuxrower.wattsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutRunnerTest {
    private val dt = 0.1

    private fun WorkoutRunner.row(rower: FakeRower, seconds: Double) {
        repeat((seconds / dt).toInt()) { update(dt, rower.step(dt)) }
    }

    private val intervals = Workout(
        "Intervals", "2 × (10 s Surge, 5 s rest)",
        repeat(2, Piece(Goal.Time(10), Band.TR, rest = Rest.Time(5))),
    )

    @Test
    fun `waits for the first stroke`() {
        val runner = WorkoutRunner(intervals, testProfile)
        runner.row(FakeRower(watts = 0.0), 5.0)
        assertEquals(Phase.READY, runner.phase)
        runner.row(FakeRower(watts = 150.0), 3.0)
        assertEquals(Phase.WORK, runner.phase)
    }

    @Test
    fun `steps through pieces and rests to the end`() {
        val runner = WorkoutRunner(intervals, testProfile)
        val rower = FakeRower(watts = testProfile.wattsFor(Band.TR))
        runner.row(rower, 3.0) // first stroke arrives after 2.5 s
        assertEquals(Phase.WORK, runner.phase)
        runner.row(rower, 10.0)
        assertEquals(Phase.REST, runner.phase)
        runner.row(rower, 5.0)
        assertEquals(Phase.WORK, runner.phase)
        assertEquals(1, runner.index)
        runner.row(rower, 10.5)
        assertEquals(Phase.DONE, runner.phase)
    }

    @Test
    fun `distance goals finish at the distance`() {
        val workout = Workout("500", "500m", listOf(Piece(Goal.Distance(500), Band.TR)))
        val runner = WorkoutRunner(workout, testProfile)
        val rower = FakeRower(watts = testProfile.wattsFor(Band.TR))
        while (runner.phase != Phase.DONE && rower.distance < 600) runner.update(dt, rower.step(dt))
        assertEquals(Phase.DONE, runner.phase)
        assertEquals(500.0, runner.results[0].distance, 5.0)
    }

    @Test
    fun `rest until ready waits for a stroke after a short grace`() {
        val workout = Workout(
            "Ready", "", listOf(Piece(Goal.Time(5), Band.TR, rest = Rest.UntilReady), Piece(Goal.Time(5), Band.TR)),
        )
        val runner = WorkoutRunner(workout, testProfile)
        val rower = FakeRower(watts = testProfile.wattsFor(Band.TR))
        runner.row(rower, 8.0)
        assertEquals(Phase.REST, runner.phase)
        rower.watts = 0.0
        runner.row(rower, 10.0)
        assertEquals(Phase.REST, runner.phase)
        rower.watts = testProfile.wattsFor(Band.TR)
        runner.row(rower, 3.0)
        assertEquals(Phase.WORK, runner.phase)
        assertEquals(1, runner.index)
    }

    @Test
    fun `time in band counts only time in the target band`() {
        val runner = WorkoutRunner(intervals, testProfile)
        runner.row(FakeRower(watts = testProfile.wattsFor(Band.TR)), 12.6)
        assertEquals(1.0, runner.results[0].inBandShare!!, 0.02)

        val slow = WorkoutRunner(intervals, testProfile)
        slow.row(FakeRower(watts = testProfile.wattsFor(Band.UT2)), 12.6)
        assertEquals(0.0, slow.results[0].inBandShare!!, 0.02)
    }

    @Test
    fun `forecasts the band ahead`() {
        val runner = WorkoutRunner(intervals, testProfile)
        runner.row(FakeRower(watts = testProfile.wattsFor(Band.TR)), 3.0)
        assertEquals(Band.TR, runner.bandAhead(5.0, 4.0))
        assertNull(runner.bandAhead(12.0, 4.0)) // the rest
        assertEquals(Band.TR, runner.bandAhead(17.0, 4.0))
        assertNull(runner.bandAhead(60.0, 4.0)) // after the end
    }

    @Test
    fun `band tracker needs a margin past a boundary to change`() {
        val tracker = BandTracker(testProfile)
        val watts = { fraction: Double -> testProfile.twoKWatts * fraction }
        assertEquals(Band.TR, tracker.update(watts(0.92)))
        // Just under the TR/AT boundary (0.85) isn't enough to drop a band...
        assertEquals(Band.TR, tracker.update(watts(0.84)))
        // ...but clearly under it is.
        assertEquals(Band.AT, tracker.update(watts(0.80)))
        assertTrue(tracker.update(watts(0.86)) == Band.AT)
    }
}
