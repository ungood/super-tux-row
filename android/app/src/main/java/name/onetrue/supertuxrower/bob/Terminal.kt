package name.onetrue.supertuxrower.bob

/**
 * Bob's terminal: lines that type themselves out, newest at the bottom. Bob's words type at a pace
 * set by the rower, sped up so the backlog never lags more than [maxLag] seconds behind. Events
 * and tool calls appear at once, and first flush any words still typing, so they're never late.
 */
class Terminal(private val maxLag: Double = 2.0, private val keep: Int = 200) {
    enum class Kind { SPEECH, EVENT, TOOL, RESULT }

    class Line(val kind: Kind, text: String) {
        var text = text
            internal set
        var shown = 0
            internal set
        val visible: String get() = text.take(shown)
    }

    private val lines = ArrayDeque<Line>()
    private var carry = 0.0
    /** Characters per second the last [advance] typed at. */
    var rate = 0.0
        private set

    val all: List<Line> get() = lines

    /** Characters written but not yet typed out. */
    val backlog: Int get() = lines.sumOf { it.text.length - it.shown }

    /** Seconds until everything written is typed out, at the current pace. */
    val secondsLeft: Double get() = if (backlog == 0) 0.0 else backlog / rate.coerceAtLeast(1.0)

    /** Adds Bob's words, continuing his current line; "\n" starts a new one. */
    fun speak(text: String) {
        text.split('\n').forEachIndexed { i, part ->
            val last = lines.lastOrNull()
            if (i == 0 && last != null && last.kind == Kind.SPEECH) last.text += part
            else add(Line(Kind.SPEECH, part))
        }
    }

    /** Ends Bob's current line, so his next words start a new one. */
    fun breakLine() {
        if (lines.lastOrNull()?.let { it.kind == Kind.SPEECH && it.text.isNotEmpty() } == true) add(Line(Kind.SPEECH, ""))
    }

    /** Shows a line at once, after typing out anything still pending. */
    fun show(kind: Kind, text: String) {
        lines.forEach { it.shown = it.text.length }
        add(Line(kind, text).also { it.shown = text.length })
    }

    /** Types for [seconds] at [baseRate] characters a second, faster if the backlog needs it. */
    fun advance(seconds: Double, baseRate: Double) {
        rate = maxOf(baseRate, backlog / maxLag)
        carry += rate * seconds
        var budget = carry.toInt()
        carry -= budget
        for (line in lines) {
            if (budget == 0) break
            val step = minOf(budget, line.text.length - line.shown)
            line.shown += step
            budget -= step
        }
    }

    private fun add(line: Line) {
        lines.addLast(line)
        while (lines.size > keep) lines.removeFirst()
    }
}
