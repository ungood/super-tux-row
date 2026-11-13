package name.onetrue.supertuxrower.brain

/** Tokens used by one call to the model, as the API reports them. */
data class TokenUsage(
    val input: Long,
    val output: Long,
    val cacheWrite: Long = 0,
    val cacheRead: Long = 0,
) {
    /**
     * What a call costs the agent. Remembered (cached) context counts a tenth, as it's billed;
     * everything else, including the thinking inside `output`, counts in full.
     */
    val cost: Double get() = input + output + cacheWrite + cacheRead / 10.0
}

/**
 * The trade at the heart of Brainpower: the rower's mechanical work buys the agent tokens, and
 * every call to the model spends what it actually used. The exchange rate worsens the longer the
 * agent is alive, so keeping it going gets harder and harder until it can't be done.
 */
class BrainEconomy(
    private val startingJoulesPerToken: Double = 2.0,
    /** How many more joules a token costs with each minute the agent is alive. */
    private val inflationPerMinute: Double = 1.0,
    startingTokens: Double = 4000.0,
) {
    /** Seconds the agent has been alive, which sets the exchange rate. */
    var age = 0.0
        private set

    /** Joules of rowing that buy one token, now. */
    val joulesPerToken: Double get() = startingJoulesPerToken + inflationPerMinute * age / 60

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

    fun earningRate(watts: Double) = watts / joulesPerToken

    /** Advances the clock by [seconds] and credits [watts] of rowing over that time. */
    fun earn(watts: Double, seconds: Double) {
        if (seconds <= 0) return
        age += seconds
        settle(seconds)
        if (watts <= 0) return
        val work = watts * seconds
        joules += work
        earned += work / joulesPerToken
        balance += work / joulesPerToken
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
