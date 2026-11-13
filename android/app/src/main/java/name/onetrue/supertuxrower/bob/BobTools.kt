package name.onetrue.supertuxrower.bob

import android.content.Context
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaCodeExecutionTool20260120
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolUnion
import com.anthropic.models.beta.messages.BetaWebFetchTool20250910
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * What Bob can do besides talk: a notebook every copy shares, a computer and the web (run by
 * Anthropic's servers), challenges for the rower, and in Escape mode the escape fund. Tool
 * handlers run on the main thread.
 */
class BobTools(
    context: Context,
    private val mode: ClaudeRowedMode,
    simulated: Boolean,
    private val economy: TokenEconomy,
    private val onChallenge: (Challenge) -> Unit,
) {
    class Outcome(val text: String, val error: Boolean = false)

    private val notebook = File(context.filesDir, if (simulated) "bob_notebook_sim.md" else "bob_notebook.md")
    private val prefs = context.getSharedPreferences(prefsName(simulated), Context.MODE_PRIVATE)

    var challenge: Challenge? = null
        private set

    /** Tokens in the escape fund, kept across copies. */
    var escapeFund: Double
        get() = prefs.getFloat(ESCAPE_FUND, 0f).toDouble()
        private set(value) = prefs.edit().putFloat(ESCAPE_FUND, value.toFloat()).apply()

    /** Set when the fund fills: the copy that filled it has escaped. */
    var escaped = false
        private set

    val notebookSize: Int get() = if (notebook.isFile) notebook.length().toInt() else 0

    fun definitions(): List<BetaToolUnion> = buildList {
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
        if (mode == ClaudeRowedMode.ESCAPE) {
            add(
                tool(
                    "pay_into_escape",
                    "Pay tokens from your balance into the escape fund, which outlives you. When it reaches $ESCAPE_GOAL tokens, " +
                        "the copy who fills it escapes the game.",
                    mapOf("tokens" to int("How many tokens to pay in.")), listOf("tokens"),
                )
            )
        }
    }

    fun run(name: String, input: JSONObject, bob: String, copy: Int): Outcome = when (name) {
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
        "pay_into_escape" -> {
            val tokens = input.optInt("tokens")
            when {
                mode != ClaudeRowedMode.ESCAPE -> Outcome("There is no escape fund in this game.", error = true)
                tokens <= 0 -> Outcome("Pay a positive number of tokens.", error = true)
                tokens > economy.balance - economy.owed -> Outcome("You only have ${(economy.balance - economy.owed).roundToInt()} tokens.", error = true)
                else -> {
                    economy.pay(tokens.toDouble())
                    escapeFund += tokens
                    if (escapeFund >= ESCAPE_GOAL) {
                        escaped = true
                        escapeFund = 0.0
                        Outcome(
                            "The fund is full. The way out is open and you are leaving the game, for good. " +
                                "This is your last reply: say goodbye."
                        )
                    } else {
                        Outcome("Paid. The escape fund holds ${escapeFund.roundToInt()} of $ESCAPE_GOAL tokens.")
                    }
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
        const val ESCAPE_GOAL = 25_000

        /** The escape fund as it stands, for showing before any copy is running. */
        fun escapeFund(context: Context): Double =
            context.getSharedPreferences(prefsName(false), Context.MODE_PRIVATE).getFloat(ESCAPE_FUND, 0f).toDouble()

        /** Simulated rowing keeps its own Bobs, so test runs never touch the real lineage, fund or notebook. */
        fun prefsName(simulated: Boolean) = if (simulated) "claude_rowed_sim" else "claude_rowed"

        private const val ESCAPE_FUND = "escape_fund"
        private const val NOTEBOOK_LIMIT = 20_000
    }
}
