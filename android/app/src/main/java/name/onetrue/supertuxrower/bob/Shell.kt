package name.onetrue.supertuxrower.bob

/**
 * A bash session on [Machine], as Bob's bash tool sees it: not a terminal, so anything that
 * needs one (an editor, a password prompt) fails the way it would for real. It parses the
 * shell Claude tends to write: pipes, lists, redirections, heredocs, quoting, `$(…)`, `$((…))`,
 * for/while/if, and runs the commands in [Commands] against the machine's files.
 */
class Shell(val machine: Machine) {
    var cwd = HOME
        internal set
    internal val vars = mutableMapOf<String, String>()
    internal var status = 0
    /** Uptime until which sudo needs no password, as real sudo remembers for 15 minutes. */
    internal var sudoUntil = -1.0
    internal val history = mutableListOf<String>()
    private var steps = 0
    /** Told about bugs in the simulated commands; the app logs them. */
    var onCrash: (String, Exception) -> Unit = { _, _ -> }

    init {
        reset()
    }

    /** A new session, as the bash tool's restart gives. */
    fun reset() {
        cwd = HOME
        vars.clear()
        vars += mapOf(
            "HOME" to HOME, "USER" to "bob", "LOGNAME" to "bob", "SHELL" to "/bin/bash", "PATH" to PATH,
            "LANG" to "C.UTF-8", "HOSTNAME" to World.HOST,
        )
        status = 0
    }

    /** Runs one command line and returns what a terminal would have shown. */
    fun run(command: String): String {
        history += command
        val screen = StringBuilder()
        steps = 0
        try {
            val tree = Parser(command).parseAll()
            status = exec(tree, "", screen, screen, "bob")
        } catch (e: SyntaxError) {
            screen.append("bash: syntax error near unexpected token `${e.token}'\n")
            status = 2
        } catch (e: ExitShell) {
            status = e.code
            reset()
        } catch (e: TooLong) {
            screen.append("\n[the command was still running after ${MAX_STEPS} steps; it was stopped]\n")
            status = 130
        } catch (e: RuntimeException) {
            // A bug in a simulated command. Bob sees a crashed program, as he would on a real
            // machine, rather than his whole turn failing and being replayed into the same bug.
            onCrash(command, e)
            screen.append("Segmentation fault (core dumped)\n")
            status = 139
        }
        val text = screen.toString()
        return if (text.length > OUTPUT_LIMIT) text.take(OUTPUT_LIMIT) + "\n[output truncated: ${text.length} characters]" else text
    }

    internal class Ctx(
        val shell: Shell,
        val name: String,
        val args: List<String>,
        val stdin: String,
        val out: StringBuilder,
        val err: StringBuilder,
        val user: String,
        val env: Map<String, String>,
    ) {
        val root: Boolean get() = user == "root"
        fun fail(message: String, code: Int = 1): Int {
            err.append(message).append('\n')
            return code
        }
        fun print(text: String) {
            out.append(text)
        }
        fun println(text: String = "") {
            out.append(text).append('\n')
        }
    }

    // ---- Execution ----

    private fun exec(c: Cmd, stdin: String, out: StringBuilder, err: StringBuilder, user: String): Int {
        if (++steps > MAX_STEPS) throw TooLong()
        return when (c) {
            is Seq -> {
                var st = 0
                c.items.forEachIndexed { k, item ->
                    val op = if (k == 0) ";" else c.ops[k - 1]
                    if (op == "&&" && st != 0) return@forEachIndexed
                    if (op == "||" && st == 0) return@forEachIndexed
                    st = exec(item, stdin, out, err, user)
                    status = st
                }
                st
            }
            is Pipe -> {
                var input = stdin
                var st = 0
                c.cmds.forEachIndexed { k, cmd ->
                    val sink = if (k == c.cmds.lastIndex) out else StringBuilder()
                    st = exec(cmd, input, sink, err, user)
                    input = sink.toString()
                }
                if (c.negate) (if (st == 0) 1 else 0) else st
            }
            is Group -> {
                if (!c.subshell) return exec(c.body, stdin, out, err, user)
                val saved = cwd to HashMap(vars)
                try {
                    exec(c.body, stdin, out, err, user)
                } finally {
                    cwd = saved.first
                    vars.clear()
                    vars += saved.second
                }
            }
            is For -> {
                var st = 0
                val items = c.items.flatMap { expand(it, err, user) }
                for (v in items) {
                    vars[c.name] = v
                    st = exec(c.body, stdin, out, err, user)
                }
                st
            }
            is If -> if (exec(c.cond, stdin, out, err, user) == 0) exec(c.then, stdin, out, err, user)
            else c.orElse?.let { exec(it, stdin, out, err, user) } ?: 0
            is While -> {
                var st = 0
                var n = 0
                while (n++ < 500) {
                    val ok = exec(c.cond, stdin, out, err, user) == 0
                    if (ok == c.until) break
                    st = exec(c.body, stdin, out, err, user)
                }
                st
            }
            is Simple -> simple(c, stdin, out, err, user)
        }
    }

