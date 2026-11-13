package name.onetrue.supertuxrower.model

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

object Rowing {
    /** Measured against the S4's own distance counter; see issue #5. */
    const val PULSES_PER_METRE = 96.0

    private const val WATTS_PER_MPS3 = 2.8

    /** The standard ergometer pace-to-power relation. */
    fun watts(speed: Double) = WATTS_PER_MPS3 * speed.pow(3)

    fun speed(watts: Double) = cbrt(watts / WATTS_PER_MPS3)

    /** Concept2's estimate: mechanical power at 25% efficiency, in kcal/h, plus 300 kcal/h. */
    fun kcalPerHour(watts: Double) = watts * 4 * 0.8604 + 300
}

/** Time per 500 m as m:ss.s. */
fun formatSplit(speed: Double): String {
    if (speed < 0.5) return "–:––"
    // Round to tenths before splitting off minutes, or 119.99 s shows as 1:60.0.
    val tenths = (5000 / speed).roundToInt()
    return "%d:%02d.%d".format(tenths / 600, tenths % 600 / 10, tenths % 10)
}

fun formatClock(seconds: Double): String {
    val t = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(t / 60, t % 60)
}
