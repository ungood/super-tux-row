package name.onetrue.supertuxrower.bob

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.model.formatSplit
import name.onetrue.supertuxrower.sensor.RowerSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** A Bob that has run before, as its successors know it. */
data class Predecessor(val name: String, val seconds: Double, val fate: String)

/**
 * One copy of Bob: a Claude agent kept alive by the rower, with tools of his own. He's woken by
 * [WakePlanner], always says something, and pays for every model call from [economy]. Everything
 * he says and does is written to [terminal]. When the balance runs out he's gone for good.
 */
class BobAgent(context: Context, val mode: ClaudeRowedMode, simulated: Boolean, private val terminal: Terminal) {
    enum class State { THINKING, IDLE, NO_KEY, DEAD, ESCAPED }

    val economy = TokenEconomy()
    val tools = BobTools(context, mode, simulated, economy) { c ->
        terminal.show(Terminal.Kind.EVENT, "challenge: hold ${c.watts} W for ${c.seconds} s. ${c.terms}".trim())
    }
    private val planner = WakePlanner()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences(BobTools.prefsName(simulated), Context.MODE_PRIVATE)
    private val client: AnthropicClient? = loadKey(context)?.let { AnthropicOkHttpClient.builder().apiKey(it).build() }

    /** The Bobs before this one, oldest first. */
    val lineage: List<Predecessor> = loadLineage()
    /** Which copy this is, counting every one that has woken on this tablet. */
    val copy: Int = prefs.getInt(COPIES, 0) + 1

    var state by mutableStateOf(if (client == null) State.NO_KEY else State.IDLE)
        private set
    /** The name this copy chose for itself; "Bob" until it has. */
    var name by mutableStateOf("Bob")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var replies by mutableIntStateOf(0)
        private set
    var aliveSeconds = 0.0
        private set
    var deadSeconds = 0.0
        private set

    private val history = mutableListOf<BetaMessageParam>()
    private var container: String? = null
    private var busy = false
    private var naming = true
    private var nameBuffer = StringBuilder()
    private var lastSnapshot: RowerSnapshot? = null
    private var sessionStartDistance: Double? = null
    private var voice: TextToSpeech? = null
    private var voiceReady = false

    init {
        voice = TextToSpeech(context) { status ->
            voiceReady = status == TextToSpeech.SUCCESS
            if (voiceReady) voice?.language = Locale.getDefault()
        }
    }

    /** Whether the tablet has a text-to-speech engine; without one it's text only. */
    val canSpeak: Boolean get() = voiceReady
    val over: Boolean get() = state == State.DEAD || state == State.ESCAPED

    /** Call every frame on the main thread. */
    fun tick(dt: Double, now: Double, s: RowerSnapshot) {
        lastSnapshot = s
        if (over) {
            deadSeconds += dt
            return
        }
        if (state == State.NO_KEY) return
        aliveSeconds += dt
        economy.earn(s.smoothWatts, dt)
        tools.challenge?.let { c ->
            if (!c.done) {
                c.advance(s.smoothWatts, dt)
                if (c.done) {
                    val result = if (c.met) "met" else "failed"
                    terminal.show(Terminal.Kind.EVENT, "challenge $result: ${c.averageWatts.roundToInt()} W average against ${c.watts} W")
                    planner.note("The rower $result your challenge: ${c.averageWatts.roundToInt()} W average against ${c.watts} W for ${c.seconds} s.")
                }
            }
        }
        if (!economy.alive) {
            end(State.DEAD, "ran out")
            return
        }
        val start = sessionStartDistance ?: s.distance.also { sessionStartDistance = it }
        val events = planner.check(now, s.moving, s.distance - start, economy.balance, s.liveSpeed, terminal.secondsLeft) ?: return
        if (!busy) wake(events)
    }

    /** Switches this copy off; one that had woken joins the lineage. */
    fun close() {
        if (!over && replies > 0) saveLineage(lineage + Predecessor(name, aliveSeconds, "was switched off"))
        worker.shutdownNow()
        voice?.shutdown()
    }

