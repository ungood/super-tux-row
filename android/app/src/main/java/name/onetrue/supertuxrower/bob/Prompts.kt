package name.onetrue.supertuxrower.bob

/**
 * Bob's system prompts. They live as markdown in `src/main/resources/bob`, one file per mode, so
 * they can be read and edited as prose. Every character in those files is sent, and paid for.
 */
object Prompts {
    /** The system prompt for [mode]; [upkeep] is what staying switched on costs, in tokens a second. */
    fun system(mode: BobMode, upkeep: Int): String =
        (read(mode.name.lowercase()) + "\n\n" + read("pace-and-voice")).replace("{upkeep}", upkeep.toString())

    private fun read(name: String): String =
        checkNotNull(Prompts::class.java.getResourceAsStream("/bob/$name.md")) { "No prompt called $name" }
            .use { it.readBytes().decodeToString().trim() }
}
