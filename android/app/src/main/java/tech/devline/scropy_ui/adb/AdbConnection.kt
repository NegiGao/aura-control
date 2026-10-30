package tech.devline.scropy_ui.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A full ADB connection over any transport (TLS or USB).
 *
 * This class handles CNXN, AUTH and stream management over the given [AdbTransport].
 */
class AdbConnection internal constructor(
    private val transport: AdbTransport,
    private val privKey: java.security.PrivateKey,
    private val pubKey:  ByteArray,
) {
    companion object {
        private const val TAG = "AdbConnection"

        suspend fun connectTcp(context: Context, host: String, port: Int = 5555): AdbConnection =
            connect(context) { TcpAdbTransport(host, port) }

        suspend fun connectUsb(context: Context, device: UsbDevice, usbManager: UsbManager): AdbConnection =
            connect(context) { UsbAdbTransport.open(device, usbManager) }

        private suspend fun connect(context: Context, transport: () -> AdbTransport): AdbConnection {
            var connection: AdbConnection? = null
            try {
                return withContext(Dispatchers.IO) {
                    val (priv, pub) = AdbAuthHelper.getOrCreateKeys(context)
                    currentCoroutineContext().ensureActive()
                    val conn = AdbConnection(transport(), priv, pub)
                    connection = conn
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { conn.close() }
                        if (!continuation.isActive) return@suspendCancellableCoroutine
                        try {
                            conn.sendCnxn()
                            conn.startReader()
                            conn.waitConnected()
                            continuation.resume(conn) { _, value, _ -> value.close() }
                        } catch (e: Exception) {
                            conn.close()
                            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                        }
                    }
                }
            } catch (e: Exception) {
                connection?.close()
                throw e
            }
        }
    }

    private val localIdGen = AtomicInteger(1)
    private val streams    = ConcurrentHashMap<Int, AdbStream>()
    private val alive      = AtomicBoolean(true)
    private val writeLock  = Any()  // guards send() so header+data are atomic

    private val connectedLatch = CountDownLatch(1)
    private val signatureSent = AtomicBoolean(false)
    private val pubKeySent    = AtomicBoolean(false)
    private lateinit var readerThread: Thread
    @Volatile private var disconnectListener: (() -> Unit)? = null
    private val listenerLock = Any()

    fun setDisconnectListener(listener: (() -> Unit)?) {
        synchronized(listenerLock) {
            disconnectListener = listener
            if (listener != null && !alive.get()) {
                disconnectListener = null
                runCatching(listener)
            }
        }
    }

    fun isAlive(): Boolean = alive.get()
    var verifiedSerial: String? = null
        private set

    private fun startReader() {
        readerThread = Thread(::readLoop, "adb-reader").also { it.isDaemon = true; it.start() }
    }

    // ─── CNXN + Connection wait ─────────────────────────────────────────────

    private fun sendCnxn() {
        val banner = "host::features=stat_v2,ls_v2,apex,abb,fixed_push_symlink_timestamp"
        send(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.ADB_MAX_DATA,
             banner.toByteArray())
    }

    private fun waitConnected() {
        if (!connectedLatch.await(30, TimeUnit.SECONDS))
            throw IOException("ADB auth timed out (re-pair needed?)")
        if (!alive.get()) throw IOException("ADB 接続が切れました。USB ケーブルを接続し直してください")
    }

    // ─── Reader loop ────────────────────────────────────────────────────────

    private fun readLoop() {
        try {
            while (alive.get()) {
                val msg = readMsg() ?: break
                dispatch(msg)
            }
        } catch (e: IOException) {
            if (alive.get()) Log.e(TAG, "ADB read error", e)
        } finally {
            failConnection()
        }
    }

    private fun readMsg(): AdbProtocol.Msg? {
        val hdr = transport.read(24) ?: return null
        if (hdr.size < 24) return null
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.int; val a0 = buf.int; val a1 = buf.int
        val len = buf.int; buf.int; buf.int   // skip checksum + magic
        val data = if (len > 0) {
            transport.read(len) ?: return null
        } else ByteArray(0)
        return AdbProtocol.Msg(cmd, a0, a1, data)
    }

    private fun dispatch(msg: AdbProtocol.Msg) {
        when (msg.cmd) {
            // ── Connection / auth ────────────────────────────────────────
            AdbProtocol.A_CNXN -> {
                Log.i(TAG, "ADB connected: ${String(msg.data)}")
                connectedLatch.countDown()
            }
            AdbProtocol.A_AUTH -> when (msg.arg0) {
                AdbProtocol.ADB_AUTH_TOKEN -> {
                    if (!signatureSent.getAndSet(true)) {
                        // First AUTH_TOKEN — try signing with our key
                        val sig = AdbAuthHelper.sign(privKey, msg.data)
                        send(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, sig)
                    } else if (!pubKeySent.getAndSet(true)) {
                        // Signature rejected — send our public key once
                        Log.i(TAG, "Sending ADB public key — please tap Allow on the device")
                        send(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, pubKey)
                    } else {
                        // Public key already sent, waiting for user to Accept
                        Log.d(TAG, "Ignoring AUTH_TOKEN — waiting for user to Allow")
                    }
                }
                else -> {
                    if (!pubKeySent.getAndSet(true)) {
                        Log.i(TAG, "Sending ADB public key — please tap Allow on the device")
                        send(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, pubKey)
                    } else {
                        Log.d(TAG, "Ignoring AUTH — waiting for user to Allow")
                    }
                }
            }

            // ── Stream events ────────────────────────────────────────────
            AdbProtocol.A_OKAY -> {
                val stream = streams[msg.arg1] ?: return
                stream.remoteId = msg.arg0
                stream.onOkay()
            }
            AdbProtocol.A_WRTE -> {
                val stream = streams[msg.arg1] ?: return
                stream.onData(msg.data)
            }
            AdbProtocol.A_CLSE -> {
                streams.remove(msg.arg1)?.onClose()
            }
            else -> Log.w(TAG, "Unknown ADB cmd 0x${msg.cmd.toString(16)}")
        }
    }

    // ─── Public API ─────────────────────────────────────────────────────────

    fun open(service: String, timeoutMs: Long = 5000): AdbStream {
        if (!alive.get()) throw IOException("ADB connection closed")
        val localId = localIdGen.getAndIncrement()
        val stream  = AdbStream(localId)

        stream.writeToDevice   = { data -> send(AdbProtocol.A_WRTE, localId, stream.remoteId, data) }
        stream.sendOkay        = {        send(AdbProtocol.A_OKAY, localId, stream.remoteId)        }
        stream.onCloseCallback = {
            streams.remove(localId)
            send(AdbProtocol.A_CLSE, localId, stream.remoteId)
        }

        streams[localId] = stream
        send(AdbProtocol.A_OPEN, localId, 0, (service + "\u0000").toByteArray())

        try {
            stream.waitReady(timeoutMs)
            return stream
        } catch (e: Exception) {
            stream.close()
            throw e
        }
    }

    fun readCommand(service: String, timeoutMs: Long = 8000): String =
        readService(service, timeoutMs, 1024 * 1024).toString(Charsets.UTF_8).trim()

    fun verifyDevice(expectedSerial: String?): String =
        checkedDeviceSerial(readCommand("shell:getprop ro.serialno"), expectedSerial).also { verifiedSerial = it }

    fun readService(service: String, timeoutMs: Long = 8000, maxBytes: Int = 16 * 1024 * 1024): ByteArray {
        val started = System.nanoTime()
        val stream = open(service, minOf(timeoutMs, 5000))
        try {
            val remaining = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            if (remaining <= 0) throw java.net.SocketTimeoutException("ADB operation timed out")
            return withAdbDeadline(remaining, { stream.close() }) {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (true) {
                    val count = stream.inputStream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) throw IOException("ADB response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { stream.close() }
    }

    fun close() {
        failConnection()
    }

    // ─── Internal helpers ──────────────────────────────────────────────────

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        try {
            val sum = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
            val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).also {
                it.putInt(cmd); it.putInt(arg0); it.putInt(arg1)
                it.putInt(data.size); it.putInt(sum); it.putInt(cmd xor -1)
            }.array()
            // Serialize all writes so header+data pairs never interleave
            // between threads (reader sending OKAY vs. writer sending WRTE).
            synchronized(writeLock) {
                if (!transport.write(hdr)) {
                    throw IOException("Transport write failed (header)")
                }
                if (data.isNotEmpty()) {
                    if (!transport.write(data)) {
                        throw IOException("Transport write failed (data)")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "ADB write error", e)
            failConnection()
        }
    }

    /** Close every logical stream immediately after a transport failure. */
    private fun failConnection() {
        if (!alive.getAndSet(false)) return
        runCatching { transport.shutdown() }
        runCatching { transport.close() }
        streams.values.forEach { it.onClose() }
        streams.clear()
        connectedLatch.countDown()
        synchronized(listenerLock) {
            val listener = disconnectListener
            disconnectListener = null
            listener?.let { runCatching(it) }
        }
    }
}
