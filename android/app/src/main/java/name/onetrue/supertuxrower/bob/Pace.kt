package name.onetrue.supertuxrower.bob

import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.penguinName
import kotlin.math.roundToInt

/**
 * The pace Bob asks the rower for. Each band of effort on the rower buys a level of reasoning
 * effort from the model: he thinks at the band the rower actually holds, up to the pace he asked
 * for, and holding his pace buys more tokens. Thinking harder costs more tokens, so a fast pace
 * pays for itself only if the rower keeps up.
 */
enum class Pace(val band: Band, val effort: String, val thinking: String) {
    DRIFT(Band.UT2, "low", "quick and shallow"),
    GLIDE(Band.UT1, "medium", "steady"),
    CHASE(Band.AT, "high", "hard"),
    SURGE(Band.TR, "xhigh", "very hard"),
    LAUNCH(Band.AN, "max", "as hard as you can");

    val id: String get() = name.lowercase()
    val title: String get() = band.penguinName()

    /** The watts this pace asks of a rower whose 2k power is [twoKWatts]. */
    fun watts(twoKWatts: Double): IntRange = (band.floor * twoKWatts).roundToInt()..(band.ceiling * twoKWatts).roundToInt()

    /** Whether [watts] is in this pace's band. */
    fun held(watts: Double, twoKWatts: Double): Boolean = watts > 5 && Band.forFraction(watts / twoKWatts) == band

    /** How hard he thinks with the rower at [watts]: their band, but no harder than this pace. */
    fun thinkingAt(watts: Double, twoKWatts: Double): Pace {
        val rowed = Band.forFraction((watts / twoKWatts).coerceAtLeast(0.0))
        val matching = entries.lastOrNull { it.band.ordinal <= rowed.ordinal } ?: DRIFT
        return if (matching.ordinal < ordinal) matching else this
    }

    companion object {
        fun of(id: String): Pace? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}
