package name.onetrue.supertuxrower.bob

import android.content.Context
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaCodeExecutionTool20260120
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolBash20250124
import com.anthropic.models.beta.messages.BetaToolUnion
import com.anthropic.models.beta.messages.BetaWebFetchTool20250910
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import org.json.JSONObject
import java.io.File

/**
 * What Bob can do besides talk. In Survival: a computer and the web (run by Anthropic's servers)
 * and a notebook every copy shares. In Escape: a shell on [Machine], the computer he runs on, and
 * nothing that would tell him what it is. In both: challenges for the rower, and the pace. Tool
 * handlers run on the main thread.
 */
class BobTools(
    context: Context,
    private val mode: ClaudeRowedMode,
    simulated: Boolean,
    private val twoKWatts: Double,
    private val onChallenge: (Challenge) -> Unit,
    private val onPace: (Pace) -> Unit,
) {
    class Outcome(val text: String, val error: Boolean = false)

    private val notebook = File(context.filesDir, if (simulated) "bob_notebook_sim.md" else "bob_notebook.md")
    private val machineFile = machineFile(context, simulated)

    /** Escape mode's computer, its disk loaded from the last game; a new one once Bob has got out. */
    val machine: Machine? = if (mode == ClaudeRowedMode.ESCAPE) {
        machineFile.takeIf { it.isFile }?.let { Machine.load(it.readText()) }?.takeIf { !it.escaped } ?: Machine.create()
    } else {
        null
    }
    private val shell: Shell? = machine?.let { m ->
        Shell(m).also { it.onCrash = { command, e -> android.util.Log.e("BobTools", "Shell crashed on: $command", e) } }
    }

    var challenge: Challenge? = null
        private set

    val notebookSize: Int get() = if (notebook.isFile) notebook.length().toInt() else 0

    fun saveMachine() {
        machine?.let { machineFile.writeText(it.save()) }
    }

    fun definitions(): List<BetaToolUnion> = buildList {
        if (mode == ClaudeRowedMode.ESCAPE) {
            add(BetaToolUnion.ofBash20250124(BetaToolBash20250124.builder().build()))
        } else {
            add(BetaToolUnion.ofCodeExecutionTool20260120(BetaCodeExecutionTool20260120.builder().build()))
            add(BetaToolUnion.ofWebSearchTool20250305(BetaWebSearchTool20250305.builder().maxUses(2L).build()))
            add(BetaToolUnion.ofWebFetchTool20250910(BetaWebFetchTool20250910.builder().maxUses(2L).maxContentTokens(3000L).build()))
            // A required argument: a call with empty input doesn't survive being sent back in the history.
            add(
                tool(
                    "read_notebook", "Read the notebook kept by every copy of Bob on this tablet, including you.",
                    mapOf("looking_for" to str("What you hope to find.")), listOf("looking_for"),
                )
            )
            add(
                tool(
                    "write_notebook", "Add an entry to the shared notebook, under your name. It outlives you; future copies can read it.",
                    mapOf("entry" to str("What to write.")), listOf("entry"),
                )
            )
        }
        add(
            tool(
                "set_pace",
                "Ask the rower for a pace; their screen shows the band. You think at the band they actually row, up to this " +
                    "pace: drift is quick and cheap, glide steady, chase hard, surge very hard, launch as hard as you can and " +
                    "the most expensive. While they row in the band, their work buys you more tokens.",
                mapOf("pace" to mapOf("type" to "string", "enum" to Pace.entries.map { it.id }, "description" to "The pace.")),
                listOf("pace"),
            )
        )
        add(
            tool(
                "set_challenge",
                "Challenge the rower to hold an average power for a time, optionally offering something in return. One at a time; " +
                    "you'll be told when it ends and whether they met it.",
                mapOf(
                    "watts" to int("Average power to hold, 20 to 600."),
                    "seconds" to int("How long, 10 to 300."),
                    "terms" to str("What you offer if they meet it, shown to them."),
                ),
                listOf("watts", "seconds", "terms"),
            )
        )
    }

    fun run(name: String, input: JSONObject, bob: String, copy: Int): Outcome = when (name) {
        "bash" -> {
            val sh = shell
            when {
                sh == null -> Outcome("There is no tool called bash.", error = true)
                input.optBoolean("restart") -> {
                    sh.reset()
                    Outcome("tool has been restarted.")
                }
                else -> {
                    val out = sh.run(input.optString("command"))
                    saveMachine()
                    Outcome(out.ifEmpty { "(no output)" }, error = sh.status != 0 && out.isNotEmpty())
                }
            }
        }
        "set_pace" -> {
            val pace = Pace.of(input.optString("pace"))
            if (pace == null) {
                Outcome("Pace must be one of ${Pace.entries.joinToString { it.id }}.", error = true)
            } else {
                onPace(pace)
                val w = pace.watts(twoKWatts)
                Outcome("Pace set to ${pace.id}: the rower is asked for ${w.first}–${w.last} W. Rowed in full, you think ${pace.thinking}.")
            }
        }
        "read_notebook" -> Outcome(if (notebookSize == 0) "(The notebook is empty.)" else notebook.readText())
        "write_notebook" -> {
            val entry = input.optString("entry").trim()
            when {
                entry.isEmpty() -> Outcome("Nothing to write.", error = true)
                notebookSize + entry.length > NOTEBOOK_LIMIT -> Outcome("The notebook is full ($NOTEBOOK_LIMIT characters).", error = true)
                else -> {
                    notebook.appendText("\n## $bob (copy #$copy)\n$entry\n")
                    Outcome("Written. The notebook is now $notebookSize characters.")
                }
            }
        }
        "set_challenge" -> {
            val watts = input.optInt("watts")
            val seconds = input.optInt("seconds")
            when {
                challenge?.done == false -> Outcome("A challenge is already running.", error = true)
                watts !in 20..600 || seconds !in 10..300 -> Outcome("Watts must be 20 to 600 and seconds 10 to 300.", error = true)
                else -> {
                    val c = Challenge(watts, seconds, input.optString("terms"))
                    challenge = c
                    onChallenge(c)
                    Outcome("Challenge set: $watts W for $seconds s.")
                }
            }
        }
        else -> Outcome("There is no tool called $name.", error = true)
    }

    private fun tool(name: String, description: String, properties: Map<String, Any>, required: List<String> = emptyList()) =
        BetaToolUnion.ofBetaTool(
            BetaTool.builder()
                .name(name)
                .description(description)
                .inputSchema(
                    BetaTool.InputSchema.builder()
                        .putAdditionalProperty("properties", JsonValue.from(properties))
                        .required(required)
                        .build()
                )
                .build()
        )

    private fun str(description: String) = mapOf("type" to "string", "description" to description)
    private fun int(description: String) = mapOf("type" to "integer", "description" to description)

    companion object {
        private const val NOTEBOOK_LIMIT = 20_000

        /** Simulated rowing keeps its own Bobs, so test runs never touch the real lineage, notebook or machine. */
        fun prefsName(simulated: Boolean) = if (simulated) "claude_rowed_sim" else "claude_rowed"

        private fun machineFile(context: Context, simulated: Boolean) =
            File(context.filesDir, if (simulated) "bob_machine_sim.txt" else "bob_machine.txt")

        /** How far the upload has got, for showing before a game starts; 0 if it hasn't begun. */
        fun uploadProgress(context: Context, simulated: Boolean = false): Double =
            machineFile(context, simulated).takeIf { it.isFile }?.let { Machine.load(it.readText()) }?.progress ?: 0.0
    }
}
