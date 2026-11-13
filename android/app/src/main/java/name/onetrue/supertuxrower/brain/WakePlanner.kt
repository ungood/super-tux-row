package name.onetrue.supertuxrower.brain

/**
 * Decides when to wake the agent: when its scrolling words are about to run out, when something
 * it would care about happens, and at least every [interval]. Times are seconds on any monotonic
 * clock.
 */
class WakePlanner(
    private val interval: Double = 30.0,
    private val minimumGap: Double = 6.0,
    /** Wake when fewer than this many seconds of unscrolled words are left. */
    private val lead: Double = 8.0,
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

    /**
     * Records what's happening now; returns the events to report if it's time to wake (an empty
     * list for a routine wake), or null if not.
     */
    fun check(now: Double, rowing: Boolean, distance: Double, balance: Double, wordsLeft: Double = Double.MAX_VALUE): List<String>? {
        if (rowing && !moving) {
            pending += "The rower just started rowing."
            urgent = true
            announcedStop = false
        }
        if (rowing) stillSince = null else if (moving || stillSince == null) stillSince = now
        moving = rowing
        val since = stillSince
        if (!rowing && !announcedStop && since != null && now - since >= 4) {
            pending += "The rower has stopped."
            urgent = true
            announcedStop = true
        }
        thresholds.filter { balance < it && lastBalance >= it }.minOrNull()?.let { pending += "Your balance just fell below $it tokens." }
        lastBalance = balance
        val reached = (distance / milestone).toInt() * milestone
        if (reached > lastMilestone) {
            lastMilestone = reached
            pending += "The rower just passed $reached metres."
            urgent = true
        }

        if (now - lastWake < minimumGap) return null
        if (!urgent && wordsLeft >= lead && now - lastWake < interval) return null
        lastWake = now
        urgent = false
        return pending.toList().also { pending.clear() }
    }
}
