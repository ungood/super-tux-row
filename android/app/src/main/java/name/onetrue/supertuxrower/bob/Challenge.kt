package name.onetrue.supertuxrower.bob

/**
 * A challenge Bob sets the rower: hold [watts] on average for [seconds]. [terms] is what Bob
 * offered in return, shown alongside.
 */
class Challenge(val watts: Int, val seconds: Int, val terms: String) {
    var elapsed = 0.0
        private set
    private var work = 0.0

    val done: Boolean get() = elapsed >= seconds
    val averageWatts: Double get() = if (elapsed > 0) work / elapsed else 0.0
    val met: Boolean get() = done && averageWatts >= watts
    val progress: Float get() = (elapsed / seconds).toFloat().coerceIn(0f, 1f)

    fun advance(watts: Double, dt: Double) {
        if (done) return
        val step = minOf(dt, seconds - elapsed)
        elapsed += step
        work += watts * step
    }
}
