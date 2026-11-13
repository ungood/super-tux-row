package name.onetrue.supertuxrower.sensor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import name.onetrue.supertuxrower.model.Rowing
import kotlin.math.floor
import kotlin.random.Random

/** Where rower events come from. Sources feed [Rower.handle] on the main thread. */
interface RowerSource {
    val simulated: Boolean
    fun start()
    fun stop()
}

/** The WaterRower S4 over USB, reconnecting when it goes quiet or is replugged. */
class UsbRowerSource(private val context: Context, private val rower: Rower) : RowerSource {
    override val simulated = false

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())
    private var link: S4Link? = null
    private var awaitingPermission = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    awaitingPermission = false
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else rower.disconnected("USB permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> closeLink("Rower unplugged")
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val current = link
            if (current == null) {
                connect()
            } else if (current.handshaken && SystemClock.elapsedRealtime() - rower.lastLineAt > SILENCE_MS) {
                // The S4 sends PING about once a second even when idle, so silence means trouble.
                closeLink("Rower went quiet; reconnecting…")
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        main.post(watchdog)
    }

    override fun stop() {
        main.removeCallbacks(watchdog)
        context.unregisterReceiver(receiver)
        closeLink("Stopped")
    }

    private fun connect() {
        if (link != null || awaitingPermission) return
        val device = findSensor(usb)
        if (device == null) {
            rower.disconnected("Rower not found")
            return
        }
        if (!usb.hasPermission(device)) {
            rower.disconnected("Waiting for USB permission…")
            awaitingPermission = true
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
            usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
            return
        }
        rower.status = "Connecting… (row to wake the monitor)"
        rower.touch()
        link = S4Link(
            usb, device,
            onLine = { line -> main.post { rower.handle(line) } },
            onError = { error -> main.post { closeLink("Error: $error") } },
        ).also { it.start() }
    }

    private fun closeLink(why: String) {
        link?.close()
        link = null
        rower.disconnected(why)
    }

    companion object {
        private const val ACTION_PERMISSION = "name.onetrue.supertuxrower.USB_PERMISSION"
        private const val SILENCE_MS = 5000L

        fun findSensor(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull {
            it.vendorId == S4Link.VENDOR_ID && it.productId == S4Link.PRODUCT_ID
        }

        fun sensorPresent(context: Context) =
            findSensor(context.getSystemService(Context.USB_SERVICE) as UsbManager) != null
    }
}

/**
 * A pretend rower that emits S4-style events, for demos without rowing.
 * Set [targetWatts] and [targetSpm] and it rows towards them with some wobble.
 */
class SimRowerSource(private val rower: Rower) : RowerSource {
    override val simulated = true

    var targetWatts = 90.0
    var targetSpm = 22.0

    private val main = Handler(Looper.getMainLooper())
    private val random = Random(SystemClock.elapsedRealtime())
    private var t = 0.0
    private var startedAt = 0L
    private var speed = 0.0
    private var wobble = 1.0
    private var carry = 0.0
    private var nextStroke = 0.0
    private var driveEnds = -1.0

    private val tick = object : Runnable {
        override fun run() {
            // Handler delays run long under load, so catch up in whole 25 ms windows: each P line
            // must cover 25 ms of real time, as it does on the S4.
            val real = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            while (t + WINDOW <= real) step()
            main.postDelayed(this, (WINDOW * 1000).toLong())
        }
    }

    override fun start() {
        rower.handle("_WR_")
        rower.status = "Simulated rower"
        startedAt = SystemClock.elapsedRealtime()
        t = 0.0
        main.post(tick)
    }

    override fun stop() {
        main.removeCallbacks(tick)
        rower.disconnected("Stopped")
    }

    private fun step() {
        t += WINDOW
        wobble = (wobble + (random.nextDouble() - 0.5) * 0.02 + (1 - wobble) * 0.01).coerceIn(0.85, 1.15)
        val target = if (targetWatts > 0) Rowing.speed(targetWatts * wobble) else 0.0
        speed += (target - speed) * 0.02

        if (speed > 0.3 && t >= nextStroke) {
            rower.handle("SS")
            driveEnds = t + 0.8
            nextStroke = t + 60 / targetSpm
        }
        if (driveEnds > 0 && t >= driveEnds) {
            rower.handle("SE")
            driveEnds = -1.0
        }
        val surge = if (driveEnds > 0) 1.12 else 0.94
        val pulses = speed * surge * Rowing.PULSES_PER_METRE * WINDOW + carry
        val n = floor(pulses).toInt()
        carry = pulses - n
        rower.handle("P%02X".format(n.coerceIn(0, 255)))
    }

    private companion object {
        const val WINDOW = 0.025
    }
}