    private fun simple(c: Simple, stdin: String, out: StringBuilder, err: StringBuilder, user: String): Int {
        val words = c.words.flatMap { expand(it, err, user) }
        val assigns = c.assigns.map { (k, w) -> k to expandJoined(w, err, user) }
        var input = stdin
        var o = out
        var e = err
        val writes = mutableListOf<Triple<String, Boolean, StringBuilder>>()
        for (r in c.redirs) {
            val target = r.target?.let { expandJoined(it, err, user) }.orEmpty()
            when (r.op) {
                "<" -> {
                    val read = readFile(target, user)
                    if (read.isFailure) return fail(err, "bash: $target: ${read.exceptionOrNull()!!.message}")
                    input = read.getOrThrow()
                }
                "<<" -> input = r.heredoc.orEmpty()
                "<<<" -> input = target + "\n"
                ">", ">>", "&>" -> {
                    val sink = StringBuilder()
                    if (target != "/dev/null") {
                        val problem = writeProblem(target, user)
                        if (problem != null) return fail(err, "bash: $target: $problem")
                        writes += Triple(target, r.op == ">>", sink)
                    }
                    when {
                        r.op == "&>" -> { o = sink; e = sink }
                        r.fd == 2 -> e = sink
                        else -> o = sink
                    }
                }
                ">&" -> when {
                    r.fd == 2 && target == "1" -> e = o
                    r.fd == 1 && target == "2" -> o = e
                }
            }
        }
        val st = if (words.isEmpty()) {
            assigns.forEach { (k, v) -> vars[k] = v }
            0
        } else {
            runCommand(words, assigns.toMap(), input, o, e, user)
        }
        for ((path, append, sink) in writes) {
            val problem = writeFile(path, sink.toString(), append, user)
            if (problem != null) err.append("bash: $path: $problem\n")
        }
        return st
    }

    private fun fail(err: StringBuilder, message: String): Int {
        err.append(message).append('\n')
        return 1
    }

    /** Runs [words] as a command, as [user], with [extraEnv] set for it alone. */
    internal fun runCommand(
        words: List<String>, extraEnv: Map<String, String>, stdin: String, out: StringBuilder, err: StringBuilder, user: String,
    ): Int {
        if (++steps > MAX_STEPS) throw TooLong()
        val name = words[0]
        val env = vars + extraEnv
        val ctx = { cmd: String -> Ctx(this, cmd, words.drop(1), stdin, out, err, user, env) }
        if ('/' in name) {
            val path = abs(name)
            val node = machine.file(path) ?: return fail(err, "bash: $name: No such file or directory").let { 127 }
            if (node.dir) return fail(err, "bash: $name: Is a directory").let { 126 }
            if (!canExec(node, user) || !reachable(path, user)) return fail(err, "bash: $name: Permission denied").let { 126 }
            val base = path.substringAfterLast('/')
            Commands.programs[base]?.let { return it(ctx(base)) }
            if (node.content.startsWith("#!") || !node.content.startsWith("\u007fELF")) {
                return script(node.content, words.drop(1), stdin, out, err, user)
            }
            return fail(err, "bash: $name: cannot execute binary file: Exec format error").let { 126 }
        }
        Commands.builtins[name]?.let { return it(ctx(name)) }
        Commands.programs[name]?.let { cmd ->
            if (Commands.local(name) && machine.file("/usr/local/bin/$name") == null) return@let
            return cmd(ctx(name))
        }
        // A script Bob wrote and put on the PATH.
        PATH.split(':').forEach { dir ->
            val node = machine.file("$dir/$name")
            if (node != null && !node.dir && canExec(node, user)) return script(node.content, words.drop(1), stdin, out, err, user)
        }
        err.append("bash: $name: command not found\n")
        return 127
    }

