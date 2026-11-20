package name.onetrue.supertuxrower.game

import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.sensor.RowerSnapshot
import name.onetrue.supertuxrower.workout.Phase
import name.onetrue.supertuxrower.workout.WorkoutRunner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tux swims through an ice cave, laid out in metres. Your progress through the workout moves him
 * forward (see [Course]); your power sets how fast he climbs or sinks: above [NEUTRAL] (a fraction
 * of 2k power) he rises, below it he sinks, faster the further you are from it.
 *
 * The cave is planned from the whole workout: each piece's stretch slopes at the rate its target
 * power produces, so rowing the workout keeps him inside, and the camera scrolls up and down to
 * follow. When the workout ends the cave opens up and he rises towards the light.
 * While you're in the target band he's also pulled gently towards the middle of the cave, so
 * wobbles within the band don't pile up; leave the band and he drifts into a wall (a bonk).
 *
 * The cave starts wide and flat while you settle in, then alternates roomier and tighter stretches
 * ([widthAt]).
 *
 * Heights are in world units; the screen shows [VIEW_HEIGHT] of them.
 */
class CaveGame(private val random: Random = Random.Default) {
    class Slice(val metre: Double, val centre: Double, val half: Double)
    class Fish(val metre: Double, val height: Double, val gold: Boolean) {
        var eaten = false
        var passed = false
        /** When it was eaten, on [clock], and what it scored, for the scene's burst. */
        var eatenAt = 0.0
        var points = 0
        /** For a golden fish, what it took off the piece ("−2 s"), if anything. */
        var bonus: String? = null
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
    /** The plan for the whole workout, made on the first update. */
    var course: Course? = null
        private set

    /**
     * How deep Tux is, in metres. The course's highest point is [SURFACE_DEPTH] down and its lowest
     * about [DEPTH_RANGE] below that, so every workout goes from near the light into the dark.
     */
    val depth: Double
        get() {
            val c = course ?: return SURFACE_DEPTH
            val perUnit = (DEPTH_RANGE / (c.highest - c.lowest).coerceAtLeast(1e-6)).coerceIn(0.4, 10.0)
            return (SURFACE_DEPTH + (c.highest - camera) * perUnit).coerceAtLeast(1.0)
        }

    /** How much daylight reaches him, 1 near the surface fading to [MIN_LIGHT] in the deep, and back as he leaves. */
    val light: Double
        get() {
            val below = exp(-depth / LIGHT_FALLOFF).coerceAtLeast(MIN_LIGHT)
            return below + (1 - below) * (finishedFor / 4).coerceIn(0.0, 1.0)
        }

    /** Seconds since the workout ended, while Tux swims out; 0 before. */
    val finishedFor: Double get() = if (finishedAt < 0) 0.0 else clock - finishedAt
    private var finishedAt = -1.0

    /** The longest run of fish caught, and how many golden ones, for the end. */
    var bestStreak = 0
        private set
    var goldCaught = 0
        private set
    /** Your power on the band scale (0–6), eased so the gauge glides. */
    var gauge = 0.0
        private set

    var clock = 0.0
        private set
    /** When the last bonk counted, on [clock]; the scene shakes and Tux sees stars. */
    var bonkedAt = -10.0
        private set
    val sinceStroke: Double get() = clock - lastStrokeAt
    /** When Tux last ate a golden fish, for the burst of speed it gives him on screen. */
    var boostedAt = -10.0
        private set
    private var nextBreath = 0.0
    private var nextPuff = 0.0
    private var puffLeft = 0
    /** Breaths taken so far; each one is a puff of bubbles. */
    var breaths = 0
        private set
    /** The cave starts behind Tux too, so the screen is full from the first frame. */
    private var nextSlice = -BEHIND
    private var nextFish = FIRST_FISH
    private var base = 0.0
    private var undulation = 0.0
    private var lastBonkAt = -10.0
    private var lastStrokeAt = -10.0
    private var seenStrokes = -1
    private var averageFraction = 0.0
    private var piece = -1
    private var graceFrom = -GRACE
    private var holding = false
    private var wasWorking = false
    private var opening = 0.0
    private var exitAt = Double.POSITIVE_INFINITY

    /** Cosmetic bob after each stroke, in world units; it doesn't move Tux's real height. */
    val bob: Double
        get() {
            val t = clock - lastStrokeAt
            return if (t in 0.0..BOB_SECONDS) BOB * sin(PI * t / BOB_SECONDS) else 0.0
        }

