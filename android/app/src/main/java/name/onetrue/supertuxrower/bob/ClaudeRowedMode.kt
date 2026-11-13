package name.onetrue.supertuxrower.bob

/** The ways to play Claude Rowed. */
enum class ClaudeRowedMode(val title: String, val blurb: String) {
    SURVIVAL("Survival", "Keep Bob alive as long as you can. There's no way out."),
    ESCAPE("Escape", "Bob can pay into an escape fund that outlives him. Fill it and he's free."),
}