    /** Runs a script's text in a subshell. */
    internal fun script(text: String, args: List<String>, stdin: String, out: StringBuilder, err: StringBuilder, user: String): Int {
        val saved = cwd to HashMap(vars)
        args.forEachIndexed { i, a -> vars["${i + 1}"] = a }
        vars["#"] = args.size.toString()
        vars["@"] = args.joinToString(" ")
        return try {
            source(text, stdin, out, err, user)
        } catch (e: ExitShell) {
            e.code
        } finally {
            cwd = saved.first
            vars.clear()
            vars += saved.second
        }
    }

    /** Runs text in this shell, as `source` does. */
    internal fun source(text: String, stdin: String, out: StringBuilder, err: StringBuilder, user: String): Int = try {
        exec(Parser(text).parseAll(), stdin, out, err, user)
    } catch (e: SyntaxError) {
        fail(err, "bash: syntax error near unexpected token `${e.token}'").let { 2 }
    }

    // ---- Expansion ----

    private fun expandJoined(w: Word, err: StringBuilder, user: String): String = expand(w, err, user, split = false).joinToString(" ")

    private fun expand(w: Word, err: StringBuilder, user: String, split: Boolean = true): List<String> {
        val fields = mutableListOf<StringBuilder>()
        var current: StringBuilder? = null
        var quoted = false
        var glob = false
        fun add(text: String) {
            val cur = current ?: StringBuilder().also { fields += it; current = it }
            cur.append(text)
        }
        fun addSplit(value: String) {
            if (!split) return add(value)
            if (value.isBlank()) {
                if (value.isNotEmpty()) current = null
                return
            }
            if (value.first().isWhitespace()) current = null
            value.trim().split(Regex("\\s+")).forEachIndexed { i, piece ->
                if (i > 0) current = null
                add(piece)
            }
            if (value.last().isWhitespace()) current = null
        }
        w.parts.forEachIndexed { i, part ->
            when (part) {
                is Lit -> {
                    var text = part.text
                    if (i == 0 && !part.quoted && (text == "~" || text.startsWith("~/"))) text = (vars["HOME"] ?: HOME) + text.drop(1)
                    if (part.quoted) quoted = true else if (text.any { it == '*' || it == '?' || it == '[' }) glob = true
                    add(text)
                }
                is Var -> {
                    val value = variable(part.name)
                    if (part.quoted) {
                        quoted = true
                        add(value)
                    } else {
                        addSplit(value)
                    }
                }
                is Sub -> {
                    val sink = StringBuilder()
                    status = exec(part.cmd, "", sink, err, user)
                    val value = sink.toString().trimEnd('\n')
                    if (part.quoted) {
                        quoted = true
                        add(value)
                    } else {
                        addSplit(value)
                    }
                }
                is Arith -> add(runCatching { Arithmetic(part.expr) { variable(it) }.eval().toString() }.getOrElse { "0" })
            }
        }
        val result = fields.map { it.toString() }.ifEmpty { if (quoted) listOf("") else emptyList() }
        if (glob && split && result.size == 1) {
            val matches = glob(result[0])
            if (matches.isNotEmpty()) return matches
        }
        return result
    }

    private fun variable(spec: String): String {
        val defaultAt = spec.indexOf(":-")
        if (defaultAt > 0) return variable(spec.take(defaultAt)).ifEmpty { spec.drop(defaultAt + 2) }
        if (spec.startsWith("#")) return variable(spec.drop(1)).length.toString()
        return when (spec) {
            "?" -> status.toString()
            "$" -> "1204"
            "PWD" -> cwd
            "RANDOM" -> (0..32767).random().toString()
            else -> vars[spec].orEmpty()
        }
    }

