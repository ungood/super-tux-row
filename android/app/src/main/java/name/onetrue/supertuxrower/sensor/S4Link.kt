package name.onetrue.supertuxrower.sensor

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import kotlin.concurrent.thread

/**
 * A line-oriented link to the WaterRower S4 over USB CDC-ACM, using the Android USB host API.
 *
 * Calls [onLine] on its own thread for each line the S4 sends, and [onError] if the link fails.
 */
class S4Link(
    private val usb: UsbManager,
    private val device: UsbDevice,
    private val onLine: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile var handshaken = false
        private set

    private lateinit var connection: UsbDeviceConnection
    private lateinit var output: UsbEndpoint

    fun start() {
        running = true
        thread(name = "s4-link") {
            try {
                run()
            } catch (e: Exception) {
                onError(e.toString())
            }
        }
    }

    fun close() {
        running = false
    }

    private fun run() {
        val control = (0 until device.interfaceCount).map(device::getInterface)
            .first { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
        val data = (0 until device.interfaceCount).map(device::getInterface)
            .first { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
        val endpoints = (0 until data.endpointCount).map(data::getEndpoint)
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val input = endpoints.first { it.direction == UsbConstants.USB_DIR_IN }
        output = endpoints.first { it.direction == UsbConstants.USB_DIR_OUT }

        connection = usb.openDevice(device) ?: throw IllegalStateException("openDevice failed")
        try {
            // force = true detaches the kernel's cdc_acm driver.
            check(connection.claimInterface(control, true)) { "claim control interface failed" }
            check(connection.claimInterface(data, true)) { "claim data interface failed" }

            // CDC SET_LINE_CODING: 19200 baud, 1 stop bit, no parity, 8 data bits (little-endian).
            val coding = byteArrayOf(0x00, 0x4B, 0x00, 0x00, 0, 0, 8)
            connection.controlTransfer(REQUEST_TYPE_CLASS_OUT, SET_LINE_CODING, 0, control.id, coding, coding.size, 1000)
            // CDC SET_CONTROL_LINE_STATE: raise DTR and RTS.
            connection.controlTransfer(REQUEST_TYPE_CLASS_OUT, SET_CONTROL_LINE_STATE, 0x03, control.id, null, 0, 1000)

            val buffer = ByteArray(maxOf(64, input.maxPacketSize))
            val line = StringBuilder()
            var lastHello = 0L
            while (running) {
                val now = System.currentTimeMillis()
                // A sleeping monitor ignores the handshake, so repeat it until it answers.
                if (!handshaken && now - lastHello > 2000) {
                    send("USB")
                    lastHello = now
                }
                val n = connection.bulkTransfer(input, buffer, buffer.size, 200)
                for (i in 0 until n) {
                    when (val c = buffer[i].toInt().toChar()) {
                        '\n' -> {
                            val text = line.toString().trim()
                            line.clear()
                            if (text.isNotEmpty()) {
                                if (text == "_WR_") handshaken = true
                                onLine(text)
                            }
                        }
                        '\r' -> {}
                        else -> line.append(c)
                    }
                }
            }
            send("EXIT")
        } finally {
            connection.close()
        }
    }

    fun send(command: String) {
        val bytes = "$command\r\n".toByteArray(Charsets.US_ASCII)
        connection.bulkTransfer(output, bytes, bytes.size, 500)
    }

    companion object {
        const val VENDOR_ID = 0x04D8
        const val PRODUCT_ID = 0x000A

        private const val REQUEST_TYPE_CLASS_OUT = 0x21
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
    }
}
