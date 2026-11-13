package name.onetrue.supertuxrower.model

/**
 * Intensity bands as fractions of a profile's 2k power; see docs/workout-model.md.
 * The IDs are stable; display names come from the mode.
 */
enum class Band(val id: String, val floor: Double, val rowingName: String, val typicalRate: Int) {
    REST("rest", 0.0, "Rest", 16),
    UT2("ut2", 0.50, "UT2", 19),
    UT1("ut1", 0.65, "UT1", 21),
    AT("at", 0.75, "AT", 25),
    TR("tr", 0.85, "TR", 29),
    AN("an", 1.00, "AN", 33);

    /** Upper bound as a fraction of 2k power; AN is open-ended, so use a nominal top. */
    val ceiling: Double get() = entries.getOrNull(ordinal + 1)?.floor ?: 1.15

    val middle: Double get() = (floor + ceiling) / 2

    companion object {
        fun forFraction(fraction: Double): Band = entries.last { fraction >= it.floor }

        /** A continuous position from 0 (bottom of rest) to 6 (top of AN), for drawing. */
        fun position(fraction: Double): Double {
            val band = forFraction(fraction)
            return band.ordinal + ((fraction - band.floor) / (band.ceiling - band.floor)).coerceIn(0.0, 1.0)
        }
    }
}

/** Band names for the penguin games. Rest is "Rest" or "Waddle" depending on the game. */
fun Band.penguinName(rest: String = "Rest"): String = when (this) {
    Band.REST -> rest
    Band.UT2 -> "Drift"
    Band.UT1 -> "Glide"
    Band.AT -> "Chase"
    Band.TR -> "Surge"
    Band.AN -> "Launch"
}
