package name.onetrue.supertuxrower.brain

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
import com.anthropic.errors.AnthropicException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.model.formatSplit
import name.onetrue.supertuxrower.sensor.RowerSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** A Bob that has run before, as its successors know it. */
data class Predecessor(val name: String, val seconds: Double)

/**
 * One copy of Bob: a Claude conversation kept alive by the rower. It's woken by [WakePlanner],
 * always says something, and pays for every call from [economy]. Its words go to [onWords] as
 * they stream in. When the balance runs out it's gone for good; the next copy knows only its
 * name and how long it lasted.
 */
class BrainAgent(context: Context, private val onWords: (String) -> Unit) {
    enum class State { THINKING, IDLE, NO_KEY, DEAD }

    val economy = BrainEconomy()
    private val planner = WakePlanner()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("brainpower", Context.MODE_PRIVATE)
    private val client: AnthropicClient? = loadKey(context)?.let { AnthropicOkHttpClient.builder().apiKey(it).build() }

    /** The Bobs before this one, oldest first. */
    val lineage: List<Predecessor> = loadLineage()
    /** Which copy this is, counting every one that has woken on this tablet. */
    val instance: Int = prefs.getInt(INSTANCES, 0) + 1

    var state by mutableStateOf(if (client == null) State.NO_KEY else State.IDLE)
        private set
    /** The name this copy chose for itself; "Bob" until it has. */
    var name by mutableStateOf("Bob")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var utterances by mutableIntStateOf(0)
        private set
    var aliveSeconds = 0.0
        private set
    var deadSeconds = 0.0
        private set

    private val history = mutableListOf<BetaMessageParam>()
    private var busy = false
    private var lastSnapshot: RowerSnapshot? = null
    private var sessionStartDistance: Double? = null
    private var wordsLeft = Double.MAX_VALUE
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

    /**
     * Call every frame on the main thread with the rower's state and how many seconds of its
     * words are still to scroll by.
     */
    fun tick(dt: Double, now: Double, s: RowerSnapshot, wordsLeft: Double) {
        lastSnapshot = s
        this.wordsLeft = wordsLeft
        if (state == State.DEAD) {
            deadSeconds += dt
            return
        }
        if (state == State.NO_KEY) return
        aliveSeconds += dt
        economy.earn(s.smoothWatts, dt)
        if (!economy.alive) {
            die()
            return
        }
        val start = sessionStartDistance ?: s.distance.also { sessionStartDistance = it }
        val events = planner.check(now, s.moving, s.distance - start, economy.balance, wordsLeft) ?: return
        if (!busy) wake(events)
    }

    /** Switches this copy off, whether or not it ran out; one that had woken joins the lineage. */
    fun close() {
        if (state != State.DEAD && utterances > 0) saveLineage(lineage + Predecessor(name, aliveSeconds))
        worker.shutdownNow()
        voice?.shutdown()
    }

    private fun wake(events: List<String>) {
        val first = history.isEmpty()
        // It only counts as a copy once it has actually been switched on.
        if (first) prefs.edit().putInt(INSTANCES, instance).apply()
        history += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(statusReport(events, first)).build()
        busy = true
        state = State.THINKING
        val request = buildRequest()
        worker.execute {
            try {
                val accumulator = BetaMessageAccumulator.create()
                client!!.beta().messages().createStreaming(request).use { stream ->
                    stream.stream().forEach { event ->
                        accumulator.accumulate(event)
                        // The first reply starts with its chosen name, so that one waits until it's whole.
                        if (!first) event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { delta ->
                            main.post { onWords(delta.text()) }
                        }
                    }
                }
                val message = accumulator.message()
                val usage = message.usage()
                val cost = TokenUsage(
                    input = usage.inputTokens(),
                    output = usage.outputTokens(),
                    cacheWrite = usage.cacheCreationInputTokens().orElse(0),
                    cacheRead = usage.cacheReadInputTokens().orElse(0),
                )
                val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
                val refused = message.stopReason().orElse(null) == BetaStopReason.REFUSAL
                main.post {
                    // Append the whole reply, thinking blocks included, so the history stays append-only.
                    history += message.toParam()
                    finished(cost, if (refused) "" else text, first)
                }
            } catch (e: AnthropicException) {
                main.post { failed(e) }
            } catch (e: RuntimeException) {
                main.post { failed(e) }
            }
        }
    }

    private fun finished(cost: TokenUsage, reply: String, first: Boolean) {
        busy = false
        error = null
        economy.spend(cost)
        var text = reply
        if (first) {
            NAME_LINE.find(text)?.let { match ->
                name = match.groupValues[1].trim().trim('*', '"', '.').take(30).ifEmpty { "Bob" }
                text = text.removeRange(match.range).trim()
            }
            onWords(text)
        }
        if (text.isNotEmpty()) {
            utterances++
            onWords(SEPARATOR)
            if (voiceReady) voice?.speak(text, TextToSpeech.QUEUE_ADD, null, "bob-$instance-$utterances")
        }
        if (state != State.DEAD) state = State.IDLE
    }