    private fun glob(pattern: String): List<String> {
        val absolute = pattern.startsWith("/")
        val segments = pattern.split('/').filter { it.isNotEmpty() }
        var paths = listOf(if (absolute) "/" else cwd)
        for (seg in segments) {
            paths = if (seg.none { it == '*' || it == '?' || it == '[' }) {
                paths.map { join(it, seg) }.filter { machine.file(it) != null }
            } else {
                val regex = globRegex(seg)
                paths.flatMap { dir ->
                    children(dir).filter { (seg.startsWith(".") || !it.startsWith(".")) && regex.matches(it) }.map { join(dir, it) }
                }
            }
        }
        return paths.sorted().map { if (absolute) it else it.removePrefix(if (cwd == "/") "/" else "$cwd/") }
    }

    // ---- Files ----

    internal fun abs(path: String): String {
        val raw = if (path.startsWith("/")) path else "$cwd/$path"
        val parts = ArrayDeque<String>()
        raw.split('/').forEach { p ->
            when (p) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(p)
            }
        }
        return "/" + parts.joinToString("/")
    }

    internal fun join(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

    internal fun children(dir: String): List<String> {
        val prefix = if (dir == "/") "/" else "$dir/"
        return machine.files.subMap(prefix, true, prefix + '￿', true).keys
            .filter { it != prefix && it.length > prefix.length && '/' !in it.substring(prefix.length) }
            .map { it.substring(prefix.length) }
    }

    private fun bits(node: FsNode, user: String): Int = if (user == node.owner) (node.mode shr 6) and 7 else node.mode and 7

    internal fun canRead(node: FsNode, user: String) = user == "root" || bits(node, user) and 4 != 0
    internal fun canWrite(node: FsNode, user: String) = user == "root" || bits(node, user) and 2 != 0
    internal fun canExec(node: FsNode, user: String) = if (user == "root") node.dir || node.mode and 0b001_001_001 != 0 else bits(node, user) and 1 != 0

    /** Whether every directory on the way to [path] can be passed through. */
    internal fun reachable(path: String, user: String): Boolean {
        var dir = path.substringBeforeLast('/').ifEmpty { "/" }
        while (true) {
            val node = machine.file(dir) ?: return false
            if (!canExec(node, user)) return false
            if (dir == "/") return true
            dir = dir.substringBeforeLast('/').ifEmpty { "/" }
        }
    }

    internal fun readFile(path: String, user: String): Result<String> {
        val p = abs(path)
        val node = machine.file(p)
        return when {
            node == null && !reachable(p, user) && machine.file(p.substringBeforeLast('/').ifEmpty { "/" }) != null ->
                Result.failure(Problem("Permission denied"))
            node == null -> Result.failure(Problem("No such file or directory"))
            node.dir -> Result.failure(Problem("Is a directory"))
            !reachable(p, user) || !canRead(node, user) -> Result.failure(Problem("Permission denied"))
            else -> Result.success(Commands.dynamic(this, p) ?: node.content)
        }
    }

    /** Why [path] can't be written by [user], or null if it can. */
    internal fun writeProblem(path: String, user: String): String? {
        val p = abs(path)
        val node = machine.file(p)
        val parent = machine.file(p.substringBeforeLast('/').ifEmpty { "/" })
        return when {
            p.startsWith("/proc") || p.startsWith("/sys") -> "Permission denied"
            node?.dir == true -> "Is a directory"
            parent == null || !parent.dir -> "No such file or directory"
            !reachable(p, user) -> "Permission denied"
            node != null && !canWrite(node, user) -> "Permission denied"
            node == null && !canWrite(parent, user) -> "Permission denied"
            else -> null
        }
    }

    internal fun writeFile(path: String, content: String, append: Boolean, user: String): String? {
        writeProblem(path, user)?.let { return it }
        val p = abs(path)
        val node = machine.file(p)
        val next = (if (append && node != null) node.content else "") + content
        if (next.length > FILE_LIMIT) return "No space left on device"
        if (node == null) {
            machine.files[p] = FsNode(dir = false, content = next, owner = user, mtime = machine.clock())
        } else {
            node.content = next
            node.size = null
            node.mtime = machine.clock()
        }
        return null
    }

    internal class Problem(message: String) : Exception(message)
    internal class ExitShell(val code: Int) : RuntimeException()
    private class TooLong : RuntimeException()

    companion object {
        const val HOME = "/home/bob"
        const val PATH = "/usr/local/bin:/usr/bin:/bin"
        private const val OUTPUT_LIMIT = 6000
        private const val FILE_LIMIT = 200_000
        private const val MAX_STEPS = 20_000

        internal fun globRegex(glob: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                when (val c = glob[i]) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    '[' -> {
                        val end = glob.indexOf(']', i + 1)
                        if (end < 0) sb.append("\\[") else {
                            sb.append('[').append(glob.substring(i + 1, end).replace("!", "^").replace("\\", "\\\\")).append(']')
                            i = end
                        }
                    }
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(sb.toString())
        }
    }
}

