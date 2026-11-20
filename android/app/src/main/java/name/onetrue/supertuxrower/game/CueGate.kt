package name.onetrue.supertuxrower.game

/**
 * Lets a creature's sound play once per visit. The scene asks every frame while the creature is
 * doing its thing; it passes the first time, then again only once that creature has gone unheard
 * for [gap] seconds.
 */
class CueGate(private val gap: Double = 2.0) {
    private val last = HashMap<Long, Double>()

    fun pass(cue: Cue, key: Int, now: Double): Boolean {
        val id = cue.ordinal.toLong() shl 32 or (key.toLong() and 0xFFFFFFFFL)
        val before = last.put(id, now)
        if (last.size > 64) last.entries.removeAll { now - it.value > gap }
        return before == null || now - before > gap
    }
}
