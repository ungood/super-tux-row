package name.onetrue.supertuxrower.sensor

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import name.onetrue.supertuxrower.model.Rowing

/** A moment's view of the rower, as read by the workout each frame. */
data class RowerSnapshot(
    /** Metres since the source started. */
    val distance: Double,
    /** Boat speed averaged over the last few strokes, like a monitor shows. */
    val speed: Double,
    /** Smoothed instantaneous speed, for animation. */
    val liveSpeed: Double,
    val spm: Double,
    val strokes: Int,
    val driving: Boolean,
    /** Power and duration of the last completed stroke alone, or 0 if there isn't a recent one. */
    val lastStrokeWatts: Double = 0.0,
    val lastStrokeSeconds: Double = 0.0,
    /**
     * Speed over a rolling window one stroke long, updated with every pulse line: no steps at
     * each stroke, no ripple within a stroke, and only about half a stroke of lag.
     */
    val smoothSpeed: Double = 0.0,
) {
    val watts: Double get() = Rowing.watts(speed)
    val smoothWatts: Double get() = Rowing.watts(smoothSpeed)
    val moving: Boolean get() = liveSpeed > 0.2
}

/**
 * Turns the S4 event stream into metrics. Not thread-safe: call [handle] on the main thread.
 */
class Rower {
    var connected by mutableStateOf(false)
    var status by mutableStateOf("Starting…")

    var lastLineAt = 0L
        private set

    /**
     * Seconds covered by each Pxx line. Nominally 25 ms, but this S4 sends ~42 a second, so it is
     * measured from the stream while the flywheel is turning.
     */
    var windowSeconds = 0.0239
        private set
    private var timedWindows = 0
    private var timedSeconds = 0.0
    private var lastPulseAt = 0L

    private var totalPulses = 0
    private var pulseRate = 0.0
    private var strokes = 0
    private var driving = false
    private var lastStrokeAt = 0L

    /**
     * Completed strokes, timed by counting the S4's windows rather than by when lines reach us: the
     * main thread can deliver them late and in bursts, which made single strokes look wildly fast
     * or slow.
     */
    private class Stroke(val pulses: Int, val windows: Int)
    private val recentStrokes = ArrayDeque<Stroke>()
    private var lastStroke: Stroke? = null
    private var inStroke = false
    private var strokePulses = 0
    private var strokeWindows = 0

    private val distance get() = totalPulses / Rowing.PULSES_PER_METRE

    /** Recent pulse counts, one per window, for the rolling one-stroke speed. */
    private val recentWindows = IntArray(512)
    private var windowHead = 0
    private var windowCount = 0

    fun handle(line: String, now: Long = SystemClock.elapsedRealtime()) {
        lastLineAt = now
        when {
            line == "_WR_" -> {
                connected = true
                status = "Connected"
            }
            line == "SS" -> {
                if (inStroke && strokeWindows > 0) {
                    if (strokeWindows * windowSeconds * 1000 < MAX_STROKE_GAP_MS) {
                        recentStrokes.addLast(Stroke(strokePulses, strokeWindows).also { lastStroke = it })
                        while (recentStrokes.size > STROKES_AVERAGED) recentStrokes.removeFirst()
                    } else {
                        recentStrokes.clear()
                    }
                }
                inStroke = true
                strokePulses = 0
                strokeWindows = 0
                lastStrokeAt = now
                strokes++
                driving = true
            }
            line == "SE" -> driving = false
            line == "PING" -> {
                pulseRate = 0.0
                driving = false
                inStroke = false
                windowCount = 0
                recentStrokes.clear()
            }
            line.length == 3 && line[0] == 'P' -> {
                val n = line.substring(1).toIntOrNull(16) ?: return
                totalPulses += n
                recentWindows[windowHead] = n
                windowHead = (windowHead + 1) % recentWindows.size
                windowCount = minOf(windowCount + 1, recentWindows.size)
                if (inStroke) {
                    strokePulses += n
                    strokeWindows++
                }
                timeWindow(now)
                pulseRate += 0.05 * (n / windowSeconds - pulseRate)
            }
        }
    }

    private fun timeWindow(now: Long) {
        val gap = now - lastPulseAt
        lastPulseAt = now
        // Bursts and delays average out over many lines; long gaps are pauses, not windows.
        if (gap in 0..999) {
            timedSeconds += gap / 1000.0
            timedWindows++
            if (timedWindows >= 200) windowSeconds = (timedSeconds / timedWindows).coerceIn(0.020, 0.030)
        }
    }

    /** Called when the connection drops or the source changes. */
    fun disconnected(why: String) {
        connected = false
        status = why
        pulseRate = 0.0
        driving = false
    }

    fun touch(now: Long = SystemClock.elapsedRealtime()) {
        lastLineAt = now
    }

    fun snapshot(now: Long = SystemClock.elapsedRealtime()): RowerSnapshot {
        val stale = now - lastLineAt > 3000
        val liveSpeed = if (stale) 0.0 else pulseRate / Rowing.PULSES_PER_METRE
        val recent = now - lastStrokeAt < MAX_STROKE_GAP_MS && recentStrokes.isNotEmpty()
        val windows = recentStrokes.sumOf { it.windows }
        val speed = if (recent) recentStrokes.sumOf { it.pulses } / (windows * windowSeconds) / Rowing.PULSES_PER_METRE else liveSpeed
        val spm = if (recent) 60 / (windows * windowSeconds / recentStrokes.size) else 0.0
        val last = lastStroke.takeIf { recent }
        val lastSeconds = last?.let { it.windows * windowSeconds } ?: 0.0
        val lastWatts = last?.let { Rowing.watts(it.pulses / lastSeconds / Rowing.PULSES_PER_METRE) } ?: 0.0
        return RowerSnapshot(distance, speed, liveSpeed, spm, strokes, driving && !stale, lastWatts, lastSeconds, if (stale) 0.0 else rollingSpeed())
    }

    /** Speed over the last stroke's worth of windows (or ~2.3 s before the first stroke). */
    private fun rollingSpeed(): Double {
        val length = (lastStroke?.windows ?: (2.3 / windowSeconds).toInt()).coerceAtMost(windowCount)
        if (length < 20) return pulseRate / Rowing.PULSES_PER_METRE
        var pulses = 0
        for (i in 1..length) pulses += recentWindows[(windowHead - i + recentWindows.size) % recentWindows.size]
        return pulses / (length * windowSeconds) / Rowing.PULSES_PER_METRE
    }

    private companion object {
        const val MAX_STROKE_GAP_MS = 6000L
        const val STROKES_AVERAGED = 3
    }
}