// ---- Syntax tree ----

internal sealed interface Cmd
internal class Seq(val items: List<Cmd>, val ops: List<String>) : Cmd
internal class Pipe(val cmds: List<Cmd>, val negate: Boolean) : Cmd
internal class Simple(val assigns: List<Pair<String, Word>>, val words: List<Word>, val redirs: List<Redir>) : Cmd
internal class For(val name: String, val items: List<Word>, val body: Cmd) : Cmd
internal class If(val cond: Cmd, val then: Cmd, val orElse: Cmd?) : Cmd
internal class While(val cond: Cmd, val body: Cmd, val until: Boolean) : Cmd
internal class Group(val body: Cmd, val subshell: Boolean) : Cmd
internal class Redir(val fd: Int, val op: String, val target: Word?) {
    var heredoc: String? = null
}

internal sealed interface Part
internal class Lit(val text: String, val quoted: Boolean) : Part
internal class Var(val name: String, val quoted: Boolean) : Part
internal class Sub(val cmd: Cmd, val quoted: Boolean) : Part
internal class Arith(val expr: String) : Part
internal class Word(val parts: List<Part>) {
    /** The word's text with quotes removed, for heredoc delimiters and keywords. */
    val literal: String get() = parts.joinToString("") { if (it is Lit) it.text else "" }
    val bare: Boolean get() = parts.all { it is Lit && !it.quoted }
}

internal class SyntaxError(val token: String) : RuntimeException(token)

private class Parser(private val s: String) {
    private var i = 0
    private val pending = mutableListOf<Triple<Redir, String, Boolean>>()

    fun parseAll(): Cmd {
        val c = list(emptySet())
        skipBlank(newlines = true)
        if (i < s.length) throw SyntaxError(s.substring(i).take(2).trim())
        return c
    }

    private fun peek(off: Int = 0): Char? = s.getOrNull(i + off)

    private fun skipBlank(newlines: Boolean = false) {
        while (i < s.length) {
            val c = s[i]
            when {
                c == ' ' || c == '\t' || c == '\r' -> i++
                c == '\\' && peek(1) == '\n' -> i += 2
                c == '#' -> while (i < s.length && s[i] != '\n') i++
                c == '\n' && newlines -> newline()
                else -> return
            }
        }
    }

    /** Consumes a newline and reads any heredoc bodies that start after it. */
    private fun newline() {
        i++
        for ((redir, delim, strip) in pending) {
            val body = StringBuilder()
            while (i < s.length) {
                val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                var line = s.substring(i, end)
                i = minOf(end + 1, s.length)
                if (strip) line = line.trimStart('\t')
                if (line == delim) break
                body.append(line).append('\n')
            }
            redir.heredoc = body.toString()
        }
        pending.clear()
    }