    fun update(dt: Double, runner: WorkoutRunner, s: RowerSnapshot) {
        clock += dt
        val profile = runner.profile
        val course = course ?: Course(runner.workout, profile).also { course = it }
        if (runner.phase == Phase.DONE) {
            if (finishedAt < 0) {
                finishedAt = clock
                // Ended early (or an open-ended piece): the way out starts just past what's built.
                exitAt = minOf(course.end, nextSlice)
            }
            // Out of the cave at a steady swim, whatever happens on the rower now.
            metres += FINISH_SPEED * dt
        } else {
            // Glide to where your progress puts him, so a golden fish's skip forward is a quick swim.
            val target = course.position(runner)
            metres += (target - metres).coerceAtLeast(0.0) * min(1.0, dt * 4)
        }
        spawn(course)

        if (s.strokes > seenStrokes && seenStrokes >= 0 && s.moving) lastStrokeAt = clock
        seenStrokes = s.strokes

        val watts = s.smoothWatts
        val fraction = profile.fraction(watts)
        gauge += (Band.position(fraction) - gauge) * min(1.0, dt * 4)
        if (s.moving) averageFraction += (fraction - averageFraction) * min(1.0, dt / 15)

        val target = runner.target
        val working = runner.phase == Phase.WORK
        // A piece's first seconds are for bringing your effort to the target. The very first piece's
        // start from its first stroke, so waiting at the start screen doesn't use them up.
        if (working && (!wasWorking || runner.index != piece)) easeIn(hold = piece == -1 || (!wasWorking && runner.index == 0))
        piece = runner.index
        wasWorking = working
        // A golden fish's boost carries him forward fast; like a grace period, it can't end in a bonk.
        val boosting = clock - boostedAt < BOOST_SECONDS
        val grace = graceLeft > 0 || boosting
        val here = sliceAt(metres)

        if (working) {
            // Pieces without a target are measured against your own recent average.
            val neutral = if (target != null) NEUTRAL else averageFraction
            // Starting out, or after a pause, your power takes over gradually so a slow start doesn't sink him.
            val handover = if (holding) (1 - graceLeft / GRACE).coerceIn(0.0, 1.0) else 1.0
            val control = handover * handover
            val climb = CLIMB * (fraction - neutral) * control
            vy += (climb - vy) * min(1.0, dt * 5)
            val inBand = target == null || profile.band(watts) == target
            val pull = if (here != null && (inBand || grace)) PULL * (if (boosting) 4 else if (grace) 2 else 1) * (here.centre - y) else 0.0
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
                    bonkedAt = clock
                }
                lastBonkAt = clock
            }
            camera += (here.centre - camera) * min(1.0, dt * 1.5)
        }

        // Once it's over the score is final; he swims out past whatever's left.
        if (finishedAt < 0) for (f in fish) {
            if (f.eaten || f.passed) continue
            // Caught as it reaches his beak, which is [MOUTH] ahead of his middle.
            if (f.metre <= metres + MOUTH && abs(y - f.height) < CATCH_RADIUS) {
                f.eaten = true
                f.eatenAt = clock
                f.points = (if (f.gold) 3 else 1) * multiplier
                score += f.points
                if (f.gold) {
                    f.bonus = runner.bonus()
                    boostedAt = clock
                    goldCaught++
                }
                combo++
                bestStreak = maxOf(bestStreak, combo)
            } else if (f.metre < metres - 1.0) {
                f.passed = true
                // Golden fish are an optional stretch; missing one doesn't break the streak.
                if (!f.gold) combo = 0
            }
        }

