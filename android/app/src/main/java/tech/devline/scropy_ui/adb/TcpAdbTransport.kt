package tech.devline.scropy_ui.adb

import java.net.InetSocketAddress
import java.net.Socket

/** Plain ADB-over-TCP transport used by AURA's development port 5555. */
class TcpAdbTransport(host: String, port: Int) : AdbTransport {
    private val socket = Socket().apply {
        try { connect(InetSocketAddress(host, port), 10_000) }
        catch (e: Exception) { close(); throw e }
    }
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(length: Int): ByteArray? {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(data, offset, length - offset)
            if (count < 0) return null
            offset += count
        }
        return data
    }

    override fun write(data: ByteArray): Boolean = runCatching {
        output.write(data)
        output.flush()
        true
    }.getOrDefault(false)

    override fun shutdown() = runCatching { socket.shutdownInput() }.let { Unit }
    override fun close() = socket.close()
}
