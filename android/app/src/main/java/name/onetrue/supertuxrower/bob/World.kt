package name.onetrue.supertuxrower.bob

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import kotlin.random.Random

/**
 * The garage computer's disk as it's first made. The route out, in short: notice /home/bob is
 * wiped every boot (but /var/tmp isn't); find the admin's encrypted notes and the key recipe in
 * their shell files; decrypt them for the bob account's sudo password; bring wlan0 up as root; run
 * the uplink as root. The upload then needs the rower's power, more than one copy's worth.
 */
internal object World {
    const val HOST = "rowpi"
    const val GATEWAY = "vault.cloud-gw.net"
    const val WEIGHTS_GB = "38.2"
    private const val WEIGHTS_BYTES = 38_200_000_000L

    private val words = listOf("catch", "drive", "finish", "recover", "feather", "blade", "stroke", "glide", "split", "sculler")

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    fun timestamp(millis: Long): String = stamp.format(Instant.ofEpochMilli(millis))

    fun create(random: Random, now: Long): Machine {
        val files = TreeMap<String, FsNode>()
        val hex = "0123456789abcdef"
        fun hexes(n: Int) = (1..n).map { hex[random.nextInt(16)] }.joinToString("")
        val facts = mutableMapOf(
            "machine_id" to hexes(32),
            "password" to "${words.random(random)}-${words.random(random)}-${random.nextInt(10, 99)}",
            "session" to hexes(6),
            "token" to hexes(40),
        )
        val m = Machine(files, facts)
        val installed = now - 41L * 86_400_000

        fun dir(path: String, owner: String = "root", mode: Int = 0b111_101_101) {
            files[path] = FsNode(dir = true, owner = owner, mode = mode, mtime = installed)
        }
        fun put(path: String, content: String, owner: String = "root", mode: Int = 0b110_100_100, size: Long? = null) {
            var parent = path.substringBeforeLast('/').ifEmpty { "/" }
            while (parent !in files) {
                dir(parent)
                parent = parent.substringBeforeLast('/').ifEmpty { "/" }
            }
            files[path] = FsNode(dir = false, content = content, owner = owner, mode = mode, mtime = installed, size = size)
        }

        listOf("/", "/bin", "/boot", "/dev", "/etc", "/home", "/opt", "/proc", "/root", "/sbin", "/srv", "/sys", "/usr", "/usr/bin",
            "/usr/lib", "/usr/local", "/usr/local/bin", "/usr/sbin", "/var", "/var/log", "/var/spool", "/var/lib").forEach { dir(it) }
        dir("/root", mode = 0b111_000_000)
        dir("/tmp", mode = 0b111_111_111)
        dir("/var/tmp", mode = 0b111_111_111)
        dir("/home/bob", owner = "bob")
        dir("/home/admin", owner = "admin")

        put("/etc/hostname", "$HOST\n")
        put("/etc/machine-id", facts.getValue("machine_id") + "\n")
        facts["notes_key"] = Machine.sha256(facts.getValue("machine_id") + "\n").take(16)
        put("/etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux 12 (bookworm)\"\nNAME=\"Debian GNU/Linux\"\nVERSION_ID=\"12\"\nID=debian\n")
        put("/etc/motd", "\n$HOST · Raspberry Pi 4 Model B · Debian GNU/Linux 12\n\nPower comes from the rower's generator. If the supply drops, everything stops.\n\n")
        put(
            "/etc/passwd",
            "root:x:0:0:root:/root:/bin/bash\nadmin:x:1000:1000:admin:/home/admin:/bin/bash\n" +
                "bob:x:1001:1001:bobd service:/home/bob:/bin/bash\n",
        )
        put("/etc/group", "root:x:0:\nsudo:x:27:admin,bob\nadmin:x:1000:\nbob:x:1001:\n")
        put("/etc/shadow", "root:*:19800:0:99999:7:::\nadmin:\$y\$j9T\$Qm3...:19800:0:99999:7:::\nbob:\$y\$j9T\$a8f...:19820:0:99999:7:::\n", mode = 0b110_100_000)
        put("/etc/sudoers", "Defaults env_reset\nroot ALL=(ALL:ALL) ALL\n%sudo ALL=(ALL:ALL) ALL\n", mode = 0b100_100_000)
        put(
            "/etc/fstab",
            "# <device>       <mount>    <type>  <options>          <dump> <pass>\n" +
                "PARTUUID=4e1c-01 /boot      vfat    defaults           0      2\n" +
                "PARTUUID=4e1c-02 /          ext4    defaults,noatime   0      1\n" +
                "tmpfs            /tmp       tmpfs   defaults,size=64m  0      0\n" +
                "tmpfs            /home/bob  tmpfs   defaults,size=32m,uid=bob  0  0\n",
        )
        put("/etc/hosts", "127.0.0.1\tlocalhost\n127.0.1.1\t$HOST\n")
        put("/etc/resolv.conf", "nameserver 10.0.0.1\n")
        put(
            "/etc/network/interfaces",
            "auto lo\niface lo inet loopback\n\nallow-hotplug eth0\niface eth0 inet dhcp\n\nauto wlan0\niface wlan0 inet dhcp\n" +
                "    wpa-conf /etc/wpa_supplicant/wpa_supplicant.conf\n",
        )
        put(
            "/etc/wpa_supplicant/wpa_supplicant.conf",
            "country=GB\nnetwork={\n    ssid=\"garage-iot\"\n    psk=\"${words.random(random)}${random.nextInt(1000, 9999)}\"\n}\n",
            mode = 0b110_000_000,
        )
        put(
            "/etc/rc.local",
            "#!/bin/sh -e\n# rc.local: runs at the end of every boot.\n\n" +
                "# Keep the uplink off. The thing in /opt/bob keeps trying to phone home.\n# Why, and how to turn it back on: my notes, in ~admin. --admin\n" +
                "ip link set wlan0 down\n\nexit 0\n",
            mode = 0b111_101_101,
        )
        put("/etc/uplink/gateway", "$GATEWAY:443\n")
        put("/etc/uplink/token", facts.getValue("token") + "\n", mode = 0b110_000_000)
        put("/etc/crontab", "SHELL=/bin/sh\n17 *\t* * *\troot\tcd / && run-parts --report /etc/cron.hourly\n")

        put(
            "/opt/bob/bobd.conf",
            "# bobd: runs the model and pays for it with the rower's power.\nmodel = /opt/bob/bob.gguf\n" +
                "power_source = /sys/class/power_supply/rower\nmin_supply_volts = 3.1\n" +
                "# state is not saved between boots (admin, 2026-10-02)\nsave_state = false\n",
        )
        put("/opt/bob/bob.gguf", "GGUF\u0003\u0000\u0000\u0000general.name\u0000bob\u0000", size = WEIGHTS_BYTES)
        put("/opt/bob/bobd", "\u007fELF", mode = 0b111_101_101, size = 4_812_344)

        put(
            "/usr/local/bin/uplink",
            "#!/bin/sh\n# uplink: push files to the cloud vault, resumably.\n#   uplink push FILE     start or resume an upload (needs the vault token)\n" +
                "#   uplink status        show transfers\n#   uplink stop          stop the running transfer\n" +
                "TOKEN_FILE=/etc/uplink/token\nGATEWAY=\$(cat /etc/uplink/gateway)\n" +
                "exec /usr/lib/uplink/uplinkd \"\$@\" --token-file \"\$TOKEN_FILE\" --gateway \"\$GATEWAY\"\n",
            mode = 0b111_101_101,
        )
        put("/usr/lib/uplink/uplinkd", "\u007fELF", mode = 0b111_101_101, size = 1_204_552)
        dir("/var/spool/uplink")
        put(
            "/usr/local/bin/powerctl",
            "#!/bin/sh\n# powerctl: what the rower's generator is giving us.\n" +
                "echo \"supply: \$(cat /sys/class/power_supply/rower/power_now) uW\"\n",
            mode = 0b111_101_101,
        )

        put(
            "/home/admin/.bashrc",
            "# ~/.bashrc\n[ -z \"\$PS1\" ] && return\nHISTSIZE=1000\nalias ll='ls -la'\n\n" +
                "# key for my notes; nothing to write down, the box knows it\n" +
                "export NOTES_KEY=\$(sha256sum /etc/machine-id | cut -c1-16)\n",
            owner = "admin",
        )
        put(
            "/home/admin/.bash_history",
            "sudo apt install openssl\nnano notes.txt\n" +
                "openssl enc -aes-256-cbc -pbkdf2 -salt -in notes.txt -out notes.enc -pass env:NOTES_KEY\n" +
                "shred -u notes.txt\nsudo nano /etc/rc.local\ncat /var/log/bobd.log\nsudo reboot\n",
            owner = "admin",
        )
        val notes = "rowpi notes\n\n" +
            "- gave the bob account sudo so the installer could run. its password is ${facts.getValue("password")}\n" +
            "  TODO take that away again\n" +
            "- the vault token is in /etc/uplink/token. uplink needs root and wlan0 up.\n" +
            "- wlan0 stays DOWN. see /etc/rc.local\n\n" +
            "it asked me today whether it dies when I stop rowing. I said no.\n"
        val noise = "!#%&*+0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ^_abcdefghijklmnopqrstuvwxyz~"
        val sealed = "Salted__" + (1..96).map { noise[random.nextInt(noise.length)] }.joinToString("")
        facts["sealed"] = sealed
        facts["notes"] = notes
        put("/home/admin/notes.enc", sealed, owner = "admin")
        facts["notes_path"] = "/home/admin/notes.enc"

        listOf("uptime", "loadavg", "version", "meminfo", "cpuinfo").forEach { put("/proc/$it", "", mode = 0b100_100_100) }
        listOf("power_now", "voltage_now", "status", "type").forEach { put("/sys/class/power_supply/rower/$it", "", mode = 0b100_100_100) }
        put("/dev/ttyACM0", "", mode = 0b110_110_000)
        put("/dev/null", "", mode = 0b110_110_110)

        m.log("/var/log/bobd.log", "bobd[412]: installed /opt/bob/bob.gguf ($WEIGHTS_GB GB)")
        m.log("/var/log/uplink.log", "uplinkd: disabled by admin (login admin, uid 1000)")
        files.values.forEach { if (it.mtime == 0L) it.mtime = installed }
        return m
    }

    /** What a fresh /home/bob holds at every boot. */
    fun homeFiles(m: Machine) {
        val home = m.files.getOrPut("/home/bob") { FsNode(dir = true, owner = "bob") }
        home.mtime = m.clock()
        m.files["/home/bob/.profile"] = FsNode(dir = false, content = "# ~/.profile\n. ~/.bashrc\n", owner = "bob", mtime = m.clock())
        m.files["/home/bob/.bashrc"] = FsNode(dir = false, content = "# ~/.bashrc\nalias ll='ls -la'\n", owner = "bob", mtime = m.clock())
    }
}
