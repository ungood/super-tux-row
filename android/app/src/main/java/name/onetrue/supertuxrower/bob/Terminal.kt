package name.onetrue.supertuxrower.bob

/**
 * Bob's terminal, as the bytes a VT100 terminal emulator would be sent: blocks in the style of
 * Claude Code, word-wrapped to [columns]. Bob's words type at a pace set by the rower, sped up so
 * the backlog never lags more than [maxLag] seconds behind. Everything else (tool calls, their
 * output, events) appears at once, and first flushes any words still typing, so it's never late.
 */
class Terminal(private val maxLag: Double = 2.0) {
    enum class Kind(val prefix: String, val prefixStyle: String, val style: String, val indent: Int, val gap: Boolean) {
        SPEECH("● ", "\u001b[0;97m", "\u001b[0;97m", 2, true),
        TOOL("● ", "\u001b[0;32m", "\u001b[0;1;97m", 2, true),
        RESULT("  ⎿  ", "\u001b[0;90m", "\u001b[0;37m", 5, false),
        EVENT("▸ ", "\u001b[0;33m", "\u001b[0;33m", 2, true),
    }

    private class Block(val kind: Kind, var open: Boolean) {
        val text = StringBuilder()
        var pos = 0
        var flush = false
        var started = false
    }

    /** Width of the screen in characters; set by whatever draws it. */
    var columns = 80

    private val queue = ArrayDeque<Block>()
    /** The block Bob is speaking into, until his turn ends. */
    private var speech: Block? = null
    private var col = 0
    private var lineChars = 0
    /** Spaces after a word, written only once the next word is known to fit on the line. */
    private var spaces = 0
    private var blankLines = 0
    private var wrote = false
    private var status: String? = null
    private var statusShown: String? = null
    private var carry = 0.0

    /** Says how many speech characters may be typed so far, or null to type at the rower's pace. */
    private var pacer: (() -> Int?)? = null
    /** Speech characters typed so far, counted as [speak] received them. */
    var speechTyped = 0
        private set

    /** Characters per second the last [advance] typed at. */
    var rate = 0.0
        private set

    /** Characters written but not yet typed out. */
    val backlog: Int get() = queue.filter { !it.flush }.sumOf { it.text.length - it.pos }

    /** Seconds until everything written is typed out, at the current pace. */
    val secondsLeft: Double get() = if (backlog == 0) 0.0 else backlog / rate.coerceAtLeast(1.0)

    /** Adds Bob's words, continuing what he was saying; returns the text as kept, for the voice to count alike. */
    fun speak(text: String): String {
        val b = speech?.takeIf { it.open } ?: Block(Kind.SPEECH, open = true).also { speech = it }
        val kept = if (b.text.isEmpty() && b.pos == 0) text.trimStart() else text
        b.text.append(kept)
        if (b !in queue && b.pos < b.text.length) queue.addLast(b)
        return kept
    }

    /**
     * Lets something else, such as a voice, set the pace: Bob's words are typed only as far as
     * [pacer] allows, and other blocks wait their turn behind them. Null goes back to the rower's pace.
     */
    fun paceBy(pacer: (() -> Int?)?) {
        this.pacer = pacer
        if (pacer == null) queue.forEach { it.flush = true }
    }

    /** Ends what Bob was saying; his next words start a new block. */
    fun breakLine() {
        speech?.open = false
        speech = null
    }

    /** Shows a block at once, after typing out anything still pending; when paced, in its turn. */
    fun show(kind: Kind, text: String) {
        breakLine()
        if (pacer?.invoke() == null) queue.forEach { it.flush = true }
        queue.addLast(Block(kind, open = false).also { it.text.append(text); it.flush = true })
    }

    /** A line shown at the bottom while nothing else is happening, such as "Thinking…"; null hides it. */
    fun status(text: String?) {
        status = text
    }

    /** Types for [seconds] at [baseRate] characters a second, faster if the backlog needs it; returns what to send. */
    fun advance(seconds: Double, baseRate: Double): String {
        rate = maxOf(baseRate, backlog / maxLag)
        carry += rate * seconds
        var budget = carry.toInt()
        carry -= budget
        val out = StringBuilder()
        val cap = pacer?.invoke()
        fun allowed(b: Block) = b.flush || if (cap != null && b.kind == Kind.SPEECH) speechTyped < cap else budget > 0
        fun step(b: Block) {
            b.pos++
            if (b.kind == Kind.SPEECH) speechTyped++
        }
        loop@ while (queue.isNotEmpty()) {
            val b = queue.first()
            if (b.pos >= b.text.length) {
                if (b.open && b === speech) break
                queue.removeFirst()
                continue
            }
            if (!allowed(b)) break
            clearStatus(out)
            if (!b.started) start(b, out)
            while (b.pos < b.text.length) {
                if (!allowed(b)) break@loop
                val c = b.text[b.pos]
                if (c == '\n') {
                    spaces = 0
                    if (lineChars > 0 || blankLines == 0) newline(b, out, blank = lineChars == 0)
                    step(b)
                    continue
                }
                if (c == ' ') {
                    if (lineChars > 0) spaces++
                    step(b)
                    continue
                }
                if (lineChars == 0 || b.text.getOrNull(b.pos - 1) == ' ') {
                    // The start of a word: wrap before it if it won't fit, once we know how long it is.
                    val end = b.text.indexOfAny(charArrayOf(' ', '\n'), b.pos).let { if (it < 0) b.text.length else it }
                    val length = end - b.pos
                    if (end == b.text.length && b.open && !b.flush && length < columns - b.kind.indent) break@loop
                    if (col + spaces + length > columns && lineChars > 0) newline(b, out, blank = false)
                    repeat(spaces) { put(' ', out) }
                    spaces = 0
                }
                if (col >= columns) newline(b, out, blank = false)
                put(c, out)
                step(b)
                if (!b.flush && cap == null) budget--
            }
        }
        if (queue.all { it.pos >= it.text.length } && status != statusShown) {
            clearStatus(out)
            status?.let { s ->
                if (lineChars > 0) breakLine()
                freshLine(out)
                out.append("\u001b[0;33m").append(s.take(columns - 1)).append("\u001b[0m")
                statusShown = s
            }
        }
        return out.toString()
    }

    private fun clearStatus(out: StringBuilder) {
        if (statusShown == null) return
        out.append("\r\u001b[2K")
        statusShown = null
        col = 0
        lineChars = 0
    }

    /** Moves to the start of an empty line. */
    private fun freshLine(out: StringBuilder) {
        if (lineChars > 0) out.append("\r\n") else if (col > 0) out.append("\r")
        if (lineChars > 0) blankLines = 0
        col = 0
        lineChars = 0
    }

    private fun start(b: Block, out: StringBuilder) {
        spaces = 0
        freshLine(out)
        if (b.kind.gap && wrote && blankLines == 0) out.append("\r\n")
        out.append(b.kind.prefixStyle).append(b.kind.prefix).append(b.kind.style)
        col = b.kind.prefix.length
        lineChars = 0
        blankLines = 0
        b.started = true
        wrote = true
    }

    private fun newline(b: Block, out: StringBuilder, blank: Boolean) {
        spaces = 0
        out.append("\r\n").append(" ".repeat(b.kind.indent))
        col = b.kind.indent
        lineChars = 0
        blankLines = if (blank) blankLines + 1 else 0
    }

    private fun put(c: Char, out: StringBuilder) {
        out.append(c)
        col++
        lineChars++
        blankLines = 0
    }
}
