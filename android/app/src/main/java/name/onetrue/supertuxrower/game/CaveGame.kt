package name.onetrue.supertuxrower.game

import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.sensor.RowerSnapshot
import name.onetrue.supertuxrower.workout.Phase
import name.onetrue.supertuxrower.workout.WorkoutRunner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Tux swims through an ice cave, laid out in metres. Your boat speed moves him forward; your power
 * sets how fast he climbs or sinks: above [NEUTRAL] (a fraction of 2k power) he rises, below it he
 * sinks, faster the further you are from it.
 *
 * The cave is built from the workout: each piece's stretch slopes at the rate its target power
 * produces, so rowing the workout keeps him inside, and the camera scrolls up and down to follow.
 * While you're in the target band he's also pulled gently towards the middle of the cave, so
 * wobbles within the band don't pile up; leave the band and he drifts into a wall (a bonk).
 *
 * Heights are in world units; the screen shows [VIEW_HEIGHT] of them.
 */
class CaveGame {
    class Slice(val metre: Double, val centre: Double, val half: Double)
    class Fish(val metre: Double, val height: Double, val gold: Boolean) {
        var eaten = false
        var passed = false
    }
    class Bubble(val metre: Double, var height: Double, val born: Double, val size: Float)

    var y = 0.0
        private set
    var vy = 0.0
        private set
    /** How far Tux has swum along the course, in metres. */
    var metres = 0.0
        private set
    val slices = ArrayDeque<Slice>()
    val fish = ArrayDeque<Fish>()
    var score = 0
        private set
    var bonks = 0
        private set
    /** Fish caught in a row; every 5 adds one to the multiplier. Reset by a miss or a bonk. */
    var combo = 0
        private set
    val multiplier: Int get() = 1 + combo / 5
    val bubbles = ArrayDeque<Bubble>()
    /** The camera's height, eased to follow the cave at Tux. */
    var camera = 0.0
        private set
    /** Your power on the band scale (0–6), eased so the gauge glides. */
    var gauge = 0.0
        private set

    var clock = 0.0
        private set
    private var nextBubble = 0.0
    private var nextSlice = 0.0
    private var nextFish = FIRST_FISH
    private var base = 0.0
    private var undulation = 0.0
    private var fishCount = 0
    private var lastBonkAt = -10.0
    private var lastStrokeAt = -10.0
    private var seenStrokes = -1
    private var averageFraction = 0.0
    private var piece = -1
    private var pieceStartedAt = 0.0

    /** Cosmetic bob after each stroke, in world units; it doesn't move Tux's real height. */
    val bob: Double
        get() {
            val t = clock - lastStrokeAt
            return if (t in 0.0..BOB_SECONDS) BOB * sin(PI * t / BOB_SECONDS) else 0.0
        }

    fun update(dt: Double, runner: WorkoutRunner, s: RowerSnapshot) {
        clock += dt
        metres += s.liveSpeed * dt
        val profile = runner.profile
        spawn(runner, s.speed)

        if (s.strokes > seenStrokes && seenStrokes >= 0 && s.moving) lastStrokeAt = clock
        seenStrokes = s.strokes

        val watts = s.smoothWatts
        val fraction = profile.fraction(watts)
        gauge += (Band.position(fraction) - gauge) * min(1.0, dt * 4)
        if (s.moving) averageFraction += (fraction - averageFraction) * min(1.0, dt / 15)

        if (runner.index != piece) {
            piece = runner.index
            pieceStartedAt = clock
        }
        // A new piece's first seconds are for bringing your effort to the new target.
        val grace = clock - pieceStartedAt < GRACE
        val target = runner.target
        val working = runner.phase == Phase.WORK
        val here = sliceAt(metres)

        if (working) {
            // Pieces without a target are measured against your own recent average.
            val neutral = if (target != null) NEUTRAL else averageFraction
            val climb = CLIMB * (fraction - neutral)
            vy += (climb - vy) * min(1.0, dt * 5)
            val inBand = target == null || profile.band(watts) == target
            val pull = if (here != null && (inBand || grace)) PULL * (here.centre - y) else 0.0
            y += (vy + pull) * dt
        } else {
            // Resting or waiting: float to the middle of the cave.
            vy = ((here?.centre ?: y) - y) * 0.8
            y += vy * dt
        }

        if (here != null) {
            val ceiling = here.centre + here.half - TUX_HALF
            val floor = here.centre - here.half + TUX_HALF
            if (y > ceiling || y < floor) {
                y = y.coerceIn(floor, ceiling)
                vy = 0.0
                // Scraping along a wall is one bonk, not one per frame.
                if (working && !grace && clock - lastBonkAt > 1.5) {
                    bonks++
                    combo = 0
                }
                lastBonkAt = clock
            }
            camera += (here.centre - camera) * min(1.0, dt * 1.5)
        }

        for (f in fish) {
            if (f.eaten || f.passed) continue
            if (f.metre <= metres && abs(y - f.height) < CATCH_RADIUS) {
                f.eaten = true
                score += (if (f.gold) 3 else 1) * multiplier
                combo++
            } else if (f.metre < metres - 1.0) {
                f.passed = true
                // Golden fish are an optional stretch; missing one doesn't break the streak.
                if (!f.gold) combo = 0
            }
        }

        // A trail of bubbles streaming off behind Tux, faster the faster he swims.
        if (s.moving && clock >= nextBubble) {
            nextBubble = clock + 0.06
            bubbles.addLast(Bubble(metres - 0.6, y + bob - 0.05 + (sin(clock * 37) * 0.05), clock, 3f + (clock * 7 % 1).toFloat() * 4f))
        }
        bubbles.forEach { it.height += 0.25 * dt }
        while (bubbles.isNotEmpty() && clock - bubbles.first().born > 1.5) bubbles.removeFirst()
        while (slices.size > 2 && slices[1].metre < metres - BEHIND) slices.removeFirst()
        while (fish.isNotEmpty() && fish.first().metre < metres - BEHIND) fish.removeFirst()
    }

