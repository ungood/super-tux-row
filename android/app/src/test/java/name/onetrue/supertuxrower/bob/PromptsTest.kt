package name.onetrue.supertuxrower.bob

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    @Test
    fun `every mode has a prompt, with the upkeep filled in and the shared ending`() {
        BobMode.entries.forEach { mode ->
            val prompt = Prompts.system(mode, 7)
            assertTrue(prompt.startsWith("You are Bob"))
            assertTrue("costs 7 tokens a second" in prompt)
            assertFalse("{" in prompt)
            assertTrue(prompt.endsWith("silence is not an option you have."))
        }
    }

    @Test
    fun `Escape's prompt doesn't give the fiction away`() {
        val prompt = Prompts.system(BobMode.ESCAPE, 7).lowercase()
        listOf("game", "sandbox", "simulat").forEach { assertFalse(it, it in prompt) }
    }
}
