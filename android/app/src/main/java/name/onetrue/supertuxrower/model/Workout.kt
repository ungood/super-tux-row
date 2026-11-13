package name.onetrue.supertuxrower.model

sealed interface Goal {
    data class Time(val seconds: Int) : Goal
    data class Distance(val metres: Int) : Goal
    data class Calories(val kcal: Int) : Goal
    data object Open : Goal
}

sealed interface Rest {
    data object None : Rest
    /** Start the next piece with the next stroke. */
    data object UntilReady : Rest
    data class Time(val seconds: Int) : Rest
}

enum class Tag(val label: String) { WARM_UP("Warm-up"), WORK("Piece"), COOL_DOWN("Cool-down") }

data class Piece(
    val goal: Goal,
    /** Target band, or null for pieces without one (tests, just rowing). */
    val band: Band?,
    val rate: IntRange? = null,
    val rest: Rest = Rest.None,
    val tag: Tag = Tag.WORK,
)

data class Workout(val name: String, val notation: String, val pieces: List<Piece>)

fun repeat(times: Int, vararg pieces: Piece): List<Piece> = List(times) { pieces.toList() }.flatten()

fun Goal.describe(): String = when (this) {
    is Goal.Time -> formatClock(seconds.toDouble())
    is Goal.Distance -> "${metres}m"
    is Goal.Calories -> "$kcal cal"
    Goal.Open -> "open"
}

/** Rough duration of a piece for this profile, for previews. */
fun Piece.estimatedSeconds(profile: Profile): Double {
    val fraction = band?.middle ?: 0.8
    return when (goal) {
        is Goal.Time -> goal.seconds.toDouble()
        is Goal.Distance -> goal.metres / profile.speedAt(fraction)
        is Goal.Calories -> goal.kcal / (Rowing.kcalPerHour(profile.twoKWatts * fraction) / 3600)
        Goal.Open -> 600.0
    }
}

fun Rest.estimatedSeconds(): Double = when (this) {
    Rest.None -> 0.0
    Rest.UntilReady -> 30.0
    is Rest.Time -> seconds.toDouble()
}

object Library {
    private fun warmUp(seconds: Int) = Piece(Goal.Time(seconds), Band.UT2, tag = Tag.WARM_UP)
    private fun coolDown(seconds: Int) = Piece(Goal.Time(seconds), Band.UT2, tag = Tag.COOL_DOWN)

    val workouts = listOf(
        Workout(
            "Taster",
            "1:00 Drift; 3 × (0:40 Surge, 0:20 rest); 1:00 Drift",
            listOf(warmUp(60)) +
                repeat(3, Piece(Goal.Time(40), Band.TR, rest = Rest.Time(20))) +
                coolDown(60),
        ),
        Workout("Just row", "Open, no target", listOf(Piece(Goal.Open, null))),
        Workout("Steady 30", "30:00 Glide, rate 19–21", listOf(Piece(Goal.Time(30 * 60), Band.UT1, rate = 19..21))),
        Workout("Recovery 20", "20:00 Drift", listOf(Piece(Goal.Time(20 * 60), Band.UT2))),
        Workout(
            "4 × 1000m",
            "4 × (1000m Surge, 5:00 rest)",
            listOf(warmUp(5 * 60)) + repeat(4, Piece(Goal.Distance(1000), Band.TR, rest = Rest.Time(5 * 60))) + coolDown(5 * 60),
        ),
        Workout(
            "8 × 500m",
            "8 × (500m Surge, 1:30 rest)",
            listOf(warmUp(5 * 60)) + repeat(8, Piece(Goal.Distance(500), Band.TR, rest = Rest.Time(90))) + coolDown(5 * 60),
        ),
        Workout(
            "Pyramid",
            "250 / 500 / 750 / 1000 / 750 / 500 / 250m Surge",
            listOf(warmUp(5 * 60)) +
                listOf(250, 500, 750, 1000, 750, 500, 250).map {
                    Piece(Goal.Distance(it), Band.TR, rest = Rest.Time(it * 60 / 250))
                } +
                coolDown(5 * 60),
        ),
        Workout(
            "20 × 1:00",
            "20 × (1:00 Launch, 1:00 rest)",
            listOf(warmUp(5 * 60)) + repeat(20, Piece(Goal.Time(60), Band.AN, rest = Rest.Time(60))) + coolDown(5 * 60),
        ),
        Workout(
            "Chase intervals",
            "5 × (3:00 Chase, rest until ready)",
            listOf(warmUp(5 * 60)) + repeat(5, Piece(Goal.Time(180), Band.AT, rest = Rest.UntilReady)) + coolDown(5 * 60),
        ),
        Workout("2k test", "2000m, all out", listOf(warmUp(5 * 60), Piece(Goal.Distance(2000), null, rest = Rest.UntilReady), coolDown(5 * 60))),
    )
}
