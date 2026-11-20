package name.onetrue.supertuxrower.game

import kotlin.random.Random

/**
 * The big creatures passing far behind the cave, one at a time. Each kind lives at certain depths
 * (by daylight) and needs room: none in a plain cave, and whales and hunters only where the water
 * is wide open. One comes along every [GAP_MIN] to [GAP_MAX] seconds, when one suits where Tux is.
 */
class Sightings(private val random: Random = Random.Default) {
    /**
     * A kind of giant. [minOpen] is how much of the screen's height must be open water. Hunters
     * cross fast, swooping. [cue] is the sound it makes as it comes by.
     */
    class Kind(
        val name: String,
        val weight: Double,
        val minLight: Double = 0.0,
        val maxLight: Double = 1.0,
        val minOpen: Double = 0.5,
        val hunts: Boolean = false,
        val cue: Cue? = null,
    ) {
        /** How fast it crosses the screen, in pixels per second at the tablet's width. */
        val speed: Double get() = if (hunts) 520.0 else 160.0
        /** Seconds from coming in at the right to leaving at the left. */
        val crossing: Double get() = (SCREEN + 2 * MARGIN) / speed
    }

    class Sighting(val kind: Kind, val id: Int, val height: Double) {
        /** Seconds since it came in at the right. */
        var age = 0.0
            internal set
    }

    var current: Sighting? = null
        private set
    private var wait = FIRST
    private var count = 0

    fun update(dt: Double, light: Double, openness: Double) {
        current?.let {
            it.age += dt
            if (it.age > it.kind.crossing) current = null
            return
        }
        wait -= dt
        if (wait > 0) return
        val here = KINDS.filter { light in it.minLight..it.maxLight && openness >= it.minOpen }
        // Nothing lives here: look again shortly.
        val kind = pick(here) ?: run { wait = 3.0; return }
        current = Sighting(kind, count++, random.nextDouble())
        wait = GAP_MIN + random.nextDouble() * (GAP_MAX - GAP_MIN)
    }

    /** Sends [name] across now, whatever the depth (`just do giant`). */
    fun summon(name: String) {
        val kind = KINDS.firstOrNull { it.name == name } ?: return
        current = Sighting(kind, count++, 0.5)
    }

    private fun pick(kinds: List<Kind>): Kind? {
        var left = random.nextDouble() * kinds.sumOf { it.weight }
        for (k in kinds) {
            left -= k.weight
            if (left < 0) return k
        }
        return kinds.lastOrNull()
    }

    companion object {
        const val SCREEN = 1920.0
        /** How far off each edge of the screen a giant starts and ends, in pixels. */
        const val MARGIN = 900.0
        const val FIRST = 40.0
        const val GAP_MIN = 50.0
        const val GAP_MAX = 110.0

        val KINDS = listOf(
            Kind("whale", 0.15, minLight = 0.35, minOpen = 0.7, cue = Cue.WHALE_SONG),
            Kind("orca", 0.1, minLight = 0.3, minOpen = 0.65, hunts = true, cue = Cue.ORCA),
            Kind("leopardseal", 0.08, minLight = 0.35, minOpen = 0.55, hunts = true, cue = Cue.ORCA),
            Kind("belugas", 0.08, minLight = 0.4, minOpen = 0.6, cue = Cue.BELUGA),
            Kind("narwhal", 0.06, minLight = 0.3, minOpen = 0.55),
            Kind("manta", 0.1, minLight = 0.25, minOpen = 0.6),
            Kind("whaleshark", 0.08, minLight = 0.3, minOpen = 0.75),
            Kind("squid", 0.13, maxLight = 0.6, minOpen = 0.5),
            Kind("octopus", 0.12, minOpen = 0.5),
            Kind("anglerfish", 0.12, maxLight = 0.35, minOpen = 0.5),
            Kind("spermwhale", 0.04, maxLight = 0.5, minOpen = 0.75, cue = Cue.WHALE_SONG),
        )
    }
}
