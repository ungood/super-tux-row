package name.onetrue.supertuxrower.game

import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.Goal
import name.onetrue.supertuxrower.model.Profile
import name.onetrue.supertuxrower.model.Workout
import name.onetrue.supertuxrower.model.estimatedSeconds
import name.onetrue.supertuxrower.workout.Phase
import name.onetrue.supertuxrower.workout.WorkoutRunner

/**
 * The cave's plan for a whole workout, laid out in metres before it starts: a stretch for each
 * piece and each rest, in order. Tux's place on it comes from your progress through the workout
 * ([position]), so he reaches the end of a stretch exactly as its piece or rest ends, whatever
 * your pace. Knowing the whole course also gives its highest and lowest points, for depth.
 */
class Course(workout: Workout, private val profile: Profile) {
    class Stretch(val start: Double, val length: Double, val band: Band?, val rest: Boolean, val rise: Double) {
        val end: Double get() = start + length
    }

    private val pieces = ArrayList<Stretch>()
    private val rests = ArrayList<Stretch?>()
    val stretches = ArrayList<Stretch>()

    /** Where the last piece ends, or infinity if a piece is open-ended. */
    val end: Double

    /** The highest and lowest the cave's middle goes, in world units, waves aside. */
    val highest: Double
    val lowest: Double

    init {
        var at = 0.0
        var height = 0.0
        var top = 0.0
        var bottom = 0.0
        for (piece in workout.pieces) {
            val fraction = piece.band?.middle ?: 0.8
            val length = when (piece.goal) {
                is Goal.Distance -> piece.goal.metres.toDouble()
                Goal.Open -> Double.POSITIVE_INFINITY
                else -> piece.estimatedSeconds(profile) * profile.speedAt(fraction)
            }
            val work = Stretch(at, length, piece.band, rest = false, rise = slope(piece.band))
            pieces += work
            stretches += work
            if (length.isInfinite()) break
            at += length
            height += work.rise * length
            top = maxOf(top, height)
            bottom = minOf(bottom, height)
            val restLength = piece.rest.estimatedSeconds() * REST_SPEED
            if (restLength > 0) {
                val rest = Stretch(at, restLength, null, rest = true, rise = 0.0)
                rests += rest
                stretches += rest
                at += restLength
            } else {
                rests += null
            }
        }
        end = if (pieces.lastOrNull()?.length?.isInfinite() == true) Double.POSITIVE_INFINITY else at
        highest = top
        lowest = bottom
    }

    /** The stretch at [metre], or null before the start and after the end. */
    fun stretchAt(metre: Double): Stretch? = stretches.firstOrNull { metre >= it.start && metre < it.end }

    /** Where Tux should be: the start of the current stretch plus how far through it you are. */
    fun position(runner: WorkoutRunner): Double {
        val i = runner.index
        return when (runner.phase) {
            Phase.READY -> 0.0
            Phase.WORK -> {
                val s = pieces.getOrNull(i) ?: return end
                runner.pieceFraction()?.let { s.start + s.length * it } ?: (s.start + runner.results[i].distance)
            }
            Phase.REST -> rests.getOrNull(i)?.let { it.start + it.length * runner.restFraction } ?: pieces[i].end
            Phase.DONE -> end
        }
    }

    /** How fast the cave climbs (or falls) per metre in a [band], so rowing its middle keeps Tux level with it. */
    private fun slope(band: Band?): Double =
        band?.let { CaveGame.CLIMB * (it.middle - CaveGame.NEUTRAL) / profile.speedAt(it.middle) } ?: 0.0

    companion object {
        /** How fast Tux drifts through a rest, in metres per second, whatever you're doing. */
        const val REST_SPEED = 2.0
    }
}
