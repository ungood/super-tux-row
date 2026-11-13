package name.onetrue.supertuxrower.workout

import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.Goal
import name.onetrue.supertuxrower.model.Piece
import name.onetrue.supertuxrower.model.Profile
import name.onetrue.supertuxrower.model.Rest
import name.onetrue.supertuxrower.model.Rowing
import name.onetrue.supertuxrower.model.Workout
import name.onetrue.supertuxrower.model.estimatedSeconds
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.sensor.RowerSnapshot
import kotlin.math.roundToInt

enum class Phase { READY, WORK, REST, DONE }

/**
 * The band you're in, with hysteresis: it only changes once you're [margin] (a fraction of 2k
 * power) past a boundary, so rowing near an edge doesn't flicker between bands.
 */
class BandTracker(private val profile: Profile, private val margin: Double = 0.03) {
    var band = Band.REST
        private set

    fun update(watts: Double): Band {
        val fraction = profile.fraction(watts)
        val raw = Band.forFraction(fraction)
        if (raw > band) {
            val candidate = Band.forFraction(fraction - margin)
            if (candidate > band) band = candidate
        } else if (raw < band) {
            val candidate = Band.forFraction(fraction + margin)
            if (candidate < band) band = candidate
        }
        return band
    }
}

class PieceResult(val piece: Piece) {
    var seconds = 0.0
    var inBand = 0.0
    var distance = 0.0
    var calories = 0.0

    val inBandShare: Double? get() = if (piece.band != null && seconds > 0) inBand / seconds else null
}

/** What the overlay shows, rebuilt a few times a second. */
data class WorkoutView(
    val phase: Phase,
    val pieceNumber: Int,
    val pieceCount: Int,
    val piece: Piece?,
    val nextPiece: Piece?,
    val goalLeft: String,
    val progress: Float?,
    val restLeft: String?,
    val elapsed: Double,
    val distance: Double,
    val calories: Double,
    val speed: Double,
    val watts: Double,
    val spm: Double,
    val band: Band,
    val onTarget: Boolean,
    val pieceInBand: Double?,
)

/**
 * Runs a workout piece by piece against the rower. The clock starts with the first stroke.
 * Time in band counts every second of a piece; only seconds in the target band (and stroke-rate
 * range, if set) count towards it.
 */
class WorkoutRunner(val workout: Workout, val profile: Profile) {
    var phase = Phase.READY
        private set
    var index = 0
        private set
    val results = workout.pieces.map(::PieceResult)

    var elapsed = 0.0
        private set
    var calories = 0.0
        private set
    var distance = 0.0
        private set

    private val tracker = BandTracker(profile)
    private var seenStrokes = -1
    private var startDistance = 0.0
    private var pieceStartDistance = 0.0
    private var pieceStartCalories = 0.0
    private var restElapsed = 0.0
    private var last: RowerSnapshot? = null

    val piece: Piece? get() = workout.pieces.getOrNull(index)

    fun update(dt: Double, s: RowerSnapshot) {
        last = s
        tracker.update(s.watts)
        val stroke = seenStrokes >= 0 && s.strokes > seenStrokes
        seenStrokes = s.strokes

        if (phase == Phase.READY) {
            if (!stroke) return
            phase = Phase.WORK
            startDistance = s.distance
            beginPiece(s)
            return
        }
        if (phase == Phase.DONE) return

        elapsed += dt
        distance = s.distance - startDistance
        if (s.moving) calories += Rowing.kcalPerHour(s.watts) / 3600 * dt

        when (phase) {
            Phase.WORK -> {
                val r = results[index]
                r.seconds += dt
                if (onTarget(r.piece, s)) r.inBand += dt
                r.distance = s.distance - pieceStartDistance
                r.calories = calories - pieceStartCalories
                if (goalReached(r)) finishPiece(s)
            }
            Phase.REST -> {
                restElapsed += dt
                when (val rest = results[index].piece.rest) {
                    is Rest.Time -> if (restElapsed >= rest.seconds) nextPiece(s)
                    // A short grace period so the stroke that ended the piece doesn't skip the rest.
                    Rest.UntilReady -> if (stroke && restElapsed > 3) nextPiece(s)
                    Rest.None -> nextPiece(s)
                }
            }
            else -> {}
        }
    }

    /** Ends the current piece (or rest) early. */
    fun skip() {
        val s = last ?: return
        when (phase) {
            Phase.READY -> {
                phase = Phase.WORK
                startDistance = s.distance
                beginPiece(s)
            }
            Phase.WORK -> finishPiece(s)
            Phase.REST -> nextPiece(s)
            Phase.DONE -> {}
        }
    }

    fun finish() {
        phase = Phase.DONE
    }

    fun onTarget(piece: Piece, s: RowerSnapshot): Boolean {
        val band = piece.band ?: return false
        if (tracker.band != band) return false
        val rate = piece.rate ?: return true
        return s.spm.roundToInt() in rate
    }

    /** The band to hold now: the piece's target while working, otherwise none. */
    val target: Band? get() = if (phase == Phase.WORK) piece?.band else null

    /** The band of the next piece with a target, for lining up before it starts. */
    val upcoming: Band?
        get() = when (phase) {
            Phase.READY -> piece?.band
            Phase.REST -> workout.pieces.getOrNull(index + 1)?.band
            else -> null
        }