    /** The cave at [metre], interpolated between slices. */
    fun sliceAt(metre: Double): Slice? {
        val i = slices.indexOfFirst { it.metre > metre }
        if (i == -1) return slices.lastOrNull()
        if (i == 0) return slices.first()
        val a = slices[i - 1]
        val b = slices[i]
        val f = (metre - a.metre) / (b.metre - a.metre)
        return Slice(metre, a.centre + (b.centre - a.centre) * f, a.half + (b.half - a.half) * f)
    }

    /** New cave and fish enter at the right edge, [LOOKAHEAD] metres ahead of Tux. */
    private fun spawn(runner: WorkoutRunner, speed: Double) {
        val profile = runner.profile
        while (nextSlice <= metres + LOOKAHEAD) {
            val m = nextSlice
            nextSlice += SLICE_METRES
            val band = runner.bandAhead((m - metres) / speed.coerceAtLeast(1.5), speed)
            // The slope that rowing at the band's middle produces, per metre at that pace.
            val slope = band?.let { CLIMB * (it.middle - NEUTRAL) / profile.speedAt(it.middle) } ?: 0.0
            base += slope * SLICE_METRES
            // A little undulation for variety, eased in and out around rests.
            undulation += ((if (band != null) 1.0 else 0.0) - undulation) * 0.08
            val wave = undulation * WAVE * sin(2 * PI * m / 55 + 2.1)
            slices.addLast(Slice(m, base + wave, HALF + (1 - undulation) * 0.4))
        }
        while (nextFish <= metres + LOOKAHEAD) {
            val m = nextFish
            nextFish += FISH_METRES
            if (runner.bandAhead((m - metres) / speed.coerceAtLeast(1.5), speed) == null && runner.phase != Phase.READY) continue
            val c = sliceAt(m) ?: continue
            fishCount++
            val (offset, gold) = when {
                // Golden fish sit where rowing at the top or bottom of the band takes you.
                fishCount % 6 == 0 -> (if (fishCount % 12 == 0) -GOLD_OFFSET else GOLD_OFFSET) to true
                else -> sin(fishCount * 1.7) * 0.1 to false
            }
            fish.addLast(Fish(m, c.centre + offset, gold))
        }
    }

    companion object {
        const val VIEW_HEIGHT = 5.0
        /** The effort, as a fraction of 2k power, at which Tux neither climbs nor sinks: Glide. */
        const val NEUTRAL = 0.70
        /** Climb rate, in world units per second, per unit of 2k power above [NEUTRAL]. */
        const val CLIMB = 1.6
        /** Pull towards the cave's middle while in band, per second. */
        const val PULL = 1.0
        const val GRACE = 6.0
        const val HALF = 0.9
        const val TUX_HALF = 0.22
        const val WAVE = 0.45
        const val GOLD_OFFSET = 0.3
        const val BOB = 0.12
        const val BOB_SECONDS = 0.8
        const val SLICE_METRES = 1.0
        const val FISH_METRES = 4.0
        const val FIRST_FISH = 12.0
        /** Metres of course visible ahead of Tux. */
        const val LOOKAHEAD = 22.0
        const val BEHIND = 15.0
        const val CATCH_RADIUS = 0.22
    }
}