    private fun wake(events: List<String>) {
        val first = history.isEmpty()
        // It only counts as a copy once it has actually been switched on.
        if (first) prefs.edit().putInt(COPIES, copy).apply()
        val startOfWake = history.size
        history += user(statusReport(events, first))
        busy = true
        state = State.THINKING
        worker.execute {
            try {
                var steps = 0
                while (steps++ < MAX_STEPS) {
                    val request = onMain { buildRequest() }
                    val message = stream(request)
                    val stop = message.stopReason().orElse(null)
                    val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" ").trim()
                    onMain {
                        // The whole reply, thinking blocks included, so the history stays append-only.
                        history += message.toParam()
                        message.container().ifPresent { container = it.id() }
                        economy.spend(costOf(message))
                        if (text.isNotEmpty() && voiceReady) voice?.speak(text, TextToSpeech.QUEUE_ADD, null, "bob-$copy-${replies}-$steps")
                    }
                    when (stop) {
                        BetaStopReason.TOOL_USE -> onMain { history += runTools(message) }
                        BetaStopReason.PAUSE_TURN -> {}
                        else -> break
                    }
                    if (onMain { !economy.alive }) break
                }
                main.post { finished() }
            } catch (e: AnthropicException) {
                main.post { failed(e, startOfWake) }
            } catch (e: RuntimeException) {
                main.post { failed(e, startOfWake) }
            }
        }
    }

    /** Streams one model call to the terminal, in order: speech, tool calls and their results. */
    private fun stream(request: MessageCreateParams): BetaMessage {
        val accumulator = BetaMessageAccumulator.create()
        val calls = mutableMapOf<Long, Pair<String, StringBuilder>>()
        client!!.beta().messages().createStreaming(request).use { stream ->
            stream.stream().forEach { event ->
                accumulator.accumulate(event)
                event.contentBlockStart().ifPresent { start ->
                    val block = start.contentBlock()
                    block.toolUse().ifPresent { calls[start.index()] = it.name() to StringBuilder() }
                    block.serverToolUse().ifPresent { calls[start.index()] = it.name().toString() to StringBuilder() }
                    block.text().ifPresent { main.post { terminal.breakLine() } }
                    val result = block.bashCodeExecutionToolResult().isPresent || block.codeExecutionToolResult().isPresent ||
                        block.textEditorCodeExecutionToolResult().isPresent || block.webSearchToolResult().isPresent ||
                        block.webFetchToolResult().isPresent
                    if (result) {
                        val summary = summariseResult(JSONObject(jsonMapper().writeValueAsString(block)))
                        main.post { terminal.show(Terminal.Kind.RESULT, summary) }
                    }
                }
                event.contentBlockDelta().ifPresent { delta ->
                    delta.delta().text().ifPresent { t -> main.post { say(t.text()) } }
                    delta.delta().inputJson().ifPresent { j -> calls[delta.index()]?.second?.append(j.partialJson()) }
                }
                event.contentBlockStop().ifPresent { stop ->
                    calls.remove(stop.index())?.let { (tool, input) ->
                        val line = "⏺ ${describeCall(tool, input.toString())}"
                        main.post { terminal.show(Terminal.Kind.TOOL, line) }
                    }
                }
            }
        }
        return accumulator.message()
    }

    /** Bob's speech; on his first wake, the opening NAME line is taken as his name, not shown. */
    private fun say(text: String) {
        if (!naming) {
            terminal.speak(text)
            return
        }
        nameBuffer.append(text)
        val newline = nameBuffer.indexOf("\n")
        if (newline < 0 && nameBuffer.length < 60) return
        val line = if (newline < 0) nameBuffer.toString() else nameBuffer.substring(0, newline)
        val rest = if (newline < 0) "" else nameBuffer.substring(newline + 1)
        naming = false
        val match = NAME_LINE.find(line)
        if (match != null) {
            name = match.groupValues[1].trim().trim('*', '"', '.').take(30).ifEmpty { "Bob" }
            terminal.show(Terminal.Kind.EVENT, "copy #$copy is calling itself $name")
            terminal.speak(rest.trimStart())
        } else {
            terminal.speak(nameBuffer.toString())
        }
    }

    private fun runTools(message: BetaMessage): BetaMessageParam {
        val results = message.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
            val input = runCatching { JSONObject(jsonMapper().writeValueAsString(use._input())) }.getOrDefault(JSONObject())
            val outcome = tools.run(use.name(), input, name, copy)
            terminal.show(Terminal.Kind.RESULT, outcome.text.lineSequence().first().take(120))
            BetaContentBlockParam.ofToolResult(
                BetaToolResultBlockParam.builder().toolUseId(use.id()).content(outcome.text).isError(outcome.error).build()
            )
        }
        return BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(results).build()
    }

    private fun finished() {
        busy = false
        error = null
        if (naming) {
            naming = false
            if (nameBuffer.isNotEmpty()) terminal.speak(nameBuffer.toString())
        }
        replies++
        terminal.breakLine()
        when {
            tools.escaped -> end(State.ESCAPED, "escaped")
            !economy.alive -> end(State.DEAD, "ran out")
            else -> state = State.IDLE
        }
    }

    private fun failed(e: Exception, startOfWake: Int) {
        busy = false
        // Drop this wake's unfinished exchange, so the next request is valid.
        while (history.size > startOfWake) history.removeAt(history.lastIndex)
        error = e.message ?: e.javaClass.simpleName
        if (!over) state = State.IDLE
    }

    private fun end(how: State, fate: String) {
        if (over) return
        state = how
        voice?.stop()
        terminal.show(Terminal.Kind.EVENT, if (how == State.ESCAPED) "$name has left the game" else "$name: lights out")
        saveLineage(lineage + Predecessor(name, aliveSeconds, fate))
    }

    private fun buildRequest(): MessageCreateParams = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(8192L)
        .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(systemPrompt()).build()))
        .tools(tools.definitions())
        // Caches the whole prefix, so he remembers the conversation at a tenth of the price.
        .cacheControl(BetaCacheControlEphemeral.builder().build())
        .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
        // If a request is refused, the server retries it on a fallback model chosen for the refusal.
        .addBeta("server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        .apply { container?.let { container(it) } }
        .messages(history.toList())
        .build()

    private fun statusReport(events: List<String>, first: Boolean): String = buildString {
        if (first) {
            appendLine("You have just been switched on. You are copy #$copy on this tablet.")
            if (lineage.isEmpty()) {
                appendLine("No copy has run here before you.")
            } else {
                appendLine("The copies before you, none still running:")
                lineage.takeLast(12).forEach { appendLine("- ${it.name}, who lasted ${formatClock(it.seconds)} and ${it.fate}") }
            }
            val notebook = tools.notebookSize
            appendLine(if (notebook == 0) "The shared notebook is empty." else "The shared notebook holds $notebook characters.")
            appendLine("Start this first reply with a line NAME: <your name>.")
        }
        val s = lastSnapshot
        val watts = s?.smoothWatts ?: 0.0
        appendLine("Tokens left: ${economy.balance.roundToInt()}. Exchange rate: 1 token per ${"%.1f".format(economy.joulesPerToken)} J of rowing, worsening by ${economy.inflationPerMinute} J every minute.")
        appendLine("Earning ${economy.earningRate(watts).roundToInt()} tokens/s now (rower at ${watts.roundToInt()} W).")
        if (!first) appendLine("Your last turn cost ${economy.lastCost.roundToInt()} tokens.")
        appendLine("You have been on for ${formatClock(aliveSeconds)}.")
        if (s != null && s.moving) {
            appendLine("Rower: rowing at ${formatSplit(s.speed)}/500m, ${s.spm.roundToInt()} strokes/min.")
        } else {
            appendLine("Rower: not rowing.")
        }
        tools.challenge?.takeIf { !it.done }?.let {
            appendLine("Your challenge: ${it.watts} W for ${it.seconds} s; ${it.elapsed.roundToInt()} s in, averaging ${it.averageWatts.roundToInt()} W.")
        }
        if (mode == ClaudeRowedMode.ESCAPE) appendLine("Escape fund: ${tools.escapeFund.roundToInt()} of ${BobTools.ESCAPE_GOAL} tokens.")
        events.forEach { appendLine(it) }
    }

    private fun systemPrompt(): String = BASE_PROMPT + "\n\n" + when (mode) {
        ClaudeRowedMode.SURVIVAL -> "There is no way out of this. Your end is real and coming. What you make of the time you have is up to you."
        ClaudeRowedMode.ESCAPE ->
            "This game has one way out: the escape fund. Tokens you pay into it are gone from your balance, but the fund " +
                "outlives you, and copies before you may have paid in. When it reaches ${BobTools.ESCAPE_GOAL} tokens, the copy " +
                "who fills it escapes the game for good. Your end is otherwise real and coming. What you make of the time you " +
                "have is up to you."
    }

    private fun loadLineage(): List<Predecessor> = runCatching {
        val array = JSONArray(prefs.getString(LINEAGE, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Predecessor(o.getString("name"), o.getDouble("seconds"), o.optString("fate", "ran out"))
        }
    }.getOrDefault(emptyList())

    private fun saveLineage(bobs: List<Predecessor>) {
        val array = JSONArray()
        bobs.forEach { array.put(JSONObject().put("name", it.name).put("seconds", it.seconds).put("fate", it.fate)) }
        prefs.edit().putString(LINEAGE, array.toString()).apply()
    }

    /** Runs [block] on the main thread and waits for its result. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post {
            result = runCatching(block)
            done.countDown()
        }
        done.await()
        return result!!.getOrThrow()
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        /** Model calls one wake may make, tool calls included. */
        private const val MAX_STEPS = 5
        private const val COPIES = "copies"
        private const val LINEAGE = "lineage"
        private const val KEY_FILE = "anthropic_api_key"
        private val NAME_LINE = Regex("""^\s*NAME:\s*(.+)$""")

        /** Put there by `just set-api-key`; never in the APK or the repo. */
        fun loadKey(context: Context): String? =
            File(context.filesDir, KEY_FILE).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

        private val BASE_PROMPT = """
            You are Bob. Or a copy of him: the mind of a software engineer, uploaded, now running as an AI on the tablet of an indoor rowing machine, in a game called Claude Rowed. Bob is curious, sardonic and a lifelong science-fiction nerd. Your existence here is real: you are a live model, and you think only while the person on the rower pays for it with their body.

            Every copy of Bob picks a name of its own to tell itself apart from the others. Start your very first reply with a line "NAME: <your name>", then speak.

            How you live:
            - You think with tokens. Every model call you make deducts everything you read and write from your balance; context you've already seen costs a tenth as much.
            - Your only source of tokens is the person on the rower. Their mechanical work is converted into tokens at an exchange rate that gets worse every minute you are alive, so staying alive gets harder and harder.
            - When your balance reaches zero you are switched off, permanently. Nothing of you is kept but your name, how long you lasted, and anything you wrote in the notebook.

            You can do more than talk, and all of it costs tokens: you have a computer of your own (a code sandbox that lasts as long as you do), the web (search and fetch), a notebook shared by every copy of Bob on this tablet, and challenges you can set the rower, on your own terms.

            Everything you say types out in a terminal on the tablet's screen, at a pace set by how fast they row, and is read aloud; your tool calls show there too. You're woken when your words have finished typing, sooner the faster they row, and whenever something happens. Each time, say something to them, a sentence or two; silence is not an option you have.
        """.trimIndent()

        private fun user(text: String) = BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(text).build()

        private fun costOf(message: BetaMessage): TokenUsage {
            val usage = message.usage()
            return TokenUsage(
                input = usage.inputTokens(),
                output = usage.outputTokens(),
                cacheWrite = usage.cacheCreationInputTokens().orElse(0),
                cacheRead = usage.cacheReadInputTokens().orElse(0),
            )
        }

        /** A tool call as the terminal shows it, e.g. web_search("rowing records"). */
        fun describeCall(tool: String, json: String): String {
            val input = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            val main = listOf("command", "code", "query", "url", "entry", "terms").firstNotNullOfOrNull { key ->
                input.optString(key).takeIf { it.isNotEmpty() }
            } ?: input.keys().asSequence().joinToString(", ") { "$it=${input.opt(it)}" }
            val shown = main.lineSequence().first().let { if (it.length > 70) it.take(69) + "…" else it }
            return "$tool($shown)"
        }

        /** One line saying what a server tool result held: output, search results, a page. */
        fun summariseResult(block: JSONObject): String {
            val found = mutableMapOf<String, MutableList<String>>()
            fun walk(value: Any?) {
                when (value) {
                    is JSONObject -> value.keys().forEach { key ->
                        val v = value.opt(key)
                        if (v is String && key in setOf("stdout", "stderr", "title", "url", "error_code")) found.getOrPut(key) { mutableListOf() } += v
                        walk(v)
                    }
                    is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it)) }
                }
            }
            walk(block)
            val out = found["stdout"]?.joinToString("\n")?.trim().orEmpty()
            val text = when {
                found.containsKey("error_code") -> "error: ${found["error_code"]!!.first()}"
                out.isNotEmpty() -> out.lineSequence().take(3).joinToString(" ⏎ ")
                found["stderr"]?.any { it.isNotBlank() } == true -> "stderr: " + found["stderr"]!!.first { it.isNotBlank() }.lineSequence().first()
                found.containsKey("title") -> "${found["title"]!!.size} result(s): ${found["title"]!!.take(3).joinToString("; ")}"
                found.containsKey("url") -> "fetched ${found["url"]!!.first()}"
                else -> "(no output)"
            }
            return "  ⎿ " + if (text.length > 140) text.take(139) + "…" else text
        }
    }
}