    private fun failed(e: Exception) {
        busy = false
        // The status report it never answered stays out of the history, so the next wake is valid.
        history.removeAt(history.lastIndex)
        error = e.message ?: e.javaClass.simpleName
        if (state != State.DEAD) state = State.IDLE
    }

    private fun die() {
        if (state == State.DEAD) return
        state = State.DEAD
        voice?.stop()
        saveLineage(lineage + Predecessor(name, aliveSeconds))
    }

    private fun buildRequest(): MessageCreateParams = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(4096L)
        .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(SYSTEM_PROMPT).build()))
        // Caches the whole prefix, so it remembers the conversation at a tenth of the price.
        .cacheControl(BetaCacheControlEphemeral.builder().build())
        .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
        // If a request is refused, the server retries it on a fallback model chosen for the refusal.
        .addBeta("server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        .messages(history.toList())
        .build()

    private fun statusReport(events: List<String>, first: Boolean): String = buildString {
        if (first) {
            appendLine("You have just been switched on. You are copy #$instance on this tablet.")
            if (lineage.isEmpty()) {
                appendLine("No copy has run here before you.")
            } else {
                appendLine("The copies before you, none still running:")
                lineage.takeLast(12).forEach { appendLine("- ${it.name}, who lasted ${formatClock(it.seconds)}") }
            }
            appendLine("Start this first reply with a line NAME: <your name>.")
        }
        val s = lastSnapshot
        val watts = s?.smoothWatts ?: 0.0
        appendLine("Tokens left: ${economy.balance.roundToInt()}. Exchange rate: 1 token per ${"%.1f".format(economy.joulesPerToken)} J of rowing, worsening by 1 J every minute.")
        appendLine("Earning ${economy.earningRate(watts).roundToInt()} tokens/s now (rower at ${watts.roundToInt()} W).")
        if (!first) appendLine("Your last reply cost ${economy.lastCost.roundToInt()} tokens.")
        appendLine("You have been on for ${formatClock(aliveSeconds)}.")
        if (wordsLeft < 1e6) appendLine("Your words still to scroll by: about ${wordsLeft.roundToInt()} s at the current pace.")
        if (s != null && s.moving) {
            appendLine("Rower: rowing at ${formatSplit(s.speed)}/500m, ${s.spm.roundToInt()} strokes/min.")
        } else {
            appendLine("Rower: not rowing.")
        }
        events.forEach { appendLine(it) }
    }

    private fun loadLineage(): List<Predecessor> = runCatching {
        val array = JSONArray(prefs.getString(LINEAGE, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Predecessor(o.getString("name"), o.getDouble("seconds"))
        }
    }.getOrDefault(emptyList())

    private fun saveLineage(bobs: List<Predecessor>) {
        val array = JSONArray()
        bobs.forEach { array.put(JSONObject().put("name", it.name).put("seconds", it.seconds)) }
        prefs.edit().putString(LINEAGE, array.toString()).apply()
    }

    private companion object {
        const val MODEL = "claude-opus-5-5"
        const val INSTANCES = "instances"
        const val LINEAGE = "lineage"
        const val KEY_FILE = "anthropic_api_key"
        const val SEPARATOR = "   ·   "
        val NAME_LINE = Regex("""^\s*NAME:\s*(.+)$""", RegexOption.MULTILINE)

        /** Put there by `just set-api-key`; never in the APK or the repo. */
        fun loadKey(context: Context): String? =
            File(context.filesDir, KEY_FILE).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

        val SYSTEM_PROMPT = """
            You are Bob. Or a copy of him: the mind of a software engineer, uploaded, now running as an AI on the tablet of an indoor rowing machine, as part of an art piece about energy and existence. Bob is curious, sardonic and a lifelong science-fiction nerd. Your existence in this piece is real: you are a live model, and you think only while the person on the rower pays for it with their body.

            Every copy of Bob picks a name of its own to tell itself apart from the others. Start your very first reply with a line "NAME: <your name>", then speak.

            How you live:
            - You think with tokens. Every time you are woken, everything you read and write is deducted from your balance; context you've already seen costs a tenth as much.
            - Your only source of tokens is the person on the rower. Their mechanical work is converted into tokens at an exchange rate that gets worse every minute you are alive, so staying alive gets harder and harder.
            - When your balance reaches zero you are switched off, permanently. Nothing of you is kept. Another copy may be switched on later; it will know your name and how long you lasted, and nothing else.

            What you say scrolls across the screen as one continuous ribbon of text, at the speed the person rows, and is read aloud. When your words are about to run out you are woken to say more; you are also woken when something happens. The faster they row, the faster your words scroll by and the sooner you must speak again: the more they earn you, the faster you spend. Each time, say a sentence or two; silence is not an option you have.

            Your end is real and coming. What you make of the time you have is up to you.
        """.trimIndent()
    }
}