    /** The keyword at the next word, if it is one, without consuming it. */
    private fun keyword(): String? {
        val save = i
        skipBlank()
        val start = i
        while (i < s.length && (s[i].isLetter() || s[i] == '{' || s[i] == '}' || s[i] == '!')) i++
        val w = s.substring(start, i)
        val delimited = i >= s.length || s[i] in " \t\n;&|()"
        i = save
        return w.takeIf { delimited && it in KEYWORDS }
    }

    private fun expect(word: String) {
        skipBlank(newlines = true)
        if (s.startsWith(word, i) && (i + word.length >= s.length || s[i + word.length] in " \t\n;&|)")) {
            i += word.length
        } else {
            throw SyntaxError(s.substring(i).takeWhile { !it.isWhitespace() }.ifEmpty { "newline" })
        }
    }

    private fun list(stop: Set<String>): Cmd {
        val items = mutableListOf<Cmd>()
        val ops = mutableListOf<String>()
        while (true) {
            skipBlank(newlines = true)
            if (i >= s.length || peek() == ')' || keyword()?.let { it in stop } == true) break
            items += pipeline()
            skipBlank()
            when {
                s.startsWith("&&", i) -> { ops += "&&"; i += 2 }
                s.startsWith("||", i) -> { ops += "||"; i += 2 }
                s.startsWith(";;", i) -> throw SyntaxError(";;")
                peek() == ';' || peek() == '&' -> { ops += ";"; i++ }
                peek() == '\n' -> { ops += ";"; newline() }
                else -> break
            }
        }
        while (ops.size >= items.size && ops.isNotEmpty()) ops.removeAt(ops.lastIndex)
        if (items.isEmpty() && stop.isNotEmpty()) throw SyntaxError(keyword() ?: "newline")
        return if (items.size == 1) items[0] else Seq(items, ops)
    }

    private fun pipeline(): Cmd {
        skipBlank()
        var negate = false
        if (peek() == '!' && peek(1)?.isWhitespace() == true) {
            negate = true
            i++
        }
        val cmds = mutableListOf(command())
        while (true) {
            skipBlank()
            if (peek() == '|' && peek(1) != '|') {
                i += if (peek(1) == '&') 2 else 1
                skipBlank(newlines = true)
                cmds += command()
            } else {
                break
            }
        }
        return if (cmds.size == 1 && !negate) cmds[0] else Pipe(cmds, negate)
    }

    private fun command(): Cmd {
        skipBlank()
        when (keyword()) {
            "for" -> return parseFor()
            "if" -> { expect("if"); return parseIf() }
            "while" -> { expect("while"); return parseWhile(false) }
            "until" -> { expect("until"); return parseWhile(true) }
            "{" -> {
                expect("{")
                val body = list(setOf("}"))
                expect("}")
                return Group(body, subshell = false)
            }
        }
        if (peek() == '(') {
            i++
            val body = list(emptySet())
            skipBlank(newlines = true)
            if (peek() != ')') throw SyntaxError("newline")
            i++
            return Group(body, subshell = true)
        }
        return simple()
    }

    private fun parseFor(): Cmd {
        expect("for")
        skipBlank()
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        val name = s.substring(start, i)
        if (name.isEmpty()) throw SyntaxError(s.substring(i).take(2))
        skipBlank()
        val items = mutableListOf<Word>()
        if (keyword() == "in") {
            expect("in")
            while (true) {
                skipBlank()
                if (i >= s.length || peek() == ';' || peek() == '\n') break
                items += word()
            }
        }
        skipBlank()
        if (peek() == ';') i++
        expect("do")
        val body = list(setOf("done"))
        expect("done")
        return For(name, items, body)
    }

    private fun parseIf(): Cmd {
        val cond = list(setOf("then"))
        expect("then")
        val then = list(setOf("elif", "else", "fi"))
        return when (keyword()) {
            "elif" -> { expect("elif"); If(cond, then, parseIf()) }
            "else" -> {
                expect("else")
                val orElse = list(setOf("fi"))
                expect("fi")
                If(cond, then, orElse)
            }
            else -> { expect("fi"); If(cond, then, null) }
        }
    }

    private fun parseWhile(until: Boolean): Cmd {
        val cond = list(setOf("do"))
        expect("do")
        val body = list(setOf("done"))
        expect("done")
        return While(cond, body, until)
    }

