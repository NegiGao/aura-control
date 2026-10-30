package tech.devline.scropy_ui.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

data class UsbWifiTarget(val host: String, val serial: String, val port: Int = 5555)

internal fun wifiIpv4(output: String): String? =
    Regex("""\binet\s+(\d+\.\d+\.\d+\.\d+)/""").findAll(output)
        .map { it.groupValues[1] }
        .firstOrNull { ip ->
            val octets = ip.split('.').mapNotNull { it.toIntOrNull() }
            octets.size == 4 && octets.all { it in 0..255 } &&
                octets[0] != 127 && octets[0] != 0 && !ip.startsWith("169.254.")
        }

/** Each shell read is bounded, even if the daemon never closes its stream. */
private fun readCommand(conn: AdbConnection, service: String): String = conn.readCommand(service)

/** USB authorization bootstraps ordinary TCP ADB; never guesses among Wi-Fi targets. */
suspend fun setupUsbWifi(context: Context, device: UsbDevice, manager: UsbManager): UsbWifiTarget =
    withContext(Dispatchers.IO) {
        val usb = AdbConnection.connectUsb(context, device, manager)
        val target: UsbWifiTarget
        try {
            val serial = readCommand(usb, "shell:getprop ro.serialno")
            if (serial.isBlank()) throw IOException("端末のシリアル番号を取得できません。無線接続を中止しました")
            val address = readCommand(usb, "shell:ip -4 -o addr show wlan0")
            val host = wifiIpv4(address)
                ?: throw IOException("AURA をスマートフォンと同じ Wi-Fi に接続してください")
            target = UsbWifiTarget(host, serial)
            // The standard ADB service restarts adbd, so USB may close before its reply.
            runCatching { readCommand(usb, "tcpip:5555") }
        } finally {
            usb.close()
        }
        var reachable = false
        for (attempt in 0 until 12) {
            reachable = runCatching {
                Socket().use { it.connect(InetSocketAddress(target.host, target.port), 500) }
            }.isSuccess
            if (reachable) break
            delay(750)
        }
        if (!reachable) throw IOException("${target.host}:5555 に接続できません。同じ Wi-Fi、端末間通信の制限、USB デバッグの許可を確認してください")
        val wifi = AdbConnection.connectTcp(context, target.host, target.port)
        try {
            val serial = readCommand(wifi, "shell:getprop ro.serialno")
            if (serial != target.serial) throw IOException("無線接続先のシリアル番号が一致しません。接続を中止しました")
        } finally {
            wifi.close()
        }
        target
    }