    /**
     * The target band [seconds] from now, or null if there won't be one (rest, a piece without a
     * target, or the end). Distance and calorie goals are estimated from [speed].
     */
    fun bandAhead(seconds: Double, speed: Double): Band? {
        val pieces = workout.pieces
        var t = seconds
        var i = index
        when (phase) {
            Phase.DONE -> return null
            Phase.READY, Phase.WORK -> {
                val left = remainingSeconds(results[i], speed)
                if (t < left) return pieces[i].band
                t -= left
                val rest = pieces[i].rest.forecastSeconds()
                if (t < rest) return null
                t -= rest
                i++
            }
            Phase.REST -> {
                val left = when (val rest = pieces[i].rest) {
                    is Rest.Time -> rest.seconds - restElapsed
                    else -> UNTIL_READY_SECONDS
                }
                if (t < left) return null
                t -= left
                i++
            }
        }
        while (i < pieces.size) {
            val work = pieces[i].estimatedSeconds(profile)
            if (t < work) return pieces[i].band
            t -= work
            val rest = pieces[i].rest.forecastSeconds()
            if (t < rest) return null
            t -= rest
            i++
        }
        return null
    }

    private fun remainingSeconds(r: PieceResult, speed: Double): Double = when (val goal = r.piece.goal) {
        is Goal.Time -> goal.seconds - r.seconds
        is Goal.Distance -> (goal.metres - r.distance) / speed.coerceAtLeast(1.0)
        is Goal.Calories -> (goal.kcal - r.calories) / (Rowing.kcalPerHour(Rowing.watts(speed.coerceAtLeast(1.0))) / 3600)
        Goal.Open -> Double.MAX_VALUE
    }

    private fun Rest.forecastSeconds(): Double = when (this) {
        Rest.None -> 0.0
        Rest.UntilReady -> UNTIL_READY_SECONDS
        is Rest.Time -> seconds.toDouble()
    }

    fun view(): WorkoutView {
        val s = last ?: RowerSnapshot(0.0, 0.0, 0.0, 0.0, 0, false)
        val p = piece
        val r = results.getOrNull(index)
        val (goalLeft, progress) = when {
            p == null || r == null -> "" to null
            phase == Phase.READY -> p.goalText() to 0f
            else -> goalProgress(p, r)
        }
        val restLeft = when {
            phase != Phase.REST || p == null -> null
            p.rest is Rest.Time -> formatClock((p.rest as Rest.Time).seconds - restElapsed + 0.999)
            else -> "Pull when ready"
        }
        return WorkoutView(
            phase = phase,
            pieceNumber = index + 1,
            pieceCount = workout.pieces.size,
            piece = p,
            nextPiece = workout.pieces.getOrNull(index + 1),
            goalLeft = goalLeft,
            progress = progress,
            restLeft = restLeft,
            elapsed = elapsed,
            distance = distance,
            calories = calories,
            speed = s.speed,
            watts = s.watts,
            spm = s.spm,
            band = tracker.band,
            onTarget = p != null && phase == Phase.WORK && onTarget(p, s),
            pieceInBand = r?.inBandShare,
        )
    }

    private fun beginPiece(s: RowerSnapshot) {
        pieceStartDistance = s.distance
        pieceStartCalories = calories
    }

    private fun finishPiece(s: RowerSnapshot) {
        if (index == workout.pieces.lastIndex) {
            phase = Phase.DONE
            return
        }
        if (results[index].piece.rest == Rest.None) {
            nextPiece(s)
        } else {
            phase = Phase.REST
            restElapsed = 0.0
        }
    }

    private fun nextPiece(s: RowerSnapshot) {
        index++
        phase = Phase.WORK
        beginPiece(s)
    }

    private fun goalReached(r: PieceResult): Boolean = when (val goal = r.piece.goal) {
        is Goal.Time -> r.seconds >= goal.seconds
        is Goal.Distance -> r.distance >= goal.metres
        is Goal.Calories -> r.calories >= goal.kcal
        Goal.Open -> false
    }

    private fun goalProgress(p: Piece, r: PieceResult): Pair<String, Float?> = when (val goal = p.goal) {
        is Goal.Time -> formatClock(goal.seconds - r.seconds + 0.999) to (r.seconds / goal.seconds).toFloat()
        is Goal.Distance -> "${(goal.metres - r.distance).roundToInt().coerceAtLeast(0)} m" to (r.distance / goal.metres).toFloat()
        is Goal.Calories -> "${(goal.kcal - r.calories).roundToInt().coerceAtLeast(0)} cal" to (r.calories / goal.kcal).toFloat()
        Goal.Open -> formatClock(r.seconds) to null
    }

    private fun Piece.goalText() = when (goal) {
        is Goal.Time -> formatClock(goal.seconds.toDouble())
        is Goal.Distance -> "${goal.metres} m"
        is Goal.Calories -> "${goal.kcal} cal"
        Goal.Open -> "0:00"
    }

    private companion object {
        /** A guess at how long someone rests when it's up to them. */
        const val UNTIL_READY_SECONDS = 5.0
    }
}
