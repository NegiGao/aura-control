package tech.devline.scropy_ui.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents one logical ADB stream (one OPEN/WRTE/OKAY/CLSE conversation).
 * Data received from the device is readable via [inputStream].
 * Use [write] to send data to the device.
 */
class AdbStream internal constructor(val localId: Int) {

    @Volatile internal var remoteId: Int = 0

    /**
     * ADB multiplexes video, audio and control on one reader thread.  A regular
     * PipedInputStream blocks that shared thread as soon as one consumer is a
     * little slower (for example while MediaCodec is recycling buffers), which
     * freezes every stream.  Queue chunks here instead so each consumer can
     * catch up independently without blocking the ADB connection.
     */
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicInteger(0)
    private val endOfStream = ByteArray(0)
    /** Read device→app data from this stream. */
    val inputStream: InputStream = object : InputStream() {
        private var current = ByteArray(0)
        private var offset = 0
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (ended) return -1
            while (offset >= current.size) {
                current = incoming.take()
                offset = 0
                if (current === endOfStream) { ended = true; return -1 }
            }
            val count = minOf(len, current.size - offset)
            current.copyInto(buffer, off, offset, offset + count)
            offset += count
            queuedBytes.addAndGet(-count)
            return count
        }

        override fun available(): Int = queuedBytes.get().coerceAtLeast(0)
        override fun close() = this@AdbStream.close()
    }

    companion object {
        // ADB max payload per WRTE message (safe for all devices).
        internal const val MAX_PAYLOAD = 4096
        // About 32 seconds at the default 4 Mbps video rate. If a consumer is
        // stuck for longer, close this stream instead of risking an OOM.
        private const val MAX_QUEUED_BYTES = 16 * 1024 * 1024
    }

    /** Writable side — uses flow-controlled [write] internally. */
    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = this@AdbStream.write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            val end = off + len
            while (pos < end) {
                val chunk = minOf(end - pos, MAX_PAYLOAD)
                this@AdbStream.write(b.copyOfRange(pos, pos + chunk))
                pos += chunk
            }
        }
        override fun write(b: ByteArray) = write(b, 0, b.size)
    }

    // Flow-control: ADB requires waiting for OKAY before sending next WRTE
    // Starts at 0 — first permit is released when device sends OKAY for OPEN
    private val writeLock = Semaphore(0)
    private val readyLatch = CountDownLatch(1)

    @Volatile private var closed = false

    // Callbacks supplied by AdbConnection
    internal lateinit var writeToDevice: (ByteArray) -> Unit
    internal lateinit var sendOkay: () -> Unit
    internal lateinit var onCloseCallback: () -> Unit

    // ─── Called by AdbConnection dispatcher ──────────────────────────────

    internal fun onData(data: ByteArray) {
        if (closed) return
        val total = queuedBytes.addAndGet(data.size)
        if (total > MAX_QUEUED_BYTES) {
            queuedBytes.addAndGet(-data.size)
            close()
            return
        }
        incoming.offer(data)
        // Acknowledge only after the data is safely queued, but never wait for
        // MediaCodec/AudioTrack on the shared ADB reader thread.
        sendOkay()
    }

    internal fun onOkay() {
        readyLatch.countDown()
        writeLock.release()
    }

    internal fun onClose() {
        readyLatch.countDown()  // unblock waitReady if stream was rejected
        closed = true
        writeLock.release()
        // A shell command commonly sends its final output immediately before
        // CLSE. Preserve those already queued bytes and put EOF behind them.
        incoming.offer(endOfStream)
    }

    // ─── Public API ────────────────────────────────────────────────────────

    /** Block until the device acknowledges OPEN (sets remoteId). */
    fun waitReady(timeoutMs: Long = 5000) {
        if (!readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS))
            throw IOException("ADB stream open timed out")
        if (closed) throw IOException("ADB stream was closed")
    }

    fun write(data: ByteArray) {
        if (closed) throw IOException("Stream closed")
        // Chunk to MAX_PAYLOAD — each chunk is a separate WRTE/OKAY cycle
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + MAX_PAYLOAD, data.size)
            val chunk = if (offset == 0 && end == data.size) data else data.copyOfRange(offset, end)
            if (!writeLock.tryAcquire(5, TimeUnit.SECONDS))
                throw IOException("ADB WRTE timed out waiting for OKAY")
            if (closed) throw IOException("Stream closed")
            writeToDevice(chunk)
            offset = end
        }
    }

    fun close() {
        if (!closed) {
            closed = true
            readyLatch.countDown()
            writeLock.release()
            incoming.clear()
            queuedBytes.set(0)
            incoming.offer(endOfStream)
            onCloseCallback()
        }
    }

    fun isClosed() = closed
}
