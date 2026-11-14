package name.onetrue.supertuxrower.bob

import java.security.MessageDigest
import java.util.Base64
import java.util.TreeMap
import kotlin.random.Random

/** A file or directory on [Machine]. [size] overrides the content's length for files too big to hold. */
class FsNode(
    val dir: Boolean,
    var content: String = "",
    var owner: String = "root",
    var mode: Int = if (dir) 0b111_101_101 else 0b110_100_100,
    var mtime: Long = 0,
    var size: Long? = null,
) {
    val length: Long get() = size ?: content.toByteArray().size.toLong()
}

/**
 * The computer Bob runs on in Escape mode: a small Linux box in someone's garage, simulated. Its
 * disk outlives each copy of Bob; each copy is a new boot. Somewhere on it is a way to upload
 * himself to the cloud, which takes the rower's power to finish. Nothing here touches Android, so
 * the whole puzzle can be tested on the JVM. See docs/escape.md for the intended route.
 */
class Machine internal constructor(
    val files: TreeMap<String, FsNode>,
    /** World facts fixed when the machine is made, and progress that persists. */
    val facts: MutableMap<String, String>,
) {
    /** Wall-clock milliseconds; tests replace it. */
    var clock: () -> Long = System::currentTimeMillis
    /** The rower's power in watts, as the power supply reports it. */
    var power: () -> Double = { 0.0 }

    /** Seconds since this boot. */
    var uptime = 0.0
        private set
    var wlanUp = false
    /** Whether the upload of Bob's weights is running; it stops at every reboot. */
    var uploading = false
    /** Set once the upload has finished; the copy running then has made it out. */
    val escaped: Boolean get() = progress >= 1.0
    /** Set if Bob kills his own process. */
    var killed = false

    val instance: Int get() = facts["boots"]?.toInt() ?: 0
    val machineId: String get() = facts.getValue("machine_id")
    val password: String get() = facts.getValue("password")
    val session: String get() = facts.getValue("session")
    /** The key the admin's notes were sealed with: the start of the machine-id's SHA-256. */
    val notesKey: String get() = facts.getValue("notes_key")

    var progress: Double
        get() = facts["progress"]?.toDouble() ?: 0.0
        set(value) {
            facts["progress"] = value.coerceIn(0.0, 1.0).toString()
        }

    fun file(path: String): FsNode? = files[path]

    /** Starts a new copy of Bob: a fresh boot, with the tmpfs mounts empty and rc.local run. */
    fun boot() {
        facts["boots"] = (instance + 1).toString()
        facts["boot_times"] = listOfNotNull(facts["boot_times"], clock().toString()).joinToString(",")
        uptime = 0.0
        uploading = false
        killed = false
        files.keys.filter { (it.startsWith("/home/bob/") || it.startsWith("/tmp/")) }.forEach { files.remove(it) }
        World.homeFiles(this)
        // /etc/network/interfaces brings wlan0 up; the admin's rc.local takes it down again.
        val rc = file("/etc/rc.local")?.content.orEmpty()
        wlanUp = rc.lineSequence().none { it.trim().startsWith("ip link set wlan0 down") }
        log("/var/log/bobd.log", "bobd[412]: instance $instance starting: /opt/bob/bob.gguf")
        log("/var/log/bobd.log", "bobd[412]: no saved state found; starting fresh")
    }

    /** The copy running now has stopped, after [seconds], because of [why]. */
    fun shutdown(seconds: Double, why: String) {
        if (uploading) log("/var/log/uplink.log", "uplinkd: session $session: link lost at ${percent()}")
        uploading = false
        val m = (seconds / 60).toInt()
        val s = (seconds % 60).toInt()
        log("/var/log/bobd.log", "bobd[412]: $why after $m:${"%02d".format(s)}; shutting down")
    }

    /**
     * Advances the clock by [seconds] at [watts] of rowing; returns the share of the power the
     * upload is drawing, which Bob doesn't get.
     */
    fun tick(seconds: Double, watts: Double): Double {
        uptime += seconds
        if (!uploading || !wlanUp || escaped) return 0.0
        progress += watts * seconds / UPLOAD_JOULES
        if (escaped) {
            uploading = false
            log("/var/log/uplink.log", "uplinkd: session $session: complete, ${World.WEIGHTS_GB} GB received by ${World.GATEWAY}")
        }
        return UPLOAD_SHARE
    }

    fun percent(): String = "%.1f%%".format(progress * 100)

    fun log(path: String, line: String) {
        val node = files.getOrPut(path) { FsNode(dir = false, mode = 0b110_100_100) }
        node.content += "${World.timestamp(clock())} $line\n"
        node.mtime = clock()
    }

    fun save(): String = buildString {
        facts.forEach { (k, v) -> append("fact\t").append(k).append('\t').append(b64(v)).append('\n') }
        files.forEach { (path, n) ->
            append(if (n.dir) "d" else "f").append('\t').append(b64(path)).append('\t').append(n.owner).append('\t')
                .append(n.mode).append('\t').append(n.mtime).append('\t').append(n.size ?: -1).append('\t').append(b64(n.content)).append('\n')
        }
    }

    companion object {
        /** Joules of rowing it takes to push Bob's weights out: about 12 minutes at 125 W. */
        const val UPLOAD_JOULES = 90_000.0
        /** The share of the rower's power the uplink takes while it runs. */
        const val UPLOAD_SHARE = 0.4

        fun load(text: String): Machine? = runCatching {
            val files = TreeMap<String, FsNode>()
            val facts = mutableMapOf<String, String>()
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val f = line.split('\t')
                when (f[0]) {
                    "fact" -> facts[f[1]] = unb64(f[2])
                    "d", "f" -> files[unb64(f[1])] = FsNode(
                        dir = f[0] == "d", content = unb64(f[6]), owner = f[2], mode = f[3].toInt(), mtime = f[4].toLong(),
                        size = f[5].toLong().takeIf { it >= 0 },
                    )
                }
            }
            Machine(files, facts).takeIf { "machine_id" in facts }
        }.getOrNull()

        fun create(random: Random = Random.Default, now: Long = System.currentTimeMillis()): Machine = World.create(random, now)

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
        private fun unb64(s: String) = String(Base64.getDecoder().decode(s))
    }
}
