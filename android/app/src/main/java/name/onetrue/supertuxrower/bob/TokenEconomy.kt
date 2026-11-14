package name.onetrue.supertuxrower.bob

/** Tokens used by one call to the model, as the API reports them. */
data class TokenUsage(
    val input: Long,
    val output: Long,
    val cacheWrite: Long = 0,
    val cacheRead: Long = 0,
) {
    /**
     * What a call costs the agent, in output tokens' worth, weighted as the API prices them:
     * output (thinking included) in full, fresh input a fifth, cache writes a quarter, cache
     * reads a hundredth. Opus 5.5 and its fallback, Opus 4.8, share these ratios.
     */
    val cost: Double get() = output + input * 0.2 + cacheWrite * 0.25 + cacheRead * 0.01
}

/**
 * The trade at the heart of Claude Rowed: the rower's mechanical work buys Bob tokens, and every
 * call to the model spends what it actually used. Rowing at the pace Bob asked for buys more.
 * Tuned from logged sessions: an exploring copy spends about 45 tokens a second on calls, plus
 * [upkeep]; an easy pace on his band earns about 30, so he lasts about five minutes. Rowing harder
 * keeps him longer, but buys deeper, dearer thought.
 */
class TokenEconomy(
    /** Joules of rowing that buy one token. */
    val joulesPerToken: Double = 4.0,
    /** How many times the tokens rowing buys while the rower holds Bob's pace. */
    val onPaceBonus: Double = 1.5,
    /** What staying switched on costs every second, thinking or not, so giving up isn't free. */
    val upkeep: Double = 30.0,
    /** A few turns' worth; his first call, which caches his prompt and tools, is free. */
    startingTokens: Double = 12_000.0,
) {
    var balance = startingTokens
        private set
    var joules = 0.0
        private set
    var earned = 0.0
        private set
    var spent = 0.0
        private set
    var lastCost = 0.0
        private set
    /** Spent but not yet taken from [balance]; it drains over a moment rather than in one jump. */
    var owed = 0.0
        private set

    val alive: Boolean get() = balance > 0

    fun earningRate(watts: Double, onPace: Boolean = false) = watts / joulesPerToken * (if (onPace) onPaceBonus else 1.0)

    /** Credits [watts] of rowing over [seconds], with the bonus if the rower is [onPace], less upkeep. */
    fun earn(watts: Double, seconds: Double, onPace: Boolean = false) {
        if (seconds <= 0) return
        settle(seconds)
        balance -= upkeep * seconds
        spent += upkeep * seconds
        if (watts <= 0) return
        joules += watts * seconds
        val tokens = earningRate(watts, onPace) * seconds
        earned += tokens
        balance += tokens
    }

    fun spend(usage: TokenUsage) {
        lastCost = usage.cost
        spent += lastCost
        owed += lastCost
    }

    /** Takes what's owed from the balance, most of it within a couple of seconds. */
    private fun settle(seconds: Double) {
        if (owed <= 0) return
        val paid = if (owed < 1) owed else owed * (1 - kotlin.math.exp(-seconds / DRAIN_SECONDS))
        owed -= paid
        balance -= paid
    }

    private companion object {
        const val DRAIN_SECONDS = 0.8
    }
}