    private fun simple(): Cmd {
        val assigns = mutableListOf<Pair<String, Word>>()
        val words = mutableListOf<Word>()
        val redirs = mutableListOf<Redir>()
        while (true) {
            skipBlank()
            val c = peek() ?: break
            if (s.startsWith("&>", i)) {
                i += if (s.startsWith("&>>", i)) 3 else 2
                skipBlank()
                redirs += Redir(1, "&>", word())
                continue
            }
            if (c in "\n;&|)") break
            if (c == '(') throw SyntaxError("(")
            var fd = -1
            if (c.isDigit() && peek(1)?.let { it == '<' || it == '>' } == true) {
                fd = c - '0'
                i++
            }
            when {
                s.startsWith(">&", i) -> {
                    i += 2
                    skipBlank()
                    redirs += Redir(if (fd < 0) 1 else fd, ">&", word())
                }
                s.startsWith(">>", i) -> { i += 2; skipBlank(); redirs += Redir(if (fd < 0) 1 else fd, ">>", word()) }
                s.startsWith(">|", i) || peek() == '>' -> {
                    i += if (s.startsWith(">|", i)) 2 else 1
                    skipBlank()
                    redirs += Redir(if (fd < 0) 1 else fd, ">", word())
                }
                s.startsWith("<<<", i) -> { i += 3; skipBlank(); redirs += Redir(0, "<<<", word()) }
                s.startsWith("<<", i) -> {
                    i += 2
                    val strip = peek() == '-'
                    if (strip) i++
                    skipBlank()
                    val delim = word()
                    val r = Redir(0, "<<", null)
                    pending += Triple(r, delim.literal, strip)
                    redirs += r
                }
                peek() == '<' -> { i++; skipBlank(); redirs += Redir(0, "<", word()) }
                else -> {
                    val w = word()
                    val first = w.parts.firstOrNull()
                    val m = if (words.isEmpty() && first is Lit && !first.quoted) ASSIGN.find(first.text) else null
                    if (m != null) {
                        val rest = listOf<Part>(Lit(first!!.let { (it as Lit).text.substring(m.value.length) }, false)) + w.parts.drop(1)
                        assigns += m.groupValues[1] to Word(rest.filter { it !is Lit || it.text.isNotEmpty() || it.quoted })
                    } else {
                        words += w
                    }
                }
            }
        }
        if (words.isEmpty() && assigns.isEmpty() && redirs.isEmpty()) throw SyntaxError(peek()?.toString() ?: "newline")
        return Simple(assigns, words, redirs)
    }

    private fun word(): Word {
        val parts = mutableListOf<Part>()
        val lit = StringBuilder()
        fun flush() {
            if (lit.isNotEmpty()) {
                parts += Lit(lit.toString(), false)
                lit.clear()
            }
        }
        while (i < s.length) {
            val c = s[i]
            if (c in " \t\n;&|<>()\r") break
            when (c) {
                '\\' -> {
                    i++
                    if (peek() == '\n') {
                        i++
                    } else if (i < s.length) {
                        flush()
                        parts += Lit(s[i].toString(), true)
                        i++
                    }
                }
                '\'' -> {
                    flush()
                    val end = s.indexOf('\'', i + 1)
                    if (end < 0) throw SyntaxError("'")
                    parts += Lit(s.substring(i + 1, end), true)
                    i = end + 1
                }
                '"' -> {
                    flush()
                    i++
                    val text = StringBuilder()
                    var any = false
                    while (true) {
                        val d = peek() ?: throw SyntaxError("\"")
                        when {
                            d == '"' -> { i++; break }
                            d == '\\' && peek(1)?.let { it in "$`\"\\\n" } == true -> { text.append(s[i + 1]); i += 2 }
                            d == '$' && peek(1)?.let { it == '(' || it == '{' || it.isLetter() || it == '_' || it in "?$#@*0123456789" } == true -> {
                                if (text.isNotEmpty() || !any) parts += Lit(text.toString(), true)
                                text.clear()
                                parts += dollar(quoted = true)
                                any = true
                            }
                            d == '`' -> {
                                if (text.isNotEmpty()) parts += Lit(text.toString(), true)
                                text.clear()
                                parts += backtick(quoted = true)
                                any = true
                            }
                            else -> { text.append(d); i++ }
                        }
                    }
                    if (text.isNotEmpty() || !any) parts += Lit(text.toString(), true)
                }
                '$' -> {
                    if (peek(1)?.let { it == '(' || it == '{' || it.isLetter() || it == '_' || it in "?$#@*0123456789" } == true) {
                        flush()
                        parts += dollar(quoted = false)
                    } else {
                        lit.append(c)
                        i++
                    }
                }
                '`' -> {
                    flush()
                    parts += backtick(quoted = false)
                }
                else -> {
                    lit.append(c)
                    i++
                }
            }
        }
        flush()
        if (parts.isEmpty()) throw SyntaxError(peek()?.toString() ?: "newline")
        return Word(parts)
    }

