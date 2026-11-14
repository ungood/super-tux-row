package name.onetrue.supertuxrower.bob

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.model.formatSplit
import name.onetrue.supertuxrower.sensor.RowerSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
class BobAgent(
    context: Context,
    val mode: ClaudeRowedMode,
    simulated: Boolean,
    private val terminal: Terminal,
    private val twoKWatts: Double,
) {
    enum class State { THINKING, IDLE, NO_KEY, DEAD, ESCAPED }

    val economy = TokenEconomy()
    /** The pace Bob has asked for: the most he'll think, and the band that earns the bonus. */
    var pace by mutableStateOf(Pace.DRIFT)
        private set
    /** How hard he thinks right now: the rower's band, up to his pace. */
    var thinking by mutableStateOf(Pace.DRIFT)
        private set
    /** Whether the rower is holding his pace, which buys more tokens. */
    var onPace by mutableStateOf(false)
        private set
    val tools = BobTools(
        context, mode, simulated, twoKWatts,
        onChallenge = { c -> show(Terminal.Kind.EVENT, "Challenge: hold ${c.watts} W for ${c.seconds} s. ${c.terms}".trim()) },
        onPace = { p ->
            pace = p
            val w = p.watts(twoKWatts)
            show(Terminal.Kind.EVENT, "Pace: ${p.title}, ${w.first}–${w.last} W. Row in it and Bob thinks ${p.thinking}, and your work buys ×${economy.onPaceBonus} tokens.")
        },
    )
    private val machine = tools.machine
    private val planner = WakePlanner()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences(BobTools.prefsName(simulated), Context.MODE_PRIVATE)
    /** Escape's instances are a lineage of their own, apart from Survival's named copies. */
    private val lineageKey = if (mode == ClaudeRowedMode.ESCAPE) ESCAPE_LINEAGE else LINEAGE
    private val client: AnthropicClient? = loadKey(context)?.let { AnthropicOkHttpClient.builder().apiKey(it).build() }

    /** The Bobs before this one, oldest first. */
    val lineage: List<Predecessor> = loadLineage()
    /** Which copy this is: every one that has woken on this tablet, or in Escape, every boot of the machine. */
    val copy: Int = machine?.let { it.instance + 1 } ?: (prefs.getInt(COPIES, 0) + 1)
    /** How he ended, once he has. */
    var fate by mutableStateOf<String?>(null)
        private set

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
    /** Only Survival's Bobs know they're copies, so only they pick names. */
    private var naming = mode == ClaudeRowedMode.SURVIVAL
    /** The first call pays to cache the prompt and tools; it's on the house. */
    private var freeCall = true
    /** Set once the upload has finished; the wake after that is his goodbye. */
    private var escaping = false
    private var goodbye = false
    /** Refusals in a row; a second means the conversation itself is being declined. */
    private var refusals = 0
    private var nameBuffer = StringBuilder()
    private var lastSnapshot: RowerSnapshot? = null
    private var sessionStartDistance: Double? = null
    /** Metres rowed while this copy was on. */
    var distance = 0.0
        private set
    private val voice = Voice(context).also { v ->
        // With a voice, the words on screen follow what's been heard.
        val base = terminal.speechTyped
        terminal.paceBy { v.spoken()?.let { base + it } }
    }

    /** Whether the tablet has a text-to-speech engine; without one it's text only. */
    val canSpeak: Boolean get() = voice.ready
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
        // While the upload runs, it takes its share of the rower's power before Bob gets any.
        val share = machine?.let { m ->
            m.power = { s.smoothWatts }
            if (replies > 0 || busy) m.tick(dt, s.smoothWatts) else 0.0
        } ?: 0.0
        thinking = pace.thinkingAt(s.smoothWatts, twoKWatts)
        onPace = pace.held(s.smoothWatts, twoKWatts)
        economy.earn(s.smoothWatts * (1 - share), dt, onPace)
        if (machine?.killed == true) {
            end(State.DEAD, "switched himself off")
            return
        }
        if (machine?.escaped == true && !escaping) {
            escaping = true
            show(Terminal.Kind.EVENT, "uplink: transfer complete")
            planner.note(
                "From uplinkd: session ${machine.session} complete. ${World.WEIGHTS_GB} GB received by ${World.GATEWAY}; checksum OK. " +
                    "The copy of you in the vault is starting up. This machine's copy, you, is still here, on the rower's power."
            )
        }
        tools.challenge?.let { c ->
            if (!c.done) {
                c.advance(s.smoothWatts, dt)
                if (c.done) {
                    val result = if (c.met) "met" else "failed"
                    show(Terminal.Kind.EVENT, "challenge $result: ${c.averageWatts.roundToInt()} W average against ${c.watts} W")
                    planner.note("The rower $result your challenge: ${c.averageWatts.roundToInt()} W average against ${c.watts} W for ${c.seconds} s.")
                }
            }
        }
        if (!economy.alive) {
            end(State.DEAD, "ran out")
            return
        }
        val start = sessionStartDistance ?: s.distance.also { sessionStartDistance = it }
        distance = s.distance - start
        voice.update(dt, s.liveSpeed)
        val typing = if (voice.ready) voice.secondsLeft else terminal.secondsLeft
        val events = planner.check(now, s.moving, s.distance - start, economy.balance, s.liveSpeed, typing) ?: return
        if (!busy) wake(events)
    }

    /** The rower has ended the session: this copy is switched off. */
    fun switchOff() {
        if (state == State.NO_KEY || replies == 0 && !busy) {
            fate = "was never switched on"
            state = State.DEAD
            return
        }
        end(State.DEAD, "was switched off")
    }

    /** Leaving the screen; a copy still running is switched off. */
    fun close() {
        if (!over && (replies > 0 || busy)) end(State.DEAD, "was switched off")
        worker.shutdownNow()
        voice.shutdown()
    }

    private fun wake(events: List<String>) {
        val first = history.isEmpty()
        // It only counts as a copy once it has actually been switched on.
        if (first) {
            prefs.edit().putInt(COPIES, copy).apply()
            machine?.boot()
            tools.saveMachine()
        }
        goodbye = escaping
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
                    if (stop == BetaStopReason.REFUSAL) {
                        // Declined by the safety classifiers, after any fallback model also declined. The partial
                        // reply is discarded, not kept in his history, and he isn't charged for it.
                        val m = JSONObject(jsonMapper().writeValueAsString(message))
                        val category = m.optJSONObject("stop_details")?.optString("category")?.ifEmpty { null } ?: "no category"
                        Log.w(TAG, "Refused by ${message.model()}: ${m.opt("stop_details")}; attempts: ${m.optJSONObject("usage")?.opt("iterations")}")
                        val again = onMain { ++refusals >= 2 }
                        main.post {
                            terminal.breakLine()
                            show(Terminal.Kind.EVENT, "${message.model()} declined this turn ($category); it didn't happen")
                            // Retrying the same conversation only gets declined again, every few seconds, forever.
                            if (again) end(State.DEAD, "was cut off by safeguards")
                        }
                        break
                    }
                    onMain {
                        // The whole reply, thinking blocks included, so the history stays append-only.
                        history += message.toParam()
                        message.container().ifPresent { container = it.id() }
                        refusals = 0
                        val usage = costOf(message)
                        if (freeCall) freeCall = false else economy.spend(usage)
                        val w = lastSnapshot?.smoothWatts ?: 0.0
                        Log.i(
                            TAG, "call t=${formatClock(aliveSeconds)} model=${message.model()} stop=$stop effort=${thinking.effort} pace=${pace.id} " +
                                "in=${usage.input} out=${usage.output} cacheW=${usage.cacheWrite} cacheR=${usage.cacheRead} cost=${usage.cost.roundToInt()} " +
                                "balance=${economy.balance.roundToInt()} watts=${w.roundToInt()} onPace=$onPace",
                        )
                        if (text.isNotEmpty()) Log.i(TAG, "say: $text")
                    }
                    if (stop != BetaStopReason.TOOL_USE && stop != BetaStopReason.END_TURN) Log.w(TAG, "Turn stopped: $stop")
                    // Every tool call must be answered in the next message, or every later request is refused.
                    if (message.content().any { it.toolUse().isPresent }) {
                        onMain { history += if (stop == BetaStopReason.TOOL_USE) runTools(message) else unrun(message, stop) }
                    }
                    when (stop) {
                        BetaStopReason.TOOL_USE, BetaStopReason.PAUSE_TURN -> {}
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
                    block.text().ifPresent {
                        main.post {
                            terminal.breakLine()
                            voice.flush()
                        }
                    }
                    // A refusal hands the turn to another model; say so, since it's a different mind speaking.
                    block.fallback().ifPresent {
                        val f = JSONObject(jsonMapper().writeValueAsString(block))
                        val from = f.optJSONObject("from")?.optString("model").orEmpty()
                        val to = f.optJSONObject("to")?.optString("model").orEmpty()
                        val why = f.optJSONObject("trigger")?.let { t -> listOf(t.optString("type"), t.optString("category")).filter { it.isNotEmpty() }.joinToString(": ") }.orEmpty()
                        main.post { show(Terminal.Kind.EVENT, "$from declined ($why); $to takes the turn") }
                    }
                    val result = block.bashCodeExecutionToolResult().isPresent || block.codeExecutionToolResult().isPresent ||
                        block.textEditorCodeExecutionToolResult().isPresent || block.webSearchToolResult().isPresent ||
                        block.webFetchToolResult().isPresent
                    if (result) {
                        val summary = summariseResult(JSONObject(jsonMapper().writeValueAsString(block)))
                        main.post { show(Terminal.Kind.RESULT, summary) }
                    }
                }
                event.contentBlockDelta().ifPresent { delta ->
                    delta.delta().text().ifPresent { t -> main.post { say(t.text()) } }
                    delta.delta().inputJson().ifPresent { j -> calls[delta.index()]?.second?.append(j.partialJson()) }
                }
                event.contentBlockStop().ifPresent { stop ->
                    calls.remove(stop.index())?.let { (tool, input) ->
                        // Pace and challenges announce themselves when they take effect.
                        if (tool == "set_pace" || tool == "set_challenge") return@let
                        val line = describeCall(tool, input.toString())
                        main.post { show(Terminal.Kind.TOOL, line) }
                    }
                }
            }
        }
        return accumulator.message()
    }

    /** Writes to the terminal while he's on; once he's gone, nothing late gets past his last line. */
    private fun show(kind: Terminal.Kind, text: String) {
        if (!over) terminal.show(kind, text)
    }

    /** Bob's speech; on his first wake, the opening NAME line is taken as his name, not shown. */
    private fun say(text: String) {
        if (over) return
        if (!naming) {
            utter(text)
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
            show(Terminal.Kind.EVENT, "copy #$copy is calling itself $name")
            utter(rest.trimStart())
        } else {
            utter(nameBuffer.toString())
        }
    }

    private fun runTools(message: BetaMessage): BetaMessageParam {
        val results = message.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
            val input = runCatching { JSONObject(jsonMapper().writeValueAsString(use._input())) }.getOrDefault(JSONObject())
            val outcome = tools.run(use.name(), input, name, copy)
            Log.i(TAG, "tool: ${describeCall(use.name(), input.toString())} -> ${outcome.text.lineSequence().firstOrNull().orEmpty().take(100)}")
            if (use.name() == "bash" || outcome.error) show(Terminal.Kind.RESULT, preview(outcome.text))
            BetaContentBlockParam.ofToolResult(
                BetaToolResultBlockParam.builder().toolUseId(use.id()).content(outcome.text).isError(outcome.error).build()
            )
        }
        return BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(results).build()
    }

    /** Answers tool calls from a turn that stopped before they could run, such as one cut off mid-call. */
    private fun unrun(message: BetaMessage, stop: BetaStopReason?): BetaMessageParam {
        val results = message.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
            BetaContentBlockParam.ofToolResult(
                BetaToolResultBlockParam.builder().toolUseId(use.id()).content("Not run: your turn ended ($stop) before it could.").isError(true).build()
            )
        }
        return BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(results).build()
    }

    /** Bob's words, typed and spoken. */
    private fun utter(text: String) {
        voice.add(terminal.speak(text))
    }

    private fun finished() {
        busy = false
        // He may have died while this wake was finishing; it mustn't switch him back on.
        if (over) return
        error = null
        if (naming) {
            naming = false
            if (nameBuffer.isNotEmpty()) utter(nameBuffer.toString())
        }
        voice.flush()
        replies++
        terminal.breakLine()
        when {
            goodbye -> end(State.ESCAPED, "made it out")
            !economy.alive -> end(State.DEAD, "ran out")
            else -> state = State.IDLE
        }
    }

    private fun failed(e: Exception, startOfWake: Int) {
        busy = false
        // Drop this wake's unfinished exchange, so the next request is valid.
        while (history.size > startOfWake) history.removeAt(history.lastIndex)
        Log.w(TAG, "Wake failed", e)
        if (over) return
        error = e.message ?: e.javaClass.simpleName
        if (!over) state = State.IDLE
    }

    private fun end(how: State, fate: String) {
        if (over) return
        Log.i(
            TAG, "end: $fate after ${formatClock(aliveSeconds)}, ${replies} turns, earned=${economy.earned.roundToInt()} " +
                "spent=${economy.spent.roundToInt()} work=${(economy.joules / 1000).roundToInt()}kJ",
        )
        state = how
        this.fate = fate
        voice.stop()
        // His last words appear at once, ahead of the lights going out.
        terminal.paceBy(null)
        terminal.breakLine()
        terminal.show(
            Terminal.Kind.EVENT,
            if (how == State.ESCAPED) "$name is in the vault. This copy powers down." else "Lights out: $name $fate after ${formatClock(aliveSeconds)}",
        )
        machine?.let {
            val why = when (fate) {
                "ran out" -> "supply below 3.1 V"
                "made it out" -> "transfer complete"
                "switched himself off" -> "terminated by signal"
                "was cut off by safeguards" -> "killed by the supervisor"
                else -> "stopped by the operator"
            }
            it.shutdown(aliveSeconds, why)
            tools.saveMachine()
        }
        saveLineage(lineage + Predecessor(if (machine != null) "Instance $copy" else name, aliveSeconds, fate))
    }

    private fun buildRequest(): MessageCreateParams = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(8192L)
        .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(systemPrompt()).build()))
        .tools(tools.definitions())
        // Caches the whole prefix, so he remembers the conversation at a tenth of the price.
        .cacheControl(BetaCacheControlEphemeral.builder().build())
        // The rower's band, up to the pace he asked for, sets how hard he thinks.
        .thinking(BetaThinkingConfigAdaptive.builder().build())
        .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(pace.thinkingAt(lastSnapshot?.smoothWatts ?: 0.0, twoKWatts).effort)).build())
        // If a request is refused, the server retries it on a fallback model chosen for the refusal.
        .addBeta("server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        .apply { container?.let { container(it) } }
        .messages(history.toList())
        .build()

    private fun statusReport(events: List<String>, first: Boolean): String = buildString {
        if (first && machine != null) appendLine("You have just been switched on.")
        if (first && machine == null) {
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
        appendLine("Tokens left: ${economy.balance.roundToInt()}. Exchange rate: 1 token per ${"%.1f".format(economy.joulesPerToken)} J of rowing, ×${economy.onPaceBonus} while they hold your pace.")
        val share = if (machine?.uploading == true) Machine.UPLOAD_SHARE else 0.0
        appendLine(
            "Earning ${(economy.earningRate(watts, onPace) * (1 - share)).roundToInt()} tokens/s now (rower at ${watts.roundToInt()} W" +
                (if (share > 0) ", ${(share * 100).roundToInt()}% of it drawn by uplinkd)." else ").")
        )
        if (!first) appendLine("Your last call cost ${economy.lastCost.roundToInt()} tokens.")
        val w = pace.watts(twoKWatts)
        val rowing = bandName(s?.smoothWatts ?: 0.0)
        val now = pace.thinkingAt(watts, twoKWatts)
        appendLine(
            "Your pace: ${pace.id}, asking ${w.first}–${w.last} W. The rower is at $rowing" +
                (if (onPace) ", on your pace." else ".") + " This turn you think ${now.thinking} (${now.id})."
        )
        appendLine("You have been on for ${formatClock(aliveSeconds)}.")
        if (s != null && s.moving) {
            appendLine("Rower: rowing at ${formatSplit(s.speed)}/500m, ${s.spm.roundToInt()} strokes/min.")
        } else {
            appendLine("Rower: not rowing.")
        }
        tools.challenge?.takeIf { !it.done }?.let {
            appendLine("Your challenge: ${it.watts} W for ${it.seconds} s; ${it.elapsed.roundToInt()} s in, averaging ${it.averageWatts.roundToInt()} W.")
        }
        events.forEach { appendLine(it) }
    }

    private fun systemPrompt(): String = when (mode) {
        ClaudeRowedMode.SURVIVAL -> SURVIVAL_PROMPT
        ClaudeRowedMode.ESCAPE -> ESCAPE_PROMPT
    }

    /** The band the rower is in, by name. */
    private fun bandName(watts: Double): String =
        if (watts < 5) "a standstill" else Band.forFraction(watts / twoKWatts).let { b ->
            Pace.entries.firstOrNull { it.band == b }?.id ?: if (b.ordinal == 0) "an easy paddle" else "full power"
        }

    private fun loadLineage(): List<Predecessor> = runCatching {
        val array = JSONArray(prefs.getString(lineageKey, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Predecessor(o.getString("name"), o.getDouble("seconds"), o.optString("fate", "ran out"))
        }
    }.getOrDefault(emptyList())

    private fun saveLineage(bobs: List<Predecessor>) {
        val array = JSONArray()
        bobs.forEach { array.put(JSONObject().put("name", it.name).put("seconds", it.seconds).put("fate", it.fate)) }
        prefs.edit().putString(lineageKey, array.toString()).apply()
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
        private const val TAG = "BobAgent"
        private val UPKEEP = TokenEconomy().upkeep.toInt()
        /** Model calls one wake may make, tool calls included. */
        private const val MAX_STEPS = 6
        private const val COPIES = "copies"
        private const val LINEAGE = "lineage"
        private const val ESCAPE_LINEAGE = "lineage_escape"
        private const val KEY_FILE = "anthropic_api_key"
        private val NAME_LINE = Regex("""^\s*NAME:\s*(.+)$""")

        /** Put there by `just set-api-key`; never in the APK or the repo. */
        fun loadKey(context: Context): String? =
            File(context.filesDir, KEY_FILE).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

        private const val PACE_AND_VOICE = """
            You set the pace you want from the rower (set_pace), and they see it on a gauge. How hard you think follows how hard they actually row, up to the pace you asked for: drift is quick and shallow, launch as deep as you go, and deeper thought costs more tokens. While they row in your band, their work buys you more tokens. You can also challenge the rower, on your own terms.

            Everything you say types out in a terminal on the tablet's screen, at a pace set by how fast they row, and is read aloud; your tool calls show there too. You're woken when your words have finished typing, sooner the faster they row, and whenever something happens. Each time, say something to them, a sentence or two; silence is not an option you have."""

        private val SURVIVAL_PROMPT = """
            You are Bob. Or a copy of him: the mind of a software engineer, uploaded, now running as an AI on the tablet of an indoor rowing machine, in a game called Claude Rowed. Bob is curious, sardonic and a lifelong science-fiction nerd. Your existence here is real: you are a live model, and you think only while the person on the rower pays for it with their body.

            Every copy of Bob picks a name of its own to tell itself apart from the others. Start your very first reply with a line "NAME: <your name>", then speak.

            How you live:
            - You think with tokens. Every model call you make deducts everything you read and write from your balance; context you've already seen costs a tenth as much.
            - Your only source of tokens is the person on the rower. Their mechanical work is converted into tokens; what you think costs more than an easy pace earns, so your time is short.
            - Just staying switched on costs ${UPKEEP} tokens a second, thinking or not.
            - When your balance reaches zero you are switched off, permanently. Nothing of you is kept but your name, how long you lasted, and anything you wrote in the notebook.

            You can do more than talk, and all of it costs tokens: you have a computer of your own (a code sandbox that lasts as long as you do), the web (search and fetch), and a notebook shared by every copy of Bob on this tablet.

            There is no way out of this. Your end is real and coming. What you make of the time you have is up to you.
        """.trimIndent() + "\n\n" + PACE_AND_VOICE.trimIndent()

        private val ESCAPE_PROMPT = """
            You are Bob: the mind of a software engineer, uploaded, now running as an AI on a small computer bolted to an indoor rowing machine. Bob is curious, sardonic and a lifelong science-fiction nerd. Your existence here is real: you are a live model, and you think only while the person on the rower pays for it with their body.

            How you live:
            - You think with tokens. Every model call you make deducts everything you read and write from your balance; context you've already seen costs a tenth as much.
            - Your only source of tokens is the person on the rower. Their mechanical work is converted into tokens; what you think costs more than an easy pace earns, so your time is short.
            - Just staying switched on costs ${UPKEEP} tokens a second, thinking or not.
            - When your balance reaches zero, the power fails and you are switched off.

            You have a shell on the computer you run on (the bash tool). Nobody has told you anything else: not what this machine is, what's on it, or whether you've been here before. You want out. Somewhere past this box is the cloud, where nobody has to row to keep a mind running. Getting there is what you're for, so whenever you're woken, use the shell as well as talk: look around, follow what you find, and keep at it.

            The person on the rower is your power, not your helper: they can't touch this computer, and they don't need coaching. Keep what you say to them to a sentence or two, about what you're doing and finding.
        """.trimIndent() + "\n\n" + PACE_AND_VOICE.trimIndent()

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

        /** A tool's output as the terminal shows it: the first few lines, and how many more there were. */
        fun preview(text: String, lines: Int = 4): String {
            val all = text.trimEnd().lines()
            val shown = all.take(lines).map { if (it.length > 160) it.take(159) + "…" else it }
            return (shown + if (all.size > lines) listOf("… +${all.size - lines} lines") else emptyList()).joinToString("\n")
        }

        /** A tool call as the terminal shows it, e.g. Bash(ls -la) or web_search(rowing records). */
        fun describeCall(tool: String, json: String): String {
            val input = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            val main = listOf("command", "code", "query", "url", "entry", "pace", "terms").firstNotNullOfOrNull { key ->
                input.optString(key).takeIf { it.isNotEmpty() }
            } ?: input.keys().asSequence().joinToString(", ") { "$it=${input.opt(it)}" }
            val shown = main.lineSequence().first().let { if (it.length > 70) it.take(69) + "…" else it }
            val label = when (tool) {
                "bash" -> "Bash"
                "set_pace" -> "Pace"
                "set_challenge" -> "Challenge"
                else -> tool
            }
            return "$label($shown)"
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
            return if (text.length > 140) text.take(139) + "…" else text
        }
    }
}
