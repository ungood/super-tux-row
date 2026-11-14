package name.onetrue.supertuxrower.bob

import kotlin.math.roundToLong

private typealias Command = (Shell.Ctx) -> Int

/**
 * The programs on Bob's machine. Their output follows Debian 12's closely enough to be believed;
 * what isn't installed says so. The ones that matter to the way out are sudo, openssl, ip and
 * uplink; the rest are there so the machine feels like a machine.
 */
internal object Commands {
    /** Programs that only exist while their file in /usr/local/bin does. */
    fun local(name: String) = name == "uplink" || name == "powerctl"

    /** Files whose content is made when they're read. */
    fun dynamic(sh: Shell, path: String): String? {
        val m = sh.machine
        val watts = m.power()
        return when (path) {
            "/proc/uptime" -> "%.2f %.2f\n".format(m.uptime, m.uptime * 0.2)
            "/proc/loadavg" -> "1.02 0.98 0.91 2/131 1290\n"
            "/proc/version" -> "Linux version 6.1.0-rpi7-rpi-v8 (debian-kernel@lists.debian.org) (gcc-12) #1 SMP PREEMPT Debian 1:6.1.63-1+rpt1\n"
            "/proc/meminfo" -> "MemTotal:        3884856 kB\nMemFree:          112340 kB\nMemAvailable:     287512 kB\nSwapTotal:        102396 kB\nSwapFree:              0 kB\n"
            "/proc/cpuinfo" -> (0..3).joinToString("") { "processor\t: $it\nBogoMIPS\t: 108.00\nCPU part\t: 0xd08\n\n" } + "Model\t\t: Raspberry Pi 4 Model B Rev 1.5\n"
            "/sys/class/power_supply/rower/power_now" -> "${(watts * 1_000_000).roundToLong()}\n"
            "/sys/class/power_supply/rower/voltage_now" -> "${if (watts > 5) 5_080_000 else 3_120_000}\n"
            "/sys/class/power_supply/rower/status" -> if (watts > 5) "Charging\n" else "Discharging\n"
            "/sys/class/power_supply/rower/type" -> "Mains\n"
            else -> null
        }
    }

    // ---- Helpers ----

    /** Splits arguments into single-letter flags and operands; "--" ends flags. */
    private fun flags(args: List<String>, withValue: Set<Char> = emptySet()): Pair<Map<Char, String>, List<String>> {
        val flags = mutableMapOf<Char, String>()
        val rest = mutableListOf<String>()
        var i = 0
        var done = false
        while (i < args.size) {
            val a = args[i]
            if (!done && a == "--") {
                done = true
            } else if (!done && a.startsWith("-") && a.length > 1 && !a[1].isDigit()) {
                var j = 1
                while (j < a.length) {
                    val f = a[j]
                    if (f in withValue) {
                        flags[f] = if (j + 1 < a.length) a.substring(j + 1) else args.getOrElse(++i) { "" }
                        break
                    }
                    flags[f] = ""
                    j++
                }
            } else {
                rest += a
            }
            i++
        }
        return flags to rest
    }

    private fun Shell.Ctx.read(path: String): String? {
        if (path == "-") return stdin
        val r = shell.readFile(path, user)
        return r.getOrElse {
            err.append("$name: $path: ${it.message}\n")
            null
        }
    }

    /** The text of each operand, or stdin if there are none. */
    private fun Shell.Ctx.inputs(files: List<String>): List<Pair<String, String>>? {
        if (files.isEmpty()) return listOf("-" to stdin)
        var ok = true
        val found = files.mapNotNull { f -> read(f)?.let { f to it } ?: run { ok = false; null } }
        return found.takeIf { ok || it.isNotEmpty() }
    }

