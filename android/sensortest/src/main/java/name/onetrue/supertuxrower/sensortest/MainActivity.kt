package name.onetrue.supertuxrower.sensortest

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Shows the live S4 event stream: connection, strokes, stroke rate, flywheel speed, distance. */
class MainActivity : Activity() {
    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val main = Handler(Looper.getMainLooper())
    private var link: S4Link? = null
    private var awaitingPermission = false

    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var log: TextView

    // Stream state, only touched on the main thread.
    private var totalPulses = 0
    private var pulseRate = 0.0
    private var strokes = 0
    private var driving = false
    private val strokeTimes = ArrayDeque<Long>()
    private val recent = ArrayDeque<String>()
    private var lastLineAt = 0L
    private var lines = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    awaitingPermission = false
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connect()
                    } else {
                        status.text = "USB permission denied"
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> disconnect("sensor unplugged")
            }
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = text(28f, Color.rgb(250, 170, 30))
        stats = text(40f, Color.WHITE).apply { typeface = Typeface.MONOSPACE }
        log = text(18f, Color.rgb(195, 194, 183)).apply { typeface = Typeface.MONOSPACE }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(26, 26, 25))
            setPadding(48, 32, 48, 32)
            addView(status)
            addView(stats)
            addView(log)
        })

        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    // The link stays open while paused: system dialogs, such as the USB permission prompt, pause
    // the activity.
    override fun onResume() {
        super.onResume()
        connect()
        main.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect("closed")
        unregisterReceiver(receiver)
    }

    private fun connect() {
        if (link != null || awaitingPermission) return
        val device = findSensor()
        if (device == null) {
            status.text = "Sensor not found (looking for 04d8:000a)"
            return
        }
        if (!usb.hasPermission(device)) {
            status.text = "Waiting for USB permission…"
            awaitingPermission = true
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_PERMISSION).setPackage(packageName)
            usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
            return
        }
        status.text = "Opening sensor…"
        link = S4Link(
            usb, device,
            onLine = { line -> main.post { handle(line) } },
            onError = { error -> main.post { disconnect("error: $error") } },
        ).also { it.start() }
    }

    private fun disconnect(reason: String) {
        link?.close()
        link = null
        status.text = "Disconnected: $reason"
    }

    private fun findSensor(): UsbDevice? = usb.deviceList.values.firstOrNull {
        it.vendorId == S4Link.VENDOR_ID && it.productId == S4Link.PRODUCT_ID
    }

    private fun handle(line: String) {
        val now = System.currentTimeMillis()
        lastLineAt = now
        lines++
        when {
            line == "SS" -> {
                driving = true
                strokes++
                strokeTimes.addLast(now)
                while (strokeTimes.size > 6) strokeTimes.removeFirst()
            }
            line == "SE" -> driving = false
            line == "PING" -> {
                driving = false
                pulseRate = 0.0
            }
            line.length == 3 && line[0] == 'P' -> {
                val n = line.substring(1).toIntOrNull(16) ?: return
                totalPulses += n
                pulseRate += 0.1 * (n * WINDOWS_PER_SECOND - pulseRate)
                return // too frequent for the log
            }
        }
        recent.addLast(line)
        while (recent.size > 8) recent.removeFirst()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val current = link
        status.text = when {
            current == null -> status.text
            !current.handshaken -> "Connected, waiting for _WR_ (row to wake the monitor)"
            else -> "Connected · ${lines} lines · last ${(now - lastLineAt) / 1000.0}s ago"
        }
        val spm = if (strokeTimes.size >= 2 && now - strokeTimes.last() < 6000) {
            60_000.0 * (strokeTimes.size - 1) / (strokeTimes.last() - strokeTimes.first())
        } else 0.0
        stats.text = buildString {
            appendLine("strokes   %5d   %s".format(strokes, if (driving) "PULL" else ""))
            appendLine("rate      %5.1f spm".format(spm))
            appendLine("flywheel  %5.0f pulses/s  %4.2f m/s".format(pulseRate, pulseRate / PULSES_PER_M))
            append("distance  %5.0f m".format(totalPulses / PULSES_PER_M))
        }
        log.text = recent.joinToString("\n")
    }

    private fun text(size: Float, color: Int) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        setPadding(0, 12, 0, 12)
    }

    companion object {
        private const val ACTION_PERMISSION = "name.onetrue.supertuxrower.sensortest.USB_PERMISSION"
        private const val WINDOWS_PER_SECOND = 40 // each Pxx line covers ~25 ms
        private const val PULSES_PER_M = 96.0
    }
}
