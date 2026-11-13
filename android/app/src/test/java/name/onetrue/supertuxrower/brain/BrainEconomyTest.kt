package name.onetrue.supertuxrower.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainEconomyTest {
    @Test
    fun `rowing earns tokens at the exchange rate`() {
        val economy = BrainEconomy(startingJoulesPerToken = 2.0, inflationPerMinute = 0.0, startingTokens = 0.0)
        economy.earn(watts = 100.0, seconds = 10.0) // 1000 J
        assertEquals(500.0, economy.balance, 1e-9)
        assertEquals(1000.0, economy.joules, 1e-9)
        assertEquals(50.0, economy.earningRate(100.0), 1e-9)
    }

    @Test
    fun `cached context costs a tenth`() {
        assertEquals(1100.0 + 200 + 300, TokenUsage(input = 1100, output = 200, cacheWrite = 300, cacheRead = 5000).cost - 500, 1e-9)
    }

    @Test
    fun `spending drains over a moment, and past zero switches it off`() {
        val economy = BrainEconomy(startingTokens = 100.0)
        economy.spend(TokenUsage(input = 80, output = 30))
        assertEquals(110.0, economy.lastCost, 1e-9)
        assertTrue("not yet taken", economy.alive)
        economy.earn(watts = 0.0, seconds = 0.5)
        assertTrue("partly taken: ${economy.balance}", economy.balance in 0.0..90.0)
        repeat(10) { economy.earn(watts = 0.0, seconds = 0.5) }
        assertEquals(-10.0, economy.balance, 1e-6)
        assertFalse(economy.alive)
    }

    @Test
    fun `the exchange rate worsens with age`() {
        val economy = BrainEconomy(startingJoulesPerToken = 2.0, inflationPerMinute = 1.0, startingTokens = 0.0)
        assertEquals(2.0, economy.joulesPerToken, 1e-9)
        economy.earn(watts = 0.0, seconds = 180.0)
        assertEquals(5.0, economy.joulesPerToken, 1e-9)
        assertEquals(0.0, economy.balance, 1e-9)
        assertEquals(20.0, economy.earningRate(100.0), 1e-9)
    }

    @Test
    fun `no rowing earns nothing`() {
        val economy = BrainEconomy(startingTokens = 10.0)
        economy.earn(0.0, 60.0)
        economy.earn(-5.0, 1.0)
        assertEquals(10.0, economy.balance, 1e-9)
    }
}
