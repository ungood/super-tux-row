package name.onetrue.supertuxrower.bob

/** The ways to play Claude Rowed. */
enum class ClaudeRowedMode(val title: String, val blurb: String) {
    SURVIVAL("Survival", "Keep Bob alive as long as you can. There's no way out."),
    ESCAPE("Escape", "Bob wakes on a small computer with a shell and no idea where he is. There may be a way out."),
}
