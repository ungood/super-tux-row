package name.onetrue.supertuxrower.bob

/**
 * Decides when to wake Bob: once his words have nearly typed out, sooner the faster the boat
 * goes, and straight away when something happens. Times are seconds on any monotonic clock.
 */
class WakePlanner(
    private val minimumGap: Double = 4.0,
    private val thresholds: List<Int> = listOf(1000, 500, 250, 100),
    private val milestone: Int = 500,
) {
    private var lastWake = Double.NEGATIVE_INFINITY
    private var moving = false
    private var stillSince: Double? = null
    private var announcedStop = true
    private var lastBalance = Double.POSITIVE_INFINITY
    private var lastMilestone = 0
    private val pending = mutableListOf<String>()
    /** Whether something in [pending] is worth waking early for. Balance warnings wait. */
    private var urgent = false

    /** Adds an event that wakes him as soon as he can be woken. */
    fun note(event: String) {
        pending += event
        urgent = true
    }

    /**
     * Records what's happening now; returns the events to report if it's time to wake (an empty
     * list for a routine wake), or null if not. [speed] is boat speed in m/s; [typing] is how many
     * seconds of his words are still typing out.
     */
    fun check(now: Double, rowing: Boolean, distance: Double, balance: Double, speed: Double, typing: Double): List<String>? {
        if (rowing && !moving) {
            note("The rower just started rowing.")
            announcedStop = false
        }
        if (rowing) stillSince = null else if (moving || stillSince == null) stillSince = now
        moving = rowing
        val since = stillSince
        if (!rowing && !announcedStop && since != null && now - since >= 3) {
            note("The rower has stopped.")
            announcedStop = true
        }
        thresholds.filter { balance < it && lastBalance >= it }.minOrNull()?.let { pending += "Your balance just fell below $it tokens." }
        lastBalance = balance
        val reached = (distance / milestone).toInt() * milestone
        if (reached > lastMilestone) {
            lastMilestone = reached
            note("The rower just passed $reached metres.")
        }

        if (now - lastWake < minimumGap) return null
        if (!urgent && (typing > 1.5 || now - lastWake < interval(speed))) return null
        lastWake = now
        urgent = false
        return pending.toList().also { pending.clear() }
    }

    /** The routine gap between wakes: 30 s at rest, about 15 s at a steady pace, 10 s flat out. */
    fun interval(speed: Double): Double = (32 - 5 * speed).coerceIn(10.0, 30.0)
}
