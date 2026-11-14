package name.onetrue.supertuxrower.bob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEconomyTest {
    @Test
    fun `rowing earns tokens at the exchange rate`() {
        val economy = TokenEconomy(joulesPerToken = 2.0, upkeep = 0.0, startingTokens = 0.0)
        economy.earn(watts = 100.0, seconds = 10.0) // 1000 J
        assertEquals(500.0, economy.balance, 1e-9)
        assertEquals(1000.0, economy.joules, 1e-9)
        assertEquals(50.0, economy.earningRate(100.0), 1e-9)
    }

    @Test
    fun `tokens are weighted as the API prices them`() {
        assertEquals(200 + 1000 * 0.2 + 400 * 0.25 + 5000 * 0.01, TokenUsage(input = 1000, output = 200, cacheWrite = 400, cacheRead = 5000).cost, 1e-9)
    }

    @Test
    fun `spending drains over a moment, and past zero switches it off`() {
        val economy = TokenEconomy(upkeep = 0.0, startingTokens = 100.0)
        economy.spend(TokenUsage(input = 400, output = 30))
        assertEquals(110.0, economy.lastCost, 1e-9)
        assertTrue("not yet taken", economy.alive)
        economy.earn(watts = 0.0, seconds = 0.5)
        assertTrue("partly taken: ${economy.balance}", economy.balance in 0.0..90.0)
        repeat(10) { economy.earn(watts = 0.0, seconds = 0.5) }
        assertEquals(-10.0, economy.balance, 1e-6)
        assertFalse(economy.alive)
    }

    @Test
    fun `rowing on pace buys more`() {
        val economy = TokenEconomy(joulesPerToken = 2.0, onPaceBonus = 1.5, upkeep = 0.0, startingTokens = 0.0)
        economy.earn(watts = 100.0, seconds = 10.0, onPace = true)
        assertEquals(750.0, economy.balance, 1e-9)
        assertEquals(1000.0, economy.joules, 1e-9)
    }

    @Test
    fun `staying on costs upkeep, rowing or not`() {
        val economy = TokenEconomy(joulesPerToken = 1.0, upkeep = 30.0, startingTokens = 1000.0)
        economy.earn(watts = 0.0, seconds = 10.0)
        assertEquals(700.0, economy.balance, 1e-9)
        economy.earn(watts = 30.0, seconds = 10.0)
        assertEquals(700.0, economy.balance, 1e-9)
    }

    @Test
    fun `no rowing earns nothing`() {
        val economy = TokenEconomy(upkeep = 0.0, startingTokens = 10.0)
        economy.earn(0.0, 60.0)
        economy.earn(-5.0, 1.0)
        assertEquals(10.0, economy.balance, 1e-9)
    }
}