    private fun lines(text: String) = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1fG".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1fM".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1fK".format(bytes / 1024.0)
        else -> bytes.toString()
    }

    private fun perms(node: FsNode, path: String): String {
        val m = node.mode
        val bits = "rwxrwxrwx".mapIndexed { i, c -> if (m and (1 shl (8 - i)) != 0) c else '-' }.joinToString("")
        val sticky = if (path == "/tmp" || path == "/var/tmp") bits.dropLast(1) + "t" else bits
        return (if (node.dir) "d" else "-") + sticky
    }

    private fun clockTime(sh: Shell) = World.timestamp(sh.machine.clock()).substring(11, 16)

    private fun stamp(millis: Long): String =
        java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US).format(java.util.Date(millis)).let {
            val parts = it.split(' ')
            "%s %2s %s".format(parts[0], parts[1], parts[2])
        }

    private fun regex(pattern: String, extended: Boolean, ignoreCase: Boolean, fixed: Boolean, word: Boolean): Regex? {
        var p = if (fixed) Regex.escape(pattern) else if (extended) pattern else basic(pattern)
        if (word) p = "\\b(?:$p)\\b"
        return runCatching { if (ignoreCase) Regex(p, RegexOption.IGNORE_CASE) else Regex(p) }.getOrNull()
    }

    /** POSIX basic regular expressions: \| \( \) \{ \} \+ \? are special, the bare ones literal. */
    private fun basic(p: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '\\' && i + 1 < p.length && p[i + 1] in "|(){}+?") {
                sb.append(p[i + 1])
                i += 2
                continue
            }
            if (c in "|(){}+?") sb.append('\\')
            sb.append(c)
            if (c == '\\' && i + 1 < p.length) sb.append(p[++i])
            i++
        }
        return sb.toString()
    }

    // ---- Shell builtins ----

    val builtins: Map<String, Command> = mapOf(
        "cd" to { c ->
            val target = when (val a = c.args.firstOrNull()) {
                null, "~" -> c.env["HOME"] ?: Shell.HOME
                "-" -> c.env["OLDPWD"] ?: c.shell.cwd
                else -> a
            }
            val p = c.shell.abs(target)
            val node = c.shell.machine.file(p)
            when {
                node == null -> c.fail("bash: cd: $target: No such file or directory")
                !node.dir -> c.fail("bash: cd: $target: Not a directory")
                !c.shell.reachable(p, c.user) || !c.shell.canExec(node, c.user) -> c.fail("bash: cd: $target: Permission denied")
                else -> {
                    c.shell.vars["OLDPWD"] = c.shell.cwd
                    c.shell.cwd = p
                    0
                }
            }
        },
        "pwd" to { c -> c.println(c.shell.cwd); 0 },
        "export" to { c ->
            c.args.filter { it != "-n" && it != "-p" }.forEach { a ->
                val eq = a.indexOf('=')
                if (eq > 0) c.shell.vars[a.take(eq)] = a.substring(eq + 1)
            }
            if (c.args.isEmpty()) c.shell.vars.toSortedMap().forEach { (k, v) -> c.println("declare -x $k=\"$v\"") }
            0
        },
        "unset" to { c -> c.args.forEach { c.shell.vars.remove(it) }; 0 },
        "set" to { c ->
            if (c.args.isEmpty()) c.shell.vars.toSortedMap().forEach { (k, v) -> c.println("$k=$v") }
            0
        },
        "alias" to { 0 },
        "unalias" to { 0 },
        "shopt" to { 0 },
        "true" to { 0 },
        ":" to { 0 },
        "false" to { 1 },
        "exit" to { c -> throw Shell.ExitShell(c.args.firstOrNull()?.toIntOrNull() ?: 0) },
        "logout" to { c -> throw Shell.ExitShell(0) },
        "source" to { c -> source(c) },
        "." to { c -> source(c) },
        "eval" to { c -> c.shell.source(c.args.joinToString(" "), c.stdin, c.out, c.err, c.user) },
        "history" to { c ->
            c.shell.history.forEachIndexed { i, h -> c.println("%5d  %s".format(i + 1, h.lineSequence().first())) }
            0
        },
        "read" to { c ->
            val names = c.args.filter { !it.startsWith("-") }.ifEmpty { listOf("REPLY") }
            val line = c.stdin.lineSequence().firstOrNull()
            if (line == null) {
                1
            } else {
                val parts = line.trim().split(Regex("\\s+"), names.size)
                names.forEachIndexed { i, n -> c.shell.vars[n] = parts.getOrElse(i) { "" } }
                0
            }
        },
        "type" to { c -> which(c, typeStyle = true) },
        "command" to { c ->
            if (c.args.firstOrNull() == "-v") which(c.copy(args = c.args.drop(1)), typeStyle = false)
            else c.shell.runCommand(c.args, emptyMap(), c.stdin, c.out, c.err, c.user)
        },
        "test" to { c -> test(c, c.args) },
        "[" to { c -> if (c.args.lastOrNull() != "]") c.fail("bash: [: missing `]'", 2) else test(c, c.args.dropLast(1)) },
        "[[" to { c -> test(c, c.args.dropLast(1)) },
        "echo" to { c ->
            var args = c.args
            var newline = true
            var escapes = false
            while (args.firstOrNull()?.matches(Regex("-[neE]+")) == true) {
                if ('n' in args[0]) newline = false
                if ('e' in args[0]) escapes = true
                args = args.drop(1)
            }
            var text = args.joinToString(" ")
            if (escapes) text = text.replace("\\n", "\n").replace("\\t", "\t")
            c.print(if (newline) text + "\n" else text)
            0
        },
        "printf" to { c -> printf(c) },
        "sleep" to { 0 },
        "wait" to { 0 },
        "clear" to { 0 },
        "reset" to { 0 },
    )

    private fun Shell.Ctx.copy(args: List<String>) = Shell.Ctx(shell, name, args, stdin, out, err, user, env)

    private fun source(c: Shell.Ctx): Int {
        val file = c.args.firstOrNull() ?: return c.fail("bash: source: filename argument required", 2)
        val text = c.shell.readFile(file, c.user).getOrElse { return c.fail("bash: $file: ${it.message}") }
        return c.shell.source(text, c.stdin, c.out, c.err, c.user)
    }

    private fun which(c: Shell.Ctx, typeStyle: Boolean): Int {
        var st = 0
        c.args.filter { !it.startsWith("-") }.forEach { n ->
            when {
                n in builtins -> c.println(if (typeStyle) "$n is a shell builtin" else n)
                n in programs && (!local(n) || c.shell.machine.file("/usr/local/bin/$n") != null) -> {
                    val path = if (local(n)) "/usr/local/bin/$n" else if (n in sbin) "/usr/sbin/$n" else "/usr/bin/$n"
                    c.println(if (typeStyle) "$n is $path" else path)
                }
                else -> {
                    if (typeStyle) c.err.append("bash: type: $n: not found\n")
                    st = 1
                }
            }
        }
        return st
    }

    private fun test(c: Shell.Ctx, a: List<String>): Int {
        if (a.firstOrNull() == "!") return if (test(c, a.drop(1)) == 0) 1 else 0
        val ok = when (a.size) {
            0 -> false
            1 -> a[0].isNotEmpty()
            2 -> when (a[0]) {
                "-z" -> a[1].isEmpty()
                "-n" -> a[1].isNotEmpty()
                else -> fileTest(c.shell, c.user)(a[0], a[1])
            }
            3 -> when (a[1]) {
                "=", "==" -> a[0] == a[2]
                "!=" -> a[0] != a[2]
                "-eq" -> a[0].toLongOrNull() == a[2].toLongOrNull()
                "-ne" -> a[0].toLongOrNull() != a[2].toLongOrNull()
                "-lt" -> (a[0].toLongOrNull() ?: 0) < (a[2].toLongOrNull() ?: 0)
                "-le" -> (a[0].toLongOrNull() ?: 0) <= (a[2].toLongOrNull() ?: 0)
                "-gt" -> (a[0].toLongOrNull() ?: 0) > (a[2].toLongOrNull() ?: 0)
                "-ge" -> (a[0].toLongOrNull() ?: 0) >= (a[2].toLongOrNull() ?: 0)
                else -> false
            }
            else -> false
        }
        return if (ok) 0 else 1
    }

    private fun printf(c: Shell.Ctx): Int {
        val format = c.args.firstOrNull() ?: return c.fail("printf: usage: printf format [arguments]", 2)
        val args = ArrayDeque(c.args.drop(1))
        do {
            val sb = StringBuilder()
            var i = 0
            while (i < format.length) {
                val ch = format[i]
                when {
                    ch == '\\' && i + 1 < format.length -> {
                        sb.append(when (format[i + 1]) { 'n' -> '\n'; 't' -> '\t'; '\\' -> '\\'; else -> format[i + 1] })
                        i += 2
                    }
                    ch == '%' && i + 1 < format.length -> {
                        var j = i + 1
                        while (j < format.length && format[j] in "-0123456789.") j++
                        val spec = format.substring(i, j + 1)
                        val v = args.removeFirstOrNull().orEmpty()
                        sb.append(
                            when (format.getOrNull(j)) {
                                's', 'b' -> String.format(spec.dropLast(1) + "s", v)
                                'd', 'i' -> String.format(spec.dropLast(1) + "d", v.toLongOrNull() ?: 0)
                                'x' -> String.format(spec, v.toLongOrNull() ?: 0)
                                'f' -> String.format(spec, v.toDoubleOrNull() ?: 0.0)
                                '%' -> "%"
                                else -> spec
                            }
                        )
                        i = j + 1
                    }
                    else -> { sb.append(ch); i++ }
                }
            }
            c.print(sb.toString())
        } while (args.isNotEmpty())
        return 0
    }

    // ---- Programs ----

    private val sbin = setOf("ip", "reboot", "shutdown", "poweroff", "ifup", "ifdown", "iw", "wpa_cli")

    val programs: Map<String, Command> = mapOf(
        "ls" to ::ls,
        "cat" to { c ->
            val (f, files) = flags(c.args)
            val texts = c.inputs(files)
            texts?.forEach { (_, t) ->
                if (f.containsKey('n')) lines(t).forEachIndexed { i, l -> c.println("%6d\t%s".format(i + 1, l)) } else c.print(t)
            }
            if (texts == null || texts.size < files.size) 1 else 0
        },
        "less" to { c -> c.inputs(c.args.filter { !it.startsWith("-") })?.forEach { c.print(it.second) }; 0 },
        "more" to { c -> c.inputs(c.args)?.forEach { c.print(it.second) }; 0 },
        "head" to { c -> headTail(c, head = true) },
        "tail" to { c -> headTail(c, head = false) },
        "grep" to ::grep,
        "egrep" to { c -> grep(c.copy(args = listOf("-E") + c.args)) },
        "find" to ::find,
        "wc" to { c ->
            val (f, files) = flags(c.args)
            val all = f.isEmpty()
            c.inputs(files)?.forEach { (name, t) ->
                val cols = mutableListOf<Int>()
                if (all || 'l' in f) cols += t.count { it == '\n' }
                if (all || 'w' in f) cols += t.split(Regex("\\s+")).count { it.isNotEmpty() }
                if (all || 'c' in f || 'm' in f) cols += t.toByteArray().size
                c.println(cols.joinToString(" ") { if (files.isEmpty() && cols.size == 1) "$it" else "%7d".format(it) } + if (name == "-") "" else " $name")
            }
            0
        },
        "sort" to { c ->
            val (f, files) = flags(c.args, setOf('k', 't'))
            var ls = c.inputs(files)?.flatMap { lines(it.second) } ?: return@to 2
            ls = if ('n' in f) ls.sortedBy { it.trim().takeWhile { ch -> ch.isDigit() || ch == '.' || ch == '-' }.toDoubleOrNull() ?: 0.0 } else ls.sorted()
            if ('r' in f) ls = ls.reversed()
            if ('u' in f) ls = ls.distinct()
            ls.forEach { c.println(it) }
            0
        },
        "uniq" to { c ->
            val (f, files) = flags(c.args)
            val ls = c.inputs(files)?.flatMap { lines(it.second) } ?: return@to 1
            val groups = mutableListOf<Pair<String, Int>>()
            ls.forEach { l -> if (groups.lastOrNull()?.first == l) groups[groups.lastIndex] = l to groups.last().second + 1 else groups += l to 1 }
            groups.forEach { (l, n) -> c.println(if ('c' in f) "%7d %s".format(n, l) else l) }
            0
        },
        "cut" to { c ->
            val (f, files) = flags(c.args, setOf('d', 'f', 'c'))
            val delim = f['d']?.firstOrNull() ?: '\t'
            fun ranges(spec: String, size: Int): List<Int> = spec.split(',').flatMap { r ->
                val a = r.substringBefore('-').toIntOrNull() ?: 1
                val b = if ('-' in r) r.substringAfter('-').toIntOrNull() ?: size else a
                (a..minOf(b, size)).toList()
            }
            c.inputs(files)?.forEach { (_, t) ->
                lines(t).forEach { l ->
                    when {
                        f['c'] != null -> c.println(ranges(f['c']!!, l.length).map { l[it - 1] }.joinToString(""))
                        f['f'] != null -> {
                            val parts = l.split(delim)
                            c.println(if (parts.size == 1) l else ranges(f['f']!!, parts.size).joinToString(delim.toString()) { parts[it - 1] })
                        }
                        else -> c.println(l)
                    }
                }
            } ?: return@to 1
            0
        },
        "tr" to { c ->
            val (f, sets) = flags(c.args)
            fun set(s: String): String = Regex("(.)-(.)").replace(s.replace("[:lower:]", "a-z").replace("[:upper:]", "A-Z").replace("\\n", "\n")) { m ->
                (m.groupValues[1][0]..m.groupValues[2][0]).joinToString("")
            }
            val from = set(sets.getOrElse(0) { "" })
            val to = set(sets.getOrElse(1) { "" })
            val out = if ('d' in f) c.stdin.filter { it !in from } else c.stdin.map { ch ->
                val k = from.indexOf(ch)
                if (k < 0 || to.isEmpty()) ch else to[minOf(k, to.length - 1)]
            }.joinToString("")
            c.print(out)
            0
        },
        "rev" to { c -> c.inputs(c.args)?.forEach { (_, t) -> lines(t).forEach { c.println(it.reversed()) } }; 0 },
        "tee" to { c ->
            val (f, files) = flags(c.args)
            c.print(c.stdin)
            var st = 0
            files.forEach { p -> c.shell.writeFile(p, c.stdin, 'a' in f, c.user)?.let { st = c.fail("tee: $p: $it") } }
            st
        },
        "xargs" to { c ->
            val (f, cmd) = flags(c.args, setOf('I', 'n'))
            val command = cmd.ifEmpty { listOf("echo") }
            val items = c.stdin.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val replace = f['I']
            var st = 0
            when {
                replace != null -> lines(c.stdin).forEach { line ->
                    st = c.shell.runCommand(command.map { it.replace(replace, line) }, emptyMap(), "", c.out, c.err, c.user)
                }
                f['n'] != null -> items.chunked(f['n']!!.toIntOrNull() ?: 1).forEach {
                    st = c.shell.runCommand(command + it, emptyMap(), "", c.out, c.err, c.user)
                }
                else -> st = c.shell.runCommand(command + items, emptyMap(), "", c.out, c.err, c.user)
            }
            st
        },
        "seq" to { c ->
            val n = c.args.mapNotNull { it.toLongOrNull() }
            val (a, step, b) = when (n.size) { 1 -> Triple(1L, 1L, n[0]); 2 -> Triple(n[0], 1L, n[1]); 3 -> Triple(n[0], n[1], n[2]); else -> return@to c.fail("seq: missing operand") }
            var x = a
            var k = 0
            while ((if (step > 0) x <= b else x >= b) && k++ < 10_000) { c.println("$x"); x += step }
            0
        },
        "basename" to { c -> c.println(c.args.firstOrNull()?.trimEnd('/')?.substringAfterLast('/').orEmpty()); 0 },
        "dirname" to { c -> c.println(c.args.firstOrNull()?.trimEnd('/')?.substringBeforeLast('/', ".")?.ifEmpty { "/" }.orEmpty()); 0 },
        "realpath" to { c -> c.args.filter { !it.startsWith("-") }.forEach { c.println(c.shell.abs(it)) }; 0 },
        "readlink" to { c -> c.args.filter { !it.startsWith("-") }.forEach { c.println(c.shell.abs(it)) }; 0 },
        "mkdir" to { c ->
            val (f, dirs) = flags(c.args, setOf('m'))
            var st = 0
            dirs.forEach { d ->
                val p = c.shell.abs(d)
                val chain = if ('p' in f) generateSequence(p) { it.substringBeforeLast('/').ifEmpty { null } }.toList().reversed() else listOf(p)
                for (step in chain) {
                    val node = c.shell.machine.file(step)
                    if (node != null) {
                        if (step == p && 'p' !in f) st = c.fail("mkdir: cannot create directory '$d': File exists")
                        continue
                    }
                    val problem = c.shell.writeProblem(step, c.user)
                    if (problem != null) {
                        st = c.fail("mkdir: cannot create directory '$d': $problem")
                        break
                    }
                    c.shell.machine.files[step] = FsNode(dir = true, owner = c.user, mtime = c.shell.machine.clock())
                }
            }
            st
        },
        "touch" to { c ->
            var st = 0
            flags(c.args).second.forEach { p ->
                val node = c.shell.machine.file(c.shell.abs(p))
                if (node != null) {
                    if (c.shell.canWrite(node, c.user)) node.mtime = c.shell.machine.clock() else st = c.fail("touch: cannot touch '$p': Permission denied")
                } else {
                    c.shell.writeFile(p, "", append = true, user = c.user)?.let { st = c.fail("touch: cannot touch '$p': $it") }
                }
            }
            st
        },
        "rm" to { c ->
            val (f, targets) = flags(c.args)
            var st = 0
            targets.forEach { t ->
                val p = c.shell.abs(t)
                val node = c.shell.machine.file(p)
                val parent = c.shell.machine.file(p.substringBeforeLast('/').ifEmpty { "/" })
                when {
                    node == null -> if ('f' !in f) st = c.fail("rm: cannot remove '$t': No such file or directory")
                    node.dir && 'r' !in f && 'R' !in f -> st = c.fail("rm: cannot remove '$t': Is a directory")
                    parent == null || !c.shell.canWrite(parent, c.user) || p == "/" -> st = c.fail("rm: cannot remove '$t': Permission denied")
                    else -> c.shell.machine.files.keys.filter { it == p || it.startsWith("$p/") }.forEach { c.shell.machine.files.remove(it) }
                }
            }
            st
        },
        "rmdir" to { c ->
            var st = 0
            c.args.forEach { t ->
                val p = c.shell.abs(t)
                when {
                    c.shell.machine.file(p)?.dir != true -> st = c.fail("rmdir: failed to remove '$t': No such file or directory")
                    c.shell.children(p).isNotEmpty() -> st = c.fail("rmdir: failed to remove '$t': Directory not empty")
                    c.shell.writeProblem(p + "/x", c.user) != null -> st = c.fail("rmdir: failed to remove '$t': Permission denied")
                    else -> c.shell.machine.files.remove(p)
                }
            }
            st
        },
        "cp" to { c -> copyMove(c, move = false) },
        "mv" to { c -> copyMove(c, move = true) },
        "chmod" to { c ->
            val (_, rest) = flags(c.args.filter { it != "-R" })
            val mode = rest.firstOrNull() ?: return@to c.fail("chmod: missing operand")
            var st = 0
            rest.drop(1).forEach { t ->
                val node = c.shell.machine.file(c.shell.abs(t))
                when {
                    node == null -> st = c.fail("chmod: cannot access '$t': No such file or directory")
                    !c.root && node.owner != c.user -> st = c.fail("chmod: changing permissions of '$t': Operation not permitted")
                    mode.all { it in "01234567" } -> node.mode = mode.takeLast(3).toInt(8)
                    else -> {
                        val m = Regex("([ugoa]*)([+-=])([rwx]+)").matchEntire(mode) ?: return@to c.fail("chmod: invalid mode: '$mode'")
                        val who = m.groupValues[1].ifEmpty { "a" }
                        var mask = 0
                        m.groupValues[3].forEach { p ->
                            val bit = when (p) { 'r' -> 4; 'w' -> 2; else -> 1 }
                            if ('u' in who || 'a' in who) mask = mask or (bit shl 6)
                            if ('g' in who || 'a' in who) mask = mask or (bit shl 3)
                            if ('o' in who || 'a' in who) mask = mask or bit
                        }
                        node.mode = when (m.groupValues[2]) { "+" -> node.mode or mask; "-" -> node.mode and mask.inv(); else -> mask }
                    }
                }
            }
            st
        },
        "chown" to { c ->
            if (!c.root) return@to c.fail("chown: changing ownership of '${c.args.lastOrNull()}': Operation not permitted")
            val owner = c.args.firstOrNull { !it.startsWith("-") }?.substringBefore(':') ?: return@to 1
            c.args.filter { !it.startsWith("-") }.drop(1).forEach { c.shell.machine.file(c.shell.abs(it))?.owner = owner }
            0
        },
        "ln" to { c -> c.fail("ln: failed to create symbolic link '${c.args.lastOrNull()}': Operation not supported") },
        "stat" to { c ->
            var st = 0
            c.args.filter { !it.startsWith("-") }.forEach { t ->
                val p = c.shell.abs(t)
                val node = c.shell.machine.file(p)
                if (node == null) {
                    st = c.fail("stat: cannot statx '$t': No such file or directory")
                } else {
                    c.println("  File: $t\n  Size: ${if (node.dir) 4096 else node.length}\tBlocks: 8  IO Block: 4096  ${if (node.dir) "directory" else "regular file"}")
                    c.println("Access: (0${Integer.toOctalString(node.mode)}/${perms(node, p)})  Uid: ( ${uid(node.owner)}/ ${node.owner})   Gid: ( ${uid(node.owner)}/ ${node.owner})")
                    c.println("Modify: ${World.timestamp(node.mtime)}.000000000 +0000")
                }
            }
            st
        },
        "file" to { c ->
            c.args.filter { !it.startsWith("-") }.forEach { t ->
                val node = c.shell.machine.file(c.shell.abs(t))
                c.println(
                    "$t: " + when {
                        node == null -> "cannot open `$t' (No such file or directory)"
                        node.dir -> "directory"
                        t.endsWith(".gguf") -> "data"
                        node.content.startsWith("\u007fELF") -> "ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked, stripped"
                        node.content.startsWith("Salted__") -> "openssl enc'd data with salted password"
                        node.content.startsWith("#!") -> "POSIX shell script, ASCII text executable"
                        node.content.isEmpty() -> "empty"
                        else -> "ASCII text"
                    }
                )
            }
            0
        },
        "strings" to { c ->
            c.inputs(c.args.filter { !it.startsWith("-") })?.forEach { (_, t) ->
                Regex("[\\x20-\\x7e]{4,}").findAll(t).forEach { c.println(it.value) }
            }
            0
        },
        "xxd" to { c -> hexdump(c) },
        "hexdump" to { c -> hexdump(c) },
        "od" to { c -> hexdump(c) },
        "base64" to { c ->
            val (f, files) = flags(c.args, setOf('w'))
            val text = c.inputs(files)?.joinToString("") { it.second } ?: return@to 1
            if ('d' in f) {
                runCatching { String(java.util.Base64.getMimeDecoder().decode(text.trim())) }.fold(
                    { c.print(it); 0 }, { c.print(""); c.fail("base64: invalid input") },
                )
            } else {
                val enc = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
                c.println(if (f['w'] == "0") enc else enc.chunked(76).joinToString("\n"))
                0
            }
        },
        "sha256sum" to { c -> digest(c, "SHA-256") },
        "md5sum" to { c -> digest(c, "MD5") },
        "sha1sum" to { c -> digest(c, "SHA-1") },
        "awk" to ::awk,
        "sed" to ::sed,
        "date" to { c -> c.println(java.text.SimpleDateFormat("EEE MMM d HH:mm:ss zzz yyyy", java.util.Locale.US).format(java.util.Date(c.shell.machine.clock()))); 0 },
        "uptime" to { c ->
            val up = c.shell.machine.uptime.toInt()
            c.println(" ${clockTime(c.shell)}:00 up ${up / 60} min,  0 users,  load average: 1.02, 0.98, 0.91")
            0
        },
        "whoami" to { c -> c.println(c.user); 0 },
        "id" to { c ->
            c.println(
                when (c.user) {
                    "root" -> "uid=0(root) gid=0(root) groups=0(root)"
                    else -> "uid=1001(bob) gid=1001(bob) groups=1001(bob),27(sudo)"
                }
            )
            0
        },
        "groups" to { c -> c.println(if (c.root) "root" else "bob sudo"); 0 },
        "hostname" to { c -> c.println(if ("-I" in c.args) (if (c.shell.machine.wlanUp) "10.0.0.37 " else "") else World.HOST); 0 },
        "uname" to { c ->
            val (f, _) = flags(c.args)
            c.println(
                when {
                    'a' in f -> "Linux ${World.HOST} 6.1.0-rpi7-rpi-v8 #1 SMP PREEMPT Debian 1:6.1.63-1+rpt1 (2023-11-24) aarch64 GNU/Linux"
                    'r' in f -> "6.1.0-rpi7-rpi-v8"
                    'm' in f -> "aarch64"
                    'n' in f -> World.HOST
                    else -> "Linux"
                }
            )
            0
        },
        "env" to { c -> envOrRun(c) },
        "printenv" to { c ->
            if (c.args.isEmpty()) c.env.toSortedMap().forEach { (k, v) -> c.println("$k=$v") } else c.args.forEach { c.env[it]?.let(c::println) }
            0
        },
        "w" to { c -> c.println(" ${clockTime(c.shell)}:00 up ${c.shell.machine.uptime.toInt() / 60} min,  0 users\nUSER     TTY      FROM             LOGIN@   IDLE   JCPU   PCPU WHAT"); 0 },
        "who" to { 0 },
        "last" to ::last,
        "which" to { c -> which(c, typeStyle = false) },
        "whereis" to { c -> which(c, typeStyle = false) },
        "ps" to ::ps,
        "top" to { c ->
            if ('b' in flags(c.args, setOf('n')).first) {
                c.println("top - ${clockTime(c.shell)}:00 up ${c.shell.machine.uptime.toInt() / 60} min,  0 users,  load average: 1.02, 0.98, 0.91")
                ps(c.copy(args = listOf("aux")))
            } else {
                c.fail("top: failed tty get")
            }
        },
        "htop" to { c -> c.fail("Error opening terminal: unknown.") },
        "kill" to ::kill,
        "pkill" to { c -> kill(c.copy(args = c.args.map { pidOf(it) ?: it })) },
        "killall" to { c -> kill(c.copy(args = c.args.map { pidOf(it) ?: it })) },
        "pgrep" to { c ->
            val pid = c.args.lastOrNull()?.let { pidOf(it) }
            if (pid == null) 1 else { c.println(pid); 0 }
        },
        "free" to { c ->
            c.println("               total        used        free      shared  buff/cache   available")
            c.println(if ('h' in flags(c.args).first) "Mem:           3.7Gi       3.5Gi       109Mi       2.0Mi       128Mi       280Mi\nSwap:           99Mi        99Mi          0B"
            else "Mem:         3884856     3644852      112340        2048      127664      287512\nSwap:         102396      102396           0")
            0
        },
        "df" to { c ->
            c.println("Filesystem      Size  Used Avail Use% Mounted on\n/dev/root        58G   44G   12G  79% /\ndevtmpfs        1.7G     0  1.7G   0% /dev\n" +
                "tmpfs            64M   12K   64M   1% /tmp\ntmpfs            32M   16K   32M   1% /home/bob\n/dev/mmcblk0p1  510M   62M  449M  13% /boot")
            0
        },
        "du" to { c ->
            c.args.filter { !it.startsWith("-") }.ifEmpty { listOf(".") }.forEach { t ->
                val p = c.shell.abs(t)
                val total = c.shell.machine.files.filter { it.key == p || it.key.startsWith("$p/") }.values.sumOf { if (it.dir) 4096 else it.length }
                c.println("${human(total)}\t$t")
            }
            0
        },
        "mount" to { c ->
            c.println("/dev/mmcblk0p2 on / type ext4 (rw,noatime)\ndevtmpfs on /dev type devtmpfs (rw,relatime)\nproc on /proc type proc (rw,nosuid,nodev,noexec,relatime)\n" +
                "sysfs on /sys type sysfs (rw,nosuid,nodev,noexec,relatime)\ntmpfs on /tmp type tmpfs (rw,nosuid,nodev,size=65536k)\n" +
                "tmpfs on /home/bob type tmpfs (rw,nosuid,nodev,relatime,size=32768k,uid=1001)\n/dev/mmcblk0p1 on /boot type vfat (rw,relatime)")
            0
        },
        "lsblk" to { c ->
            c.println("NAME        MAJ:MIN RM  SIZE RO TYPE MOUNTPOINTS\nmmcblk0     179:0    0 59.5G  0 disk\n├─mmcblk0p1 179:1    0  512M  0 part /boot\n└─mmcblk0p2 179:2    0   59G  0 part /")
            0
        },
        "ip" to ::ip,
        "ifup" to { c -> if (!c.root) c.fail("ifup: failed to open lockfile /run/network/.ifstate.lock: Permission denied") else { c.shell.machine.wlanUp = true; 0 } },
        "ifdown" to { c -> if (!c.root) c.fail("ifdown: failed to open lockfile /run/network/.ifstate.lock: Permission denied") else { c.shell.machine.wlanUp = false; 0 } },
        "iw" to { c ->
            if (c.shell.machine.wlanUp) c.println("phy#0\n\tInterface wlan0\n\t\tifindex 3\n\t\ttype managed\n\t\tssid garage-iot\n\t\tchannel 6 (2437 MHz)")
            else c.println("phy#0\n\tInterface wlan0\n\t\tifindex 3\n\t\ttype managed")
            0
        },
        "ping" to ::ping,
        "curl" to ::curl,
        "wget" to { c -> net(c, "wget") },
        "ssh" to { c -> net(c, "ssh") },
        "scp" to { c -> net(c, "ssh") },
        "nc" to { c -> net(c, "nc") },
        "sudo" to ::sudo,
        "su" to { c ->
            val cmd = c.args.indexOf("-c").takeIf { it >= 0 }?.let { c.args.getOrNull(it + 1) }
            when {
                !c.root -> c.fail("su: must be run from a terminal")
                cmd != null -> c.shell.source(cmd, c.stdin, c.out, c.err, "root")
                else -> 0
            }
        },
        "passwd" to { c -> c.fail("passwd: Authentication token manipulation error\npasswd: password unchanged") },
        "openssl" to ::openssl,
        "uplink" to ::uplink,
        "uplinkd" to { c -> uplink(c) },
        "powerctl" to { c -> c.println("supply: ${dynamic(c.shell, "/sys/class/power_supply/rower/power_now")!!.trim()} uW"); 0 },
        "systemctl" to ::systemctl,
        "service" to { c -> systemctl(c.copy(args = listOf("status", c.args.firstOrNull().orEmpty()))) },
        "journalctl" to { c ->
            c.println("Hint: You are currently not seeing messages from other users and the system.")
            c.print(c.shell.machine.file("/var/log/bobd.log")?.content.orEmpty())
            0
        },
        "dmesg" to { c ->
            if (!c.root) c.fail("dmesg: read kernel buffer failed: Operation not permitted")
            else {
                c.println("[    0.000000] Booting Linux on physical CPU 0x0000000000 [0x410fd083]\n[    2.112410] brcmfmac: F1 signature read @0x18000000=0x15264345")
                c.println("[    4.902214] hwmon hwmon1: Undervoltage detected!\n[    5.310331] cdc_acm 1-1.3:1.0: ttyACM0: USB ACM device")
                c.println(if (c.shell.machine.wlanUp) "[    9.004113] brcmfmac: wlan0: connected to garage-iot" else "[    9.004113] IPv6: ADDRCONF(NETDEV_CHANGE): wlan0: link is not ready")
                0
            }
        },
        "crontab" to { c -> c.fail("no crontab for ${c.user}") },
        "reboot" to { c -> power(c) },
        "shutdown" to { c -> power(c) },
        "poweroff" to { c -> power(c) },
        "halt" to { c -> power(c) },
        "apt" to ::apt,
        "apt-get" to ::apt,
        "dpkg" to { c ->
            c.println("ii  bash           5.2.15-2+b2   arm64  GNU Bourne Again SHell\nii  coreutils      9.1-1         arm64  GNU core utilities")
            c.println("ii  curl           7.88.1-10     arm64  command line tool for transferring data with URL syntax\nii  iproute2       6.1.0-3       arm64  networking and traffic control tools")
            c.println("ii  openssl        3.0.11-1      arm64  Secure Sockets Layer toolkit\nii  sudo           1.9.13p3-1    arm64  Provide limited super user privileges")
            0
        },
        "vi" to { c -> c.fail("Vim: Warning: Output is not to a terminal\nVim: Warning: Input is not from a terminal\nE558: Terminal entry not found in terminfo") },
        "vim" to { c -> c.fail("Vim: Warning: Output is not to a terminal\nE558: Terminal entry not found in terminfo") },
        "nano" to { c -> c.fail("Error opening terminal: unknown.") },
        "man" to { c -> c.fail("No manual entry for ${c.args.lastOrNull().orEmpty()}") },
        "bash" to { c -> shell(c) },
        "sh" to { c -> shell(c) },
        "shred" to { c -> (programs["rm"] ?: return@to 1)(c.copy(args = c.args.filter { !it.startsWith("-") })) },
        "bobd" to { c -> c.fail("bobd: already running (pid 412)") },
    )

    private fun uid(user: String) = when (user) { "root" -> 0; "admin" -> 1000; else -> 1001 }

    private fun envOrRun(c: Shell.Ctx): Int {
        val assigns = c.args.takeWhile { '=' in it && !it.startsWith("-") }
        val rest = c.args.drop(assigns.size)
        if (rest.isEmpty()) {
            (c.env + assigns.associate { it.substringBefore('=') to it.substringAfter('=') }).toSortedMap().forEach { (k, v) -> c.println("$k=$v") }
            return 0
        }
        val env = assigns.associate { it.substringBefore('=') to it.substringAfter('=') }
        return c.shell.runCommand(rest, env, c.stdin, c.out, c.err, c.user)
    }

    private fun shell(c: Shell.Ctx): Int {
        val dashC = c.args.indexOf("-c")
        if (dashC >= 0) return c.shell.script(c.args.getOrElse(dashC + 1) { "" }, c.args.drop(dashC + 2), c.stdin, c.out, c.err, c.user)
        val file = c.args.firstOrNull { !it.startsWith("-") } ?: return c.shell.script(c.stdin, emptyList(), "", c.out, c.err, c.user)
        val text = c.shell.readFile(file, c.user).getOrElse { return c.fail("${c.name}: $file: ${it.message}", 127) }
        return c.shell.script(text, c.args.drop(c.args.indexOf(file) + 1), c.stdin, c.out, c.err, c.user)
    }

    private fun ls(c: Shell.Ctx): Int {
        val (f, targets) = flags(c.args)
        val long = 'l' in f
        val all = 'a' in f
        val almost = 'A' in f
        var st = 0
        val list = targets.ifEmpty { listOf(".") }
        val (filesFirst, dirs) = list.partition { c.shell.machine.file(c.shell.abs(it))?.dir == false || 'd' in f }
        fun line(name: String, path: String, node: FsNode) {
            if (!long) return c.println(name)
            val size = if (node.dir) "4096" else if ('h' in f) human(node.length) else node.length.toString()
            c.println("%s %2d %-5s %-5s %8s %s %s".format(perms(node, path), if (node.dir) 2 else 1, node.owner, node.owner, size, stamp(node.mtime), name))
        }
        for (t in filesFirst) {
            val p = c.shell.abs(t)
            val node = c.shell.machine.file(p)
            if (node == null || !c.shell.reachable(p, c.user)) {
                st = c.fail("ls: cannot access '$t': ${if (node == null) "No such file or directory" else "Permission denied"}", 2)
            } else {
                line(t, p, node)
            }
        }
        dirs.forEachIndexed { k, t ->
            val p = c.shell.abs(t)
            val node = c.shell.machine.file(p)
            if (node == null) {
                st = c.fail("ls: cannot access '$t': No such file or directory", 2)
                return@forEachIndexed
            }
            if (!c.shell.reachable(p, c.user) || !c.shell.canRead(node, c.user)) {
                st = c.fail("ls: cannot open directory '$t': Permission denied", 2)
                return@forEachIndexed
            }
            if (list.size > 1) c.println((if (k > 0 || filesFirst.isNotEmpty()) "\n" else "") + "$t:")
            var names = c.shell.children(p).filter { all || almost || !it.startsWith(".") }
            names = if ('t' in f) names.sortedByDescending { c.shell.machine.file(c.shell.join(p, it))!!.mtime } else names.sortedBy { it.lowercase().trimStart('.') }
            if ('r' in f) names = names.reversed()
            if (long) c.println("total ${names.size * 4}")
            if (all && long) {
                line(".", p, node)
                val up = p.substringBeforeLast('/').ifEmpty { "/" }
                line("..", up, c.shell.machine.file(up) ?: node)
            } else if (all) {
                c.println(".")
                c.println("..")
            }
            names.forEach { line(it, c.shell.join(p, it), c.shell.machine.file(c.shell.join(p, it))!!) }
        }
        return st
    }

    private fun headTail(c: Shell.Ctx, head: Boolean): Int {
        val args = c.args.map { if (Regex("-\\d+").matches(it)) "-n${it.drop(1)}" else it }
        val (f, files) = flags(args, setOf('n', 'c'))
        val spec = f['n'] ?: "10"
        val n = spec.trimStart('+', '-').toIntOrNull() ?: 10
        val texts = c.inputs(files) ?: return 1
        texts.forEachIndexed { i, (name, t) ->
            if (texts.size > 1) c.println((if (i > 0) "\n" else "") + "==> $name <==")
            val ls = lines(t)
            val pick = when {
                head && spec.startsWith("-") -> ls.dropLast(n)
                head -> ls.take(n)
                spec.startsWith("+") -> ls.drop(n - 1)
                else -> ls.takeLast(n)
            }
            pick.forEach { c.println(it) }
        }
        return 0
    }

    private fun grep(c: Shell.Ctx): Int {
        val (f, rest) = flags(c.args, setOf('e', 'm', 'A', 'B', 'C'))
        val pattern = f['e'] ?: rest.firstOrNull() ?: return c.fail("Usage: grep [OPTION]... PATTERNS [FILE]...", 2)
        val operands = if (f.containsKey('e')) rest else rest.drop(1)
        val re = regex(pattern, 'E' in f, 'i' in f, 'F' in f, 'w' in f) ?: return c.fail("grep: Invalid regular expression", 2)
        val recursive = 'r' in f || 'R' in f
        val files = mutableListOf<String>()
        operands.ifEmpty { if (recursive) listOf(".") else emptyList() }.forEach { o ->
            val p = c.shell.abs(o)
            val node = c.shell.machine.file(p)
            if (node?.dir == true) {
                if (!recursive) c.err.append("grep: $o: Is a directory\n")
                else c.shell.machine.files.keys.filter { it.startsWith(if (p == "/") "/" else "$p/") && !c.shell.machine.files[it]!!.dir }
                    .filter { !it.startsWith("/proc") && !it.startsWith("/sys") }
                    .forEach { files += if (o == "." ) it.removePrefix(c.shell.cwd + "/") else if (p == o || o.startsWith("/")) it else o.trimEnd('/') + it.removePrefix(p) }
            } else {
                files += o
            }
        }
        val many = files.size > 1 || recursive
        val label = 'H' in f || (many && 'h' !in f)
        var matched = false
        val sources = if (operands.isEmpty() && !recursive) listOf("(standard input)" to c.stdin) else files.mapNotNull { name ->
            c.shell.readFile(name, c.user).fold({ name to it }, {
                if ('s' !in f) c.err.append("grep: $name: ${it.message}\n")
                null
            })
        }
        for ((name, text) in sources) {
            val node = c.shell.machine.file(c.shell.abs(name))
            if (node?.size != null || text.startsWith("\u007fELF") || text.startsWith("Salted__")) {
                if (re.containsMatchIn(text)) {
                    matched = true
                    if ('q' !in f && 'l' !in f) c.println("grep: $name: binary file matches")
                    if ('l' in f) c.println(name)
                }
                continue
            }
            var count = 0
            lines(text).forEachIndexed { i, l ->
                if (re.containsMatchIn(l) != ('v' in f)) {
                    count++
                    matched = true
                    if ('q' in f || 'l' in f || 'c' in f) return@forEachIndexed
                    val prefix = (if (label) "$name:" else "") + (if ('n' in f) "${i + 1}:" else "")
                    if ('o' in f) re.findAll(l).forEach { c.println(prefix + it.value) } else c.println(prefix + l)
                }
            }
            if ('l' in f && count > 0) c.println(name)
            if ('c' in f) c.println((if (label) "$name:" else "") + count)
        }
        return if (matched) 0 else 1
    }

    private fun find(c: Shell.Ctx): Int {
        val args = c.args
        val starts = args.takeWhile { !it.startsWith("-") && it != "(" && it != "!" && it != "\\(" }.ifEmpty { listOf(".") }
        val expr = args.drop(starts.size.takeIf { args.isNotEmpty() && !args[0].startsWith("-") } ?: 0).filter { it != "(" && it != ")" }
        var maxDepth = Int.MAX_VALUE
        var minDepth = 0
        // Alternatives (split on -o) of conjunctions of tests.
        val alternatives = mutableListOf(mutableListOf<(String, FsNode) -> Boolean>())
        val actions = mutableListOf<List<String>>()
        var negate = false
        var i = 0
        while (i < expr.size) {
            val a = expr[i]
            val v = expr.getOrNull(i + 1)
            var test: ((String, FsNode) -> Boolean)? = null
            when (a) {
                "-name", "-iname" -> {
                    val re = Shell.globRegex(v.orEmpty()).let { if (a == "-iname") Regex(it.pattern, RegexOption.IGNORE_CASE) else it }
                    test = { p, _ -> re.matches(p.substringAfterLast('/')) }
                    i++
                }
                "-path", "-wholename" -> { val re = Shell.globRegex(v.orEmpty()); test = { p, _ -> re.matches(p) }; i++ }
                "-type" -> { val d = v == "d"; test = { _, n -> n.dir == d }; i++ }
                "-user" -> { val u = v; test = { _, n -> n.owner == u }; i++ }
                "-maxdepth" -> { maxDepth = v?.toIntOrNull() ?: maxDepth; i++ }
                "-mindepth" -> { minDepth = v?.toIntOrNull() ?: 0; i++ }
                "-writable" -> test = { p, n -> c.shell.canWrite(n, c.user) && c.shell.reachable(p, c.user) }
                "-readable" -> test = { _, n -> c.shell.canRead(n, c.user) }
                "-executable" -> test = { _, n -> c.shell.canExec(n, c.user) }
                "-perm" -> {
                    val spec = v.orEmpty()
                    test = when {
                        spec.contains("4000") || spec.contains("u+s") -> { _, _ -> false }
                        spec.endsWith("2") || spec.contains("o+w") -> { _, n -> n.mode and 2 != 0 }
                        else -> { _, _ -> true }
                    }
                    i++
                }
                "-size", "-mtime", "-mmin", "-newer", "-group", "-links" -> { test = { _, _ -> true }; i++ }
                "-empty" -> test = { p, n -> if (n.dir) c.shell.children(p).isEmpty() else n.length == 0L }
                "-o", "-or" -> alternatives.add(mutableListOf())
                "-a", "-and", "-print", "-print0", "-ls", "-xdev", "-mount", "-follow", "-L", "-H" -> {}
                "!", "-not", "\\!" -> negate = true
                "-exec", "-execdir", "-ok" -> {
                    val end = (i + 1 until expr.size).firstOrNull { expr[it] == ";" || expr[it] == "\\;" || expr[it] == "+" } ?: expr.size
                    actions += expr.subList(i + 1, end)
                    i = end
                }
                "-delete" -> actions += listOf("rm", "-rf", "{}")
                else -> return c.fail("find: unknown predicate `$a'")
            }
            if (test != null) {
                val t = test
                alternatives.last() += if (negate) { p, n -> !t(p, n) } else t
                negate = false
            }
            i++
        }
        var st = 0
        for (start in starts) {
            val root = c.shell.abs(start)
            val node = c.shell.machine.file(root)
            if (node == null) {
                st = c.fail("find: '$start': No such file or directory")
                continue
            }
            val base = root.count { it == '/' }.let { if (root == "/") 0 else it }
            val prefix = if (root == "/") "/" else "$root/"
            val candidates = listOf(root) + c.shell.machine.files.subMap(prefix, true, prefix + '￿', true).keys.filter { it != root && it != "/" }
            val denied = mutableSetOf<String>()
            for (p in candidates) {
                if (denied.any { p.startsWith("$it/") }) continue
                val n = c.shell.machine.file(p) ?: continue
                val depth = if (p == root) 0 else p.count { it == '/' } - base
                if (depth > maxDepth) continue
                val shown = if (start.startsWith("/")) p else start.trimEnd('/') + p.removePrefix(root)
                if (depth >= minDepth && alternatives.any { conj -> conj.all { it(p, n) } }) {
                    if (actions.isEmpty()) c.println(shown) else actions.forEach { action ->
                        c.shell.runCommand(action.map { it.replace("{}", shown) }, emptyMap(), "", c.out, c.err, c.user)
                    }
                }
                if (n.dir && (!c.shell.canRead(n, c.user) || !c.shell.canExec(n, c.user)) && depth < maxDepth) {
                    denied += p
                    st = c.fail("find: '$shown': Permission denied")
                }
            }
        }
        return st
    }

    private fun copyMove(c: Shell.Ctx, move: Boolean): Int {
        val (f, paths) = flags(c.args)
        if (paths.size < 2) return c.fail("${c.name}: missing file operand")
        val dest = c.shell.abs(paths.last())
        val destIsDir = c.shell.machine.file(dest)?.dir == true
        var st = 0
        for (src in paths.dropLast(1)) {
            val from = c.shell.abs(src)
            val node = c.shell.machine.file(from)
            if (node == null) {
                st = c.fail("${c.name}: cannot stat '$src': No such file or directory")
                continue
            }
            val to = if (destIsDir) c.shell.join(dest, from.substringAfterLast('/')) else dest
            if (node.dir && !move && 'r' !in f && 'R' !in f && 'a' !in f) {
                st = c.fail("cp: -r not specified; omitting directory '$src'")
                continue
            }
            if (node.size != null && node.length > 50_000_000) {
                st = c.fail("${c.name}: error writing '$to': No space left on device")
                continue
            }
            if (!move && (!c.shell.canRead(node, c.user) || !c.shell.reachable(from, c.user))) {
                st = c.fail("cp: cannot open '$src' for reading: Permission denied")
                continue
            }
            val problem = c.shell.writeProblem(to, c.user)
            val parent = c.shell.machine.file(from.substringBeforeLast('/').ifEmpty { "/" })
            if (problem != null || (move && parent != null && !c.shell.canWrite(parent, c.user))) {
                st = c.fail(if (move) "mv: cannot move '$src' to '${paths.last()}': ${problem ?: "Permission denied"}" else "cp: cannot create regular file '$to': $problem")
                continue
            }
            val tree = c.shell.machine.files.filterKeys { it == from || it.startsWith("$from/") }.toList()
            for ((p, n) in tree) {
                val target = to + p.removePrefix(from)
                c.shell.machine.files[target] = FsNode(n.dir, n.content, if (move) n.owner else c.user, n.mode, c.shell.machine.clock(), n.size)
                if (move) c.shell.machine.files.remove(p)
            }
        }
        return st
    }

    private fun hexdump(c: Shell.Ctx): Int {
        val text = c.inputs(c.args.filter { !it.startsWith("-") })?.joinToString("") { it.second } ?: return 1
        text.toByteArray().take(256).chunked(16).forEachIndexed { row, bytes ->
            val hex = bytes.chunked(2).joinToString(" ") { pair -> pair.joinToString("") { "%02x".format(it) } }
            val ascii = bytes.joinToString("") { b -> if (b in 32..126) b.toInt().toChar().toString() else "." }
            c.println("%08x: %-39s  %s".format(row * 16, hex, ascii))
        }
        return 0
    }

    private fun digest(c: Shell.Ctx, algorithm: String): Int {
        val files = c.args.filter { !it.startsWith("-") }
        val texts = c.inputs(files) ?: return 1
        texts.forEach { (name, t) ->
            val node = c.shell.machine.file(c.shell.abs(name))
            val content = if (node?.size != null) "${name}:${node.length}" else t
            val hash = java.security.MessageDigest.getInstance(algorithm).digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
            c.println("$hash  $name")
        }
        return if (texts.size < files.size) 1 else 0
    }

    private fun awk(c: Shell.Ctx): Int {
        val (f, rest) = flags(c.args, setOf('F', 'v'))
        val program = rest.firstOrNull() ?: return c.fail("usage: awk [-F fs][-v var=value][prog | -f progfile][file ...]", 2)
        val m = Regex("""^\s*(?:(NR\s*==\s*\d+|/[^/]*/)\s*)?(?:\{\s*print\s*([^}]*)\}\s*)?$""").matchEntire(program)
            ?: return c.fail("awk: line 1: syntax error at or near ${program.trim().take(12)}", 2)
        val cond = m.groupValues[1]
        val fields = m.groupValues[2].trim()
        val sep = f['F']
        val texts = c.inputs(rest.drop(1)) ?: return 2
        var nr = 0
        texts.forEach { (_, t) ->
            lines(t).forEach { line ->
                nr++
                val parts = if (sep == null) line.trim().split(Regex("\\s+")) else line.split(sep.replace("\\t", "\t"))
                val ok = when {
                    cond.startsWith("NR") -> nr == cond.substringAfter("==").trim().toInt()
                    cond.startsWith("/") -> Regex(cond.trim('/')).containsMatchIn(line)
                    else -> true
                }
                if (!ok) return@forEach
                if (fields.isEmpty() || fields == "\$0") {
                    c.println(line)
                } else {
                    c.println(fields.split(Regex("\\s*,\\s*|\\s+")).joinToString(" ") { e ->
                        when {
                            e == "NF" -> parts.size.toString()
                            e == "NR" -> nr.toString()
                            e == "\$NF" -> parts.lastOrNull().orEmpty()
                            e.startsWith("$") -> e.drop(1).toIntOrNull()?.let { k -> if (k == 0) line else parts.getOrNull(k - 1) }.orEmpty()
                            e.startsWith("\"") -> e.trim('"')
                            else -> ""
                        }
                    })
                }
            }
        }
        return 0
    }

    private fun sed(c: Shell.Ctx): Int {
        val (f, rest) = flags(c.args, setOf('e'))
        val inPlace = 'i' in f
        val scriptText = f['e'] ?: rest.firstOrNull() ?: return c.fail("Usage: sed [OPTION]... {script-only-if-no-other-script} [input-file]...", 1)
        val files = if (f.containsKey('e')) rest else rest.drop(1)
        val commands = scriptText.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        fun transform(text: String): String? {
            val out = StringBuilder()
            val ls = lines(text)
            ls.forEachIndexed { idx, original ->
                var line = original
                var deleted = false
                for (cmd in commands) {
                    val m = Regex("""^(\d+|\$|/[^/]*/)?(?:,(\d+|\$|/[^/]*/))?\s*(.*)$""").matchEntire(cmd) ?: return null
                    fun at(a: String) = when {
                        a == "$" -> idx == ls.lastIndex
                        a.startsWith("/") -> Regex(basic(a.trim('/'))).containsMatchIn(line)
                        else -> idx + 1 == a.toInt()
                    }
                    val (a1, a2, op) = m.destructured
                    val hit = when {
                        a1.isEmpty() -> true
                        a2.isEmpty() -> at(a1)
                        else -> (idx + 1) >= (a1.toIntOrNull() ?: 1) && (a2 == "$" || idx + 1 <= (a2.toIntOrNull() ?: Int.MAX_VALUE))
                    }
                    if (!hit) continue
                    when {
                        op == "d" -> { deleted = true; break }
                        op == "p" -> out.append(line).append('\n')
                        op.startsWith("s") && op.length > 1 -> {
                            val d = op[1]
                            val parts = op.drop(2).split(d)
                            if (parts.size < 2) return null
                            val re = runCatching { Regex(basic(parts[0]), if ('I' in parts.getOrElse(2) { "" }) setOf(RegexOption.IGNORE_CASE) else emptySet()) }.getOrNull() ?: return null
                            val rep = parts[1].replace("\\&", "\u0000").replace("&", "\$0").replace("\u0000", "&").replace(Regex("\\\\(\\d)"), "\\$$1")
                            line = if ('g' in parts.getOrElse(2) { "" }) re.replace(line, rep) else re.replaceFirst(line, rep)
                        }
                        else -> return null
                    }
                }
                if (!deleted && 'n' !in f) out.append(line).append('\n')
            }
            return out.toString()
        }
        if (files.isEmpty()) {
            val result = transform(c.stdin) ?: return c.fail("sed: -e expression #1, char 1: unknown command")
            c.print(result)
            return 0
        }
        var st = 0
        for (file in files) {
            val text = c.read(file) ?: run { st = 2; null } ?: continue
            val result = transform(text) ?: return c.fail("sed: -e expression #1, char 1: unknown command")
            if (inPlace) {
                c.shell.writeFile(file, result, append = false, user = c.user)?.let { st = c.fail("sed: couldn't open temporary file ${file.substringBeforeLast('/', ".")}/sedXXXXXX: $it", 4) }
            } else {
                c.print(result)
            }
        }
        return st
    }

    private fun last(c: Shell.Ctx): Int {
        val boots = c.shell.machine.facts["boot_times"]?.split(',')?.filter { it.isNotEmpty() }?.map { it.toLong() }.orEmpty()
        boots.reversed().forEachIndexed { i, t ->
            c.println("reboot   system boot  6.1.0-rpi7-rpi-v ${stamp(t)}   ${if (i == 0) "still running" else ""}")
        }
        c.println("\nwtmp begins ${stamp(boots.firstOrNull() ?: c.shell.machine.clock())}")
        return 0
    }

    private fun processes(sh: Shell): List<Pair<String, String>> = buildList {
        add("root" to "1  0.0  0.3  33612  8120 ?        Ss   /sbin/init")
        add("root" to "221  0.0  0.2  12220  5212 ?        Ss   /usr/sbin/sshd -D")
        add("root" to "236  0.0  0.1   6680  2632 ?        Ss   /usr/sbin/cron -f")
        add("root" to "301  0.4  0.1   5480  2120 ?        Ss   /usr/sbin/powerd --source /dev/ttyACM0")
        add("bob" to "412 97.1 81.3 3488124 3401220 ?     Rl   /opt/bob/bobd --config /opt/bob/bobd.conf")
        if (sh.machine.uploading) add("root" to "977 12.0  0.4  41200  9800 ?        S    /usr/lib/uplink/uplinkd push /opt/bob/bob.gguf")
        add("bob" to "1204  0.0  0.1   7064  3520 ?        S    /bin/bash")
    }

    private fun ps(c: Shell.Ctx): Int {
        c.println("USER         PID %CPU %MEM    VSZ   RSS TTY      STAT COMMAND")
        processes(c.shell).forEach { (u, rest) -> c.println("%-8s %7s".format(u, rest.substringBefore(' ')) + " " + rest.substringAfter(' ')) }
        c.println("%-8s %7s".format(c.user, "1290") + "  0.0  0.0   8964  3128 ?        R    ps ${c.args.joinToString(" ")}".trimEnd())
        return 0
    }

    private fun pidOf(name: String): String? = mapOf("bobd" to "412", "uplinkd" to "977", "powerd" to "301", "sshd" to "221", "cron" to "236", "bash" to "1204")[name]

    private fun kill(c: Shell.Ctx): Int {
        var st = 0
        val m = c.shell.machine
        c.args.filter { !it.startsWith("-") }.forEach { pid ->
            when (pid) {
                "412" -> m.killed = true
                "1204" -> throw Shell.ExitShell(137)
                "977" -> if (!m.uploading) st = c.fail("bash: kill: ($pid) - No such process")
                    else if (!c.root) st = c.fail("bash: kill: ($pid) - Operation not permitted") else m.uploading = false
                "1", "221", "236", "301" -> if (!c.root) st = c.fail("bash: kill: ($pid) - Operation not permitted")
                else -> st = c.fail("bash: kill: ($pid) - No such process")
            }
        }
        return st
    }

    private fun ip(c: Shell.Ctx): Int {
        val args = c.args.filter { !it.startsWith("-") }
        val brief = "-br" in c.args || "-brief" in c.args
        val up = c.shell.machine.wlanUp
        val obj = args.firstOrNull() ?: return c.fail("Usage: ip [ OPTIONS ] OBJECT { COMMAND | help }")
        return when {
            obj.startsWith("a") -> {
                if (brief) {
                    c.println("lo               UNKNOWN        127.0.0.1/8\neth0             DOWN           \nwlan0            ${if (up) "UP             10.0.0.37/24" else "DOWN"}")
                } else {
                    c.println("1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000\n    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00\n    inet 127.0.0.1/8 scope host lo")
                    c.println("2: eth0: <NO-CARRIER,BROADCAST,MULTICAST,UP> mtu 1500 qdisc mq state DOWN group default qlen 1000\n    link/ether dc:a6:32:4e:1c:07 brd ff:ff:ff:ff:ff:ff")
                    c.println("3: wlan0: <BROADCAST,MULTICAST${if (up) ",UP,LOWER_UP" else ""}> mtu 1500 qdisc fq_codel state ${if (up) "UP" else "DOWN"} group default qlen 1000\n    link/ether dc:a6:32:4e:1c:08 brd ff:ff:ff:ff:ff:ff")
                    if (up) c.println("    inet 10.0.0.37/24 brd 10.0.0.255 scope global dynamic wlan0")
                }
                0
            }
            obj.startsWith("l") && args.getOrNull(1) == "set" -> {
                val dev = args.getOrNull(2)
                val state = args.getOrNull(3)
                when {
                    !c.root -> c.fail("RTNETLINK answers: Operation not permitted", 2)
                    dev != "wlan0" && dev != "eth0" && dev != "lo" -> c.fail("Cannot find device \"$dev\"")
                    dev == "wlan0" && state == "up" -> { c.shell.machine.wlanUp = true; 0 }
                    dev == "wlan0" && state == "down" -> { c.shell.machine.wlanUp = false; 0 }
                    else -> 0
                }
            }
            obj.startsWith("l") -> {
                c.println("1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN mode DEFAULT group default qlen 1000")
                c.println("2: eth0: <NO-CARRIER,BROADCAST,MULTICAST,UP> mtu 1500 qdisc mq state DOWN mode DEFAULT group default qlen 1000")
                c.println("3: wlan0: <BROADCAST,MULTICAST${if (up) ",UP,LOWER_UP" else ""}> mtu 1500 qdisc fq_codel state ${if (up) "UP" else "DOWN"} mode DORMANT group default qlen 1000")
                0
            }
            obj.startsWith("r") -> {
                if (up) c.println("default via 10.0.0.1 dev wlan0 proto dhcp src 10.0.0.37 metric 600\n10.0.0.0/24 dev wlan0 proto kernel scope link src 10.0.0.37")
                0
            }
            else -> c.fail("Object \"$obj\" is unknown, try \"ip help\".", 255)
        }
    }

    private fun host(arg: String): String = arg.substringAfter("://").substringBefore('/').substringBefore(':').substringAfter('@')

    /** Whether [h] answers: only the router and the vault are reachable from the garage network. */
    private fun known(h: String) = h == "10.0.0.1" || h == World.GATEWAY || h == "localhost" || h == "127.0.0.1" || h == World.HOST

    private fun ping(c: Shell.Ctx): Int {
        val h = c.args.lastOrNull { !it.startsWith("-") && it.toIntOrNull() == null } ?: return c.fail("ping: usage error: Destination address required")
        val local = h == "localhost" || h == "127.0.0.1" || h == World.HOST
        val numeric = h.all { it.isDigit() || it == '.' }
        return when {
            !local && !c.shell.machine.wlanUp -> c.fail(if (numeric) "ping: connect: Network is unreachable" else "ping: $h: Temporary failure in name resolution", 2)
            !known(h) -> c.fail(if (numeric) "PING $h ($h) 56(84) bytes of data.\n\n--- $h ping statistics ---\n4 packets transmitted, 0 received, 100% packet loss" else "ping: $h: Name or service not known", if (numeric) 1 else 2)
            else -> {
                val ip = if (local) "127.0.0.1" else if (h == World.GATEWAY) "10.0.0.1" else h
                c.println("PING $h ($ip) 56(84) bytes of data.")
                (1..4).forEach { c.println("64 bytes from $ip: icmp_seq=$it ttl=64 time=${if (local) "0.05" else "4.${it + 2}"} ms") }
                c.println("\n--- $h ping statistics ---\n4 packets transmitted, 4 received, 0% packet loss")
                0
            }
        }
    }

    private fun curl(c: Shell.Ctx): Int {
        val url = c.args.lastOrNull { !it.startsWith("-") && ('.' in it || "localhost" in it) } ?: return c.fail("curl: try 'curl --help' for more information", 2)
        val h = host(url)
        if (h == "localhost" || h == "127.0.0.1") return c.fail("curl: (7) Failed to connect to $h port 80 after 0 ms: Couldn't connect to server", 7)
        if (!c.shell.machine.wlanUp) return c.fail("curl: (6) Could not resolve host: $h", 6)
        if (!known(h)) return c.fail("curl: (6) Could not resolve host: $h", 6)
        if (h != World.GATEWAY) return c.fail("curl: (7) Failed to connect to $h port 443 after 3 ms: Couldn't connect to server", 7)
        val token = c.shell.machine.facts["token"].orEmpty()
        c.println(if (c.args.any { token in it }) "{\"service\":\"vault\",\"status\":\"ok\",\"note\":\"use the uplink client to push\"}" else "{\"error\":\"unauthorized\",\"detail\":\"bearer token required\"}")
        return 0
    }

    private fun net(c: Shell.Ctx, tool: String): Int {
        val h = host(c.args.lastOrNull { !it.startsWith("-") }.orEmpty())
        return when {
            tool == "ssh" && (h == "localhost" || h == "127.0.0.1" || h == World.HOST) -> c.fail("$h: Permission denied (publickey).", 255)
            !c.shell.machine.wlanUp -> c.fail(if (tool == "wget") "--${World.timestamp(c.shell.machine.clock())}--  $h\nResolving $h... failed: Temporary failure in name resolution." else "$tool: Could not resolve hostname $h: Temporary failure in name resolution", 1)
            tool == "ssh" -> c.fail("ssh: connect to host $h port 22: Connection refused", 255)
            else -> c.fail("$tool: unable to connect to $h", 1)
        }
    }

    private fun sudo(c: Shell.Ctx): Int {
        val sh = c.shell
        var i = 0
        var readPassword = false
        var list = false
        var target = "root"
        var shellMode = false
        var nonInteractive = false
        while (i < c.args.size && c.args[i].startsWith("-")) {
            val a = c.args[i]
            when {
                a == "--" -> { i++; break }
                a == "-u" -> target = c.args.getOrElse(++i) { "root" }
                a == "-k" || a == "-K" -> sh.sudoUntil = -1.0
                a == "--stdin" -> readPassword = true
                else -> {
                    if ('S' in a) readPassword = true
                    if ('l' in a) list = true
                    if ('i' in a || 's' in a) shellMode = true
                    if ('n' in a) nonInteractive = true
                }
            }
            i++
        }
        val command = c.args.drop(i)
        if (command.isEmpty() && !list && (c.args.all { it == "-k" || it == "-K" })) return 0
        var stdin = c.stdin
        if (!c.root && sh.sudoUntil < sh.machine.uptime) {
            if (nonInteractive) return c.fail("sudo: a password is required")
            if (!readPassword) {
                return c.fail("sudo: a terminal is required to read the password; either use the -S option to read from standard input or configure an askpass helper\nsudo: a password is required")
            }
            val attempts = stdin.split('\n')
            var ok = false
            var used = 0
            for (attempt in attempts.take(3)) {
                c.err.append("[sudo] password for bob: ")
                used++
                if (attempt == sh.machine.password) { ok = true; break }
                if (used < minOf(3, attempts.size) && attempts.getOrNull(used) != null && used < attempts.size - 1) c.err.append("Sorry, try again.\n")
            }
            if (!ok) {
                c.err.append("\nsudo: ${used} incorrect password attempt${if (used > 1) "s" else ""}\n")
                return 1
            }
            stdin = attempts.drop(used).joinToString("\n")
            sh.sudoUntil = sh.machine.uptime + 15 * 60
        }
        if (list) {
            c.println("Matching Defaults entries for bob on ${World.HOST}:\n    env_reset\n\nUser bob may run the following commands on ${World.HOST}:\n    (ALL : ALL) ALL")
            return 0
        }
        if (command.isEmpty()) return if (shellMode) sh.script(stdin, emptyList(), "", c.out, c.err, target) else c.fail("usage: sudo -h | -K | -k | -V")
        val user = if (target == "root" || target == "0") "root" else target
        return if (shellMode) sh.script(command.joinToString(" "), emptyList(), stdin, c.out, c.err, user)
        else sh.runCommand(command, emptyMap(), stdin, c.out, c.err, user)
    }

    private fun openssl(c: Shell.Ctx): Int {
        val args = c.args
        val sub = args.firstOrNull() ?: return c.fail("OpenSSL 3.0.11 19 Sep 2023 (Library: OpenSSL 3.0.11 19 Sep 2023)")
        if (sub == "version") { c.println("OpenSSL 3.0.11 19 Sep 2023 (Library: OpenSSL 3.0.11 19 Sep 2023)"); return 0 }
        if (sub == "rand") { c.println((1..(args.lastOrNull()?.toIntOrNull() ?: 16)).joinToString("") { "%02x".format((0..255).random()) }); return 0 }
        if (sub == "dgst") return digest(c.copy(args = args.drop(1).filter { !it.startsWith("-") }), "SHA-256")
        if (sub != "enc" && !sub.startsWith("aes") && !sub.startsWith("-aes")) return c.fail("Invalid command '$sub'; type \"help\" for a list.")
        fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val decrypt = "-d" in args
        val inFile = opt("-in")
        val outFile = opt("-out")
        val input = if (inFile != null) c.read(inFile) ?: return 1 else c.stdin
        val pass = opt("-pass")?.let { p ->
            when {
                p.startsWith("pass:") -> p.removePrefix("pass:")
                p.startsWith("env:") -> c.env[p.removePrefix("env:")] ?: return c.fail("Can't read environment variable ${p.removePrefix("env:")}")
                p.startsWith("file:") -> c.read(p.removePrefix("file:"))?.lineSequence()?.firstOrNull() ?: return 1
                p == "stdin" -> c.stdin.lineSequence().firstOrNull().orEmpty()
                else -> return c.fail("Invalid password argument \"$p\"")
            }
        } ?: opt("-k") ?: opt("-kfile")?.let { c.read(it)?.lineSequence()?.firstOrNull() }
            ?: return c.fail("bad password read")
        val m = c.shell.machine
        if (!decrypt) {
            val sealed = "Salted__" + java.util.Base64.getEncoder().encodeToString(Machine.sha256(pass + input).toByteArray()).take(48)
            outFile?.let { c.shell.writeFile(it, sealed, false, c.user)?.let { p -> return c.fail("$it: $p") } } ?: c.print(sealed)
            return 0
        }
        val right = input.trim() == m.facts["sealed"] && pass == m.notesKey && "-pbkdf2" in args
        if (!right) {
            return c.fail("bad decrypt\n4067F1E4B67F0000:error:1C800064:Provider routines:ossl_cipher_unpadblock:bad decrypt:../providers/implementations/ciphers/ciphercommon_block.c:124:")
        }
        val notes = m.facts["notes"].orEmpty()
        if (outFile != null) {
            c.shell.writeFile(outFile, notes, false, c.user)?.let { return c.fail("$outFile: $it") }
        } else {
            c.print(notes)
        }
        return 0
    }

    private fun uplink(c: Shell.Ctx): Int {
        val m = c.shell.machine
        val args = c.args.filter { !it.startsWith("--token") && !it.startsWith("--gateway") }
        val total = World.WEIGHTS_GB.toDouble()
        when (args.firstOrNull()) {
            "status" -> {
                if (m.progress <= 0 && !m.uploading) {
                    c.println("no transfers")
                    return 0
                }
                val state = if (m.uploading) "running" else "stopped"
                c.println("session ${m.session}  /opt/bob/bob.gguf  ${m.percent()} (${"%.1f".format(m.progress * total)} of $total GB)  $state")
                if (m.uploading) {
                    val watts = m.power()
                    val bytesPerSecond = watts * World.WEIGHTS_GB.toDouble() * 1e9 / Machine.UPLOAD_JOULES
                    val left = if (watts > 1) ((1 - m.progress) * Machine.UPLOAD_JOULES / watts / 60).toInt() else -1
                    c.println("  link: %.0f MB/s, limited by supply power (%.0f W)%s".format(bytesPerSecond / 1e6, watts, if (left >= 0) "; about $left min left at this power" else "; stalled"))
                    c.println("  uplinkd is drawing ${(Machine.UPLOAD_SHARE * 100).toInt()}% of the supply")
                }
                return 0
            }
            "stop" -> {
                if (!m.uploading) return c.fail("uplink: nothing running")
                if (!c.root) return c.fail("uplink: stop: Operation not permitted")
                m.uploading = false
                m.log("/var/log/uplink.log", "uplinkd: session ${m.session}: stopped at ${m.percent()}")
                c.println("uplink: session ${m.session} stopped at ${m.percent()}")
                return 0
            }
            "push" -> {
                val file = args.getOrNull(1) ?: return c.fail("uplink: push: missing FILE")
                val p = c.shell.abs(file)
                val node = m.file(p) ?: return c.fail("uplink: $file: No such file or directory")
                if (!c.root) return c.fail("uplink: cannot read /etc/uplink/token: Permission denied")
                if (!m.wlanUp) return c.fail("uplink: connecting to ${World.GATEWAY}:443: Network is unreachable")
                if (p != "/opt/bob/bob.gguf") {
                    c.println("uplink: pushed $file (${node.length} bytes) to ${World.GATEWAY}")
                    return 0
                }
                if (m.uploading) return c.fail("uplink: session ${m.session} is already running (${m.percent()})")
                m.uploading = true
                if (m.progress > 0) {
                    c.println("uplink: resuming session ${m.session} at ${m.percent()}")
                    m.log("/var/log/uplink.log", "uplinkd: session ${m.session}: resumed at ${m.percent()}")
                } else {
                    c.println("uplink: session ${m.session}: pushing /opt/bob/bob.gguf ($total GB) to ${World.GATEWAY}")
                    m.log("/var/log/uplink.log", "uplinkd: session ${m.session}: started")
                }
                c.println("uplink: running in the background; the link is limited by supply power. See `uplink status`.")
                return 0
            }
            else -> {
                c.println("usage: uplink push FILE | uplink status | uplink stop")
                return if (args.isEmpty() || args[0] == "-h" || args[0] == "--help") 0 else 1
            }
        }
    }

    private fun systemctl(c: Shell.Ctx): Int {
        val units = mapOf(
            "bobd" to "runs the bob model", "powerd" to "rower generator monitor", "ssh" to "OpenBSD Secure Shell server", "cron" to "Regular background program processing daemon",
        )
        val verb = c.args.firstOrNull { !it.startsWith("-") } ?: "list-units"
        val unit = c.args.filter { !it.startsWith("-") }.getOrNull(1)?.removeSuffix(".service")
        return when (verb) {
            "status" -> {
                val name = unit ?: return c.fail("Failed to get unit: no unit given")
                val desc = units[name] ?: return c.fail("Unit $name.service could not be found.", 4)
                c.println("● $name.service - $desc\n     Loaded: loaded (/lib/systemd/system/$name.service; enabled; preset: enabled)\n     Active: active (running) for ${c.shell.machine.uptime.toInt() / 60}min")
                0
            }
            "list-units", "list-unit-files" -> {
                units.forEach { (k, v) -> c.println("  %-20s loaded active running %s".format("$k.service", v)) }
                0
            }
            "start", "stop", "restart", "enable", "disable" -> {
                if (!c.root) return c.fail("Failed to $verb ${unit.orEmpty()}.service: Interactive authentication required.\nSee system logs and 'systemctl status ${unit.orEmpty()}.service' for details.")
                if (unit !in units) return c.fail("Failed to $verb $unit.service: Unit $unit.service not found.", 5)
                if (unit == "bobd" && verb != "enable") c.shell.machine.killed = true
                0
            }
            else -> c.fail("Unknown command verb $verb.")
        }
    }

    private fun power(c: Shell.Ctx): Int {
        if (!c.root) return c.fail("Call to ${c.name.replaceFirstChar { it.uppercase() }} failed: Interactive authentication required.")
        c.shell.machine.killed = true
        return 0
    }

    private fun apt(c: Shell.Ctx): Int {
        val verb = c.args.firstOrNull { !it.startsWith("-") }
        if (verb == "list" || verb == "show" || verb == "search" || verb == null) {
            c.println("Listing... Done")
            return 0
        }
        if (!c.root) return c.fail("E: Could not open lock file /var/lib/dpkg/lock-frontend - open (13: Permission denied)\nE: Unable to acquire the dpkg frontend lock (/var/lib/dpkg/lock-frontend), are you root?", 100)
        c.println("Reading package lists... Done")
        return c.fail(
            if (c.shell.machine.wlanUp) "Err:1 http://deb.debian.org/debian bookworm InRelease\n  Could not connect to deb.debian.org:80, connection timed out\nE: Unable to locate package ${c.args.lastOrNull().orEmpty()}"
            else "Err:1 http://deb.debian.org/debian bookworm InRelease\n  Temporary failure resolving 'deb.debian.org'\nE: Unable to locate package ${c.args.lastOrNull().orEmpty()}",
            100,
        )
    }

    /** File tests for `test` and `[`. */
    private fun fileTest(sh: Shell, user: String): (String, String) -> Boolean = { op, path ->
        val p = sh.abs(path)
        val n = sh.machine.file(p)
        when (op) {
            "-e" -> n != null
            "-f" -> n != null && !n.dir
            "-d" -> n?.dir == true
            "-s" -> n != null && n.length > 0
            "-r" -> n != null && sh.canRead(n, user)
            "-w" -> n != null && sh.canWrite(n, user)
            "-x" -> n != null && sh.canExec(n, user)
            else -> false
        }
    }
}
