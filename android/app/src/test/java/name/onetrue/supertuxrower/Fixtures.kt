package name.onetrue.supertuxrower

import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.Profile
import name.onetrue.supertuxrower.model.Rowing
import name.onetrue.supertuxrower.sensor.RowerSnapshot
import java.io.File

/** An 8:00 2k profile. */
val testProfile = Profile(id = "test", name = "Test", twoKSeconds = 480)

fun Profile.wattsFor(band: Band) = twoKWatts * band.middle

/**
 * A pretend rower that produces snapshots directly, for driving the workout and the game in
 * tests: steady power, a stroke every 60/spm seconds.
 */
class FakeRower(var watts: Double = 0.0, var spm: Double = 24.0) {
    var distance = 0.0
        private set
    private var strokes = 0
    private var sinceStroke = 0.0

    fun step(dt: Double): RowerSnapshot {
        val speed = if (watts > 0) Rowing.speed(watts) else 0.0
        distance += speed * dt
        sinceStroke += dt
        if (speed > 0 && sinceStroke >= 60 / spm) {
            sinceStroke = 0.0
            strokes++
        }
        return RowerSnapshot(
            distance = distance,
            speed = speed,
            liveSpeed = speed,
            spm = if (speed > 0) spm else 0.0,
            strokes = strokes,
            driving = false,
            lastStrokeWatts = watts,
            lastStrokeSeconds = 60 / spm,
            smoothSpeed = speed,
        )
    }
}

/** A recorded session from tools/log.py: (seconds, line) pairs, as the S4 sent them. */
fun capture(name: String): List<Pair<Double, String>> {
    var dir = File("").absoluteFile
    while (!File(dir, "captures").isDirectory) dir = dir.parentFile ?: error("captures/ not found")
    val event = Regex(""""t": ([0-9.]+), "k": "(\w+)"(?:, "n": (\d+))?""")
    return File(dir, "captures/$name").readLines().mapNotNull { line ->
        val m = event.find(line) ?: return@mapNotNull null
        val (t, kind, n) = m.destructured
        val text = when (kind) {
            "P" -> "P%02X".format(n.toInt())
            "SS", "SE", "PING" -> kind
            else -> return@mapNotNull null
        }
        t.toDouble() to text
    }
}