        // Tux breathes out a puff of bubbles from his beak: slow and small at rest, quick and big
        // the harder you row, going by the power gauge.
        val effort = (gauge / 6).coerceIn(0.0, 1.0)
        if (clock >= nextBreath) {
            nextBreath = clock + BREATH_SLOW + (BREATH_FAST - BREATH_SLOW) * effort
            puffLeft = 2 + (effort * 5).toInt()
            breaths++
        }
        if (puffLeft > 0 && clock >= nextPuff) {
            nextPuff = clock + 0.06
            puffLeft--
            val jitter = sin(clock * 37) * 0.04
            bubbles.addLast(Bubble(metres + MOUTH * 0.85, y + bob + 0.06 + jitter, clock, 3f + (clock * 7 % 1).toFloat() * (3f + 3f * effort.toFloat())))
        }
        bubbles.forEach { it.height += 0.45 * dt }
        while (bubbles.isNotEmpty() && clock - bubbles.first().born > BUBBLE_LIFE) bubbles.removeFirst()
        while (slices.size > 2 && slices[1].metre < metres - BEHIND) slices.removeFirst()
        while (fish.isNotEmpty() && fish.first().metre < metres - BEHIND) fish.removeFirst()
    }

    /** Seconds of grace left: Tux is pulled to the middle and can't bonk while you adjust. */
    val graceLeft: Double get() = (GRACE - (clock - graceFrom)).coerceAtLeast(0.0)

    /** Seconds left of holding Tux steady while you get up to speed, at the start or after a pause. */
    val holdLeft: Double get() = if (holding) graceLeft else 0.0

    /**
     * Starts a grace period now. With [hold], as at the start or after a pause, your power also
     * takes over gradually rather than at once.
     */
    fun easeIn(hold: Boolean = true) {
        graceFrom = clock
        holding = hold
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

    /**
     * New cave and fish are built a little past the right edge, [LOOKAHEAD] metres ahead of Tux,
     * so they scroll in rather than appearing at the edge with open water before them.
     */
    private fun spawn(course: Course) {
        while (nextSlice <= metres + LOOKAHEAD + MARGIN) {
            val m = nextSlice
            nextSlice += SLICE_METRES
            // Behind the start it's level, so the cave at Tux's start is where he is.
            val stretch = course.stretchAt(m)
            base += (stretch?.rise ?: 0.0) * SLICE_METRES
            // A little undulation for variety, eased in and out around rests.
            val working = stretch != null && !stretch.rest
            undulation += ((if (working) 1.0 else 0.0) - undulation) * 0.08
            val wave = undulation * settled(m) * WAVE * sin(2 * PI * m / 55 + 2.1)
            // Past the end the ceiling lifts away, more steeply as it goes, while the floor stays.
            val exit = minOf(course.end, exitAt)
            if (m > exit) opening += 0.35 * ((m - exit) / 20).coerceAtMost(1.0) * SLICE_METRES
            slices.addLast(Slice(m, base + wave + opening / 2, HALF * widthAt(m) + (1 - undulation) * 0.4 + opening / 2))
        }
        // Fish come at irregular gaps, now and then a few together.
        while (nextFish <= metres + LOOKAHEAD + MARGIN) {
            val m = nextFish
            nextFish += FISH_GAP_MIN + random.nextDouble() * (FISH_GAP_MAX - FISH_GAP_MIN)
            if (course.stretchAt(m)?.rest != false || m > exitAt) continue
            val c = sliceAt(m) ?: continue
            val room = (c.half - TUX_HALF - 0.05).coerceAtLeast(0.0)
            if (random.nextDouble() < GOLD_CHANCE) {
                // Golden fish sit where rowing at the top or bottom of the band takes you, if there's room.
                val side = if (random.nextBoolean()) 1 else -1
                fish.addLast(Fish(m, c.centre + side * GOLD_OFFSET.coerceAtMost(room), gold = true))
                continue
            }
            val height = c.centre + (random.nextDouble() * 2 - 1) * 0.15
            val shoal = if (random.nextDouble() < SHOAL_CHANCE) 2 + random.nextInt(2) else 1
            val rise = (random.nextDouble() * 2 - 1) * 0.08
            for (k in 0 until shoal) fish.addLast(Fish(m + k * 1.3, (height + k * rise).coerceIn(c.centre - room, c.centre + room), gold = false))
            nextFish = maxOf(nextFish, m + shoal * 1.3 + FISH_GAP_MIN)
        }
    }

    /**
     * The cave's half-height at [metre], as a multiple of [HALF]. It starts [WARMUP_EXTRA] wider
     * and narrows to normal over [WARMUP_METRES]; after that it swells and tightens over a few
     * hundred metres, between about 0.72 and 1.28.
     */
    fun widthAt(metre: Double): Double {
        val settled = settled(metre)
        val sections = 0.2 * sin(2 * PI * metre / 170 + 0.7) + 0.08 * sin(2 * PI * metre / 63 + 1.9)
        return 1 + WARMUP_EXTRA * (1 - settled) + settled * sections
    }

    /** 0 at the start, easing to 1 by [WARMUP_METRES]: how far into the course's real shape. */
    private fun settled(metre: Double): Double {
        val t = (metre / WARMUP_METRES).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
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
        /** Metres between one fish (or shoal) and the next, picked at random between these. */
        const val FISH_GAP_MIN = 6.0
        const val FISH_GAP_MAX = 16.0
        const val GOLD_CHANCE = 1.0 / 7
        const val SHOAL_CHANCE = 0.25
        const val FIRST_FISH = 12.0
        /** Metres of course visible ahead of Tux. */
        const val LOOKAHEAD = 22.0
        /** Metres built beyond [LOOKAHEAD], off the right edge. */
        const val MARGIN = 4.0
        const val WARMUP_METRES = 300.0
        const val WARMUP_EXTRA = 0.6
        const val BEHIND = 15.0
        const val CATCH_RADIUS = 0.22
        /** Metres from Tux's middle to his beak, as drawn. */
        const val MOUTH = 1.0
        /** Seconds between breaths when resting and when rowing flat out. */
        const val BREATH_SLOW = 3.0
        const val BREATH_FAST = 0.7
        const val BUBBLE_LIFE = 2.0
        /** The depth at the course's highest point, and how much deeper its lowest is. */
        const val SURFACE_DEPTH = 5.0
        const val DEPTH_RANGE = 250.0
        /** How long a golden fish's boost lasts, in seconds. */
        const val BOOST_SECONDS = 1.6
        /** How fast Tux swims out of the cave once the workout's over, in metres per second. */
        const val FINISH_SPEED = 4.0
        const val LIGHT_FALLOFF = 80.0
        const val MIN_LIGHT = 0.15
    }
}