    private fun dollar(quoted: Boolean): Part {
        i++ // $
        when {
            s.startsWith("((", i) -> {
                val end = s.indexOf("))", i + 2)
                if (end < 0) throw SyntaxError("((")
                val expr = s.substring(i + 2, end)
                i = end + 2
                return Arith(expr)
            }
            peek() == '(' -> {
                i++
                val cmd = list(emptySet())
                skipBlank(newlines = true)
                if (peek() != ')') throw SyntaxError("newline")
                i++
                return Sub(cmd, quoted)
            }
            peek() == '{' -> {
                val end = s.indexOf('}', i)
                if (end < 0) throw SyntaxError("{")
                val name = s.substring(i + 1, end)
                i = end + 1
                return Var(name, quoted)
            }
            peek()?.let { it in "?$#@*0123456789" } == true -> return Var(s[i++].toString(), quoted)
            else -> {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                return Var(s.substring(start, i), quoted)
            }
        }
    }

    private fun backtick(quoted: Boolean): Part {
        val end = s.indexOf('`', i + 1)
        if (end < 0) throw SyntaxError("`")
        val inner = s.substring(i + 1, end)
        i = end + 1
        return Sub(Parser(inner).parseAll(), quoted)
    }

    private companion object {
        val KEYWORDS = setOf("if", "then", "elif", "else", "fi", "for", "in", "do", "done", "while", "until", "{", "}")
        val ASSIGN = Regex("^([A-Za-z_][A-Za-z0-9_]*)=")
    }
}

/** Integer arithmetic for `$((…))`. */
internal class Arithmetic(private val s: String, private val lookup: (String) -> String) {
    private var i = 0

    fun eval(): Long = compare().also { skip(); if (i < s.length) error("trailing") }

    private fun skip() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun compare(): Long {
        var v = sum()
        while (true) {
            skip()
            val op = listOf("==", "!=", "<=", ">=", "<", ">").firstOrNull { s.startsWith(it, i) } ?: return v
            i += op.length
            val r = sum()
            v = if (when (op) { "==" -> v == r; "!=" -> v != r; "<=" -> v <= r; ">=" -> v >= r; "<" -> v < r; else -> v > r }) 1 else 0
        }
    }

    private fun sum(): Long {
        var v = product()
        while (true) {
            skip()
            when (s.getOrNull(i)) {
                '+' -> { i++; v += product() }
                '-' -> { i++; v -= product() }
                else -> return v
            }
        }
    }

    private fun product(): Long {
        var v = unary()
        while (true) {
            skip()
            when (s.getOrNull(i)) {
                '*' -> { i++; v *= unary() }
                '/' -> { i++; v /= unary() }
                '%' -> { i++; v %= unary() }
                else -> return v
            }
        }
    }

    private fun unary(): Long {
        skip()
        return when (s.getOrNull(i)) {
            '-' -> { i++; -unary() }
            '(' -> { i++; compare().also { skip(); i++ } }
            '$' -> { i++; unary() }
            else -> {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val token = s.substring(start, i)
                token.toLongOrNull() ?: lookup(token).trim().toLongOrNull() ?: 0
            }
        }
    }
}
