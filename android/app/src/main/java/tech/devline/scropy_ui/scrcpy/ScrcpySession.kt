package tech.devline.scropy_ui.scrcpy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.devline.scropy_ui.adb.AdbConnection
import tech.devline.scropy_ui.adb.AdbStream
import tech.devline.scropy_ui.adb.AdbSync
import tech.devline.scropy_ui.adb.withAdbDeadline
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private const val TAG = "ScrcpySession"

private val DISPLAY_ID_PATTERN = Regex("""\bdisplayId\s+(\d+)\b""")

/** Select the device's own panel rather than an attached XR/HDMI display. */
internal fun parseInternalDisplayId(dumpsys: String): Int? =
    dumpsys.lineSequence()
        .filter { "DisplayInfo{" in it && "type INTERNAL" in it }
        .mapNotNull { line ->
            DISPLAY_ID_PATTERN.find(line)?.groupValues?.get(1)?.toIntOrNull()
        }
        .firstOrNull()

/** Describes the remote device after a successful connection. */
data class DeviceInfo(
    val name: String,
    val videoCodec: Int,
    val audioCodec: Int,    // 0 if audio disabled
    val width: Int,
    val height: Int,
)

/**
 * Manages the full scrcpy session lifecycle:
 *  1. Push the embedded scrcpy-server binary to the device via ADB SYNC
 *  2. Launch the server via ADB shell
 *  3. Open three ADB streams (video / audio / control) to the server's local socket
 *  4. Return open streams for [VideoDecoder], [AudioPlayer] and [ControlSender]
 */
class ScrcpySession private constructor(
    val deviceInfo: DeviceInfo,
    val videoStream:   AdbStream,
    val audioStream:   AdbStream?,
    val controlStream: AdbStream,
    private val shellStream: AdbStream,
) {

    companion object {

        private const val DEVICE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar"
        private const val SERVER_ASSET       = "scrcpy-server"
        private const val SCRCPY_VERSION     = "4.1"

        /**
         * Connect to a running [adb] session and start a scrcpy session.
         *
         * @param enableAudio   Request audio forwarding (Android 11+).
         * @param maxSize       0 = no limit; otherwise long-edge pixel cap.
         * @param maxFps        0 = server default; otherwise cap frame rate (battery saver).
         * @param videoBitRate  0 = server default (~8 Mbps); otherwise bits/sec.
         */
        suspend fun start(
            context: Context,
            adb: AdbConnection,
            enableAudio: Boolean = true,
            maxSize: Int = 0,
            maxFps: Int = 0,
            videoBitRate: Int = 0,
        ): ScrcpySession = withContext(Dispatchers.IO) {
            val opened = mutableListOf<AdbStream>()
            var completed = false
            fun open(service: String, timeoutMs: Long = 5000): AdbStream =
                adb.open(service, timeoutMs).also { opened.add(it) }
            try {
                val result = withAdbDeadline(45_000, { adb.close() }) {

                    // ── 1. Push server JAR (skip if already up-to-date) ────────────
                    val serverBytes = loadServerAsset(context)
                    if (needsPush(adb, serverBytes)) {
                        Log.d(TAG, "Pushing server (${serverBytes.size} bytes)…")
                        val syncStream = open("sync:")
                        AdbSync(syncStream.inputStream, syncStream.outputStream)
                            .pushFile(serverBytes, DEVICE_SERVER_PATH)
                        syncStream.close()
                        Log.d(TAG, "Server pushed.")
                    } else {
                        Log.d(TAG, "Server already up-to-date, skipping push.")
                    }

                    // ── 2. Generate a random session ID (31-bit, server uses Integer.parseInt) ─
                    val scid = (Math.random() * 0x7FFFFFFFL).toLong().and(0x7FFFFFFFL)
                    val scidHex = "%08x".format(scid)
                    val socketName = "scrcpy_$scidHex"

                    // On AURA the external XR/HDMI surface may become display 0 while
                    // the actual device UI moves to another id.  scrcpy defaults to 0,
                    // producing a 3840x1200 ultra-wide strip.  Resolve type INTERNAL
                    // on every connection because ids may vary between devices/boots.
                    val internalDisplayId = resolveInternalDisplayId(adb)

                    // ── 3. Build shell command ───────────────────────────────────────
                    val cmdParts = mutableListOf(
                        "CLASSPATH=$DEVICE_SERVER_PATH",
                        "app_process",
                        "/",
                        "com.genymobile.scrcpy.Server",
                        SCRCPY_VERSION,
                        "scid=$scidHex",
                        "log_level=info",
                        "video=true",
                        "control=true",
                        "tunnel_forward=true",
                        "power_on=true",
                        "stay_awake=true",
                    )
                    if (enableAudio) {
                        // Keep playback active on AURA while forwarding the same audio
                        // stream to Beam Pro.  This is scrcpy's equivalent of --audio-dup.
                        cmdParts += "audio_source=playback"
                        cmdParts += "audio_dup=true"
                    } else {
                        cmdParts += "audio=false"
                    }
                    if (maxSize > 0) cmdParts += "max_size=$maxSize"
                    if (maxFps > 0) cmdParts += "max_fps=$maxFps"
                    if (videoBitRate > 0) cmdParts += "video_bit_rate=$videoBitRate"
                    if (internalDisplayId != null) {
                        cmdParts += "display_id=$internalDisplayId"
                        Log.i(TAG, "Using internal display id=$internalDisplayId")
                    } else {
                        Log.w(TAG, "Internal display id not found; using scrcpy default")
                    }

                    val shellCmd = "shell:" + cmdParts.joinToString(" ")
                    Log.d(TAG, "Starting server: $shellCmd")
                    val shellStream = open(shellCmd)

                    // Read server stdout/stderr in background for diagnostics
                    Thread({
                        try {
                            val buf = ByteArray(1024)
                            while (!shellStream.isClosed()) {
                                val r = shellStream.inputStream.read(buf)
                                if (r <= 0) break
                                Log.i("ScrcpyServer", String(buf, 0, r).trimEnd())
                            }
                        } catch (_: Exception) {}
                    }, "scrcpy-server-log").apply { isDaemon = true; start() }

                    // ── 4. Connect stream sockets (with retry) ───────────────────────
                    // The server needs time to start and create its LocalServerSocket.
                    // Include OPEN's acknowledgement time in the five-second budget.
                    fun openWithRetry(dest: String): AdbStream {
                        val deadline = System.nanoTime() + 5_000_000_000L
                        var lastErr: Exception? = null
                        while (System.nanoTime() < deadline && adb.isAlive()) {
                            try {
                                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
                                return open(dest, minOf(1000, remaining))
                            } catch (e: IOException) {
                                lastErr = e
                                Thread.sleep(100)
                            }
                        }
                        throw lastErr ?: IOException("Failed to connect to $dest")
                    }

                    val socketDest = "localabstract:$socketName"
                    val videoStream   = openWithRetry(socketDest)
                    Log.d(TAG, "Video socket connected")
                    val audioStream   = if (enableAudio) openWithRetry(socketDest) else null
                    if (enableAudio) Log.d(TAG, "Audio socket connected")
                    val controlStream = openWithRetry(socketDest)
                    Log.d(TAG, "Control socket connected")

                    // ── 5. Read handshake from video socket ──────────────────────────
                    val dis = DataInputStream(videoStream.inputStream)

                    // Discard the 1-byte dummy (send_dummy_byte=true by default)
                    if (dis.read() < 0) throw IOException("Video handshake closed")

                    // 64-byte device name (null-padded UTF-8)
                    val nameBytes = ByteArray(ScrcpyProtocol.DEVICE_NAME_LEN)
                    dis.readFully(nameBytes)
                    val deviceName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')

                    // scrcpy 4.x video meta: header is codec_id(4) only, then the first
                    // packet is a "session-meta" packet — a 12-byte header with the MSB set
                    // carrying flags(4) + width(4) + height(4) and no payload.
                    val codecId       = dis.readInt()
                    val sessionFlags  = dis.readInt()
                    val width         = dis.readInt()
                    val height        = dis.readInt()
                    if (width !in 1..16384 || height !in 1..16384) throw IOException("Invalid video dimensions")
                    if (sessionFlags and (1 shl 31) == 0) {
                        Log.w(TAG, "Expected session-meta packet (MSB set) but got flags=0x${sessionFlags.toString(16)}")
                    }
                    Log.i(TAG, "Device: $deviceName  codec=0x${codecId.toString(16)}  ${width}x${height}")

                    // Read audio codec ID (first 4 bytes of audio socket)
                    // 0x0 = disabled (device does not support audio forwarding, requires Android 11+)
                    // 0x1 = configuration error
                    val audioCodecId = if (audioStream != null) {
                        try {
                            val id = withAdbDeadline(5000, { audioStream.close() }) {
                                DataInputStream(audioStream.inputStream).readInt()
                            }
                            when (id) {
                                0 -> Log.w(TAG, "Audio disabled by server (requires Android 11+)")
                                1 -> Log.w(TAG, "Audio error on server (configuration error)")
                                else -> Log.i(TAG, "Audio codec id: 0x${id.toString(16)}")
                            }
                            if (id == 1) 0 else id
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read audio codec id", e)
                            0
                        }
                    } else 0

                    val info = DeviceInfo(deviceName, codecId, audioCodecId, width, height)
                    if (audioCodecId == 0) audioStream?.close()
                    ScrcpySession(info, videoStream, audioStream?.takeIf { audioCodecId != 0 }, controlStream, shellStream)
                }
                completed = true
                result
            } finally {
                if (!completed) opened.forEach { runCatching { it.close() } }
            }
        }

        private fun loadServerAsset(context: Context): ByteArray {
            return runCatching {
                context.assets.open(SERVER_ASSET).use { it.readBytes() }
            }.getOrElse {
                throw IOException(
                    "scrcpy-server not found in app assets.\n" +
                    "Build it first:\n" +
                    "  cd /path/to/scrcpy && ./gradlew :server:assembleRelease\n" +
                    "Then rebuild this app."
                )
            }
        }

        private fun resolveInternalDisplayId(adb: AdbConnection): Int? {
            return try {
                parseInternalDisplayId(adb.readCommand("shell:dumpsys display", 4000))
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve internal display id", e)
                null
            }
        }

        /**
         * Check if the server JAR on the device is already up-to-date.
         * Compares md5 checksum to avoid redundant pushes.
         */
        private fun needsPush(adb: AdbConnection, localBytes: ByteArray): Boolean {
            return try {
                val localMd5 = md5Hex(localBytes)
                val output = adb.readCommand("shell:md5sum $DEVICE_SERVER_PATH 2>/dev/null", 3000)
                val remoteMd5 = output.split("\\s+".toRegex()).firstOrNull() ?: ""
                Log.d(TAG, "Server md5 local=$localMd5 remote=$remoteMd5")
                localMd5 != remoteMd5
            } catch (e: Exception) {
                Log.w(TAG, "Could not check remote server, will push", e)
                true
            }
        }

        private fun md5Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5").digest(data)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    fun close() {
        runCatching { videoStream.close() }
        runCatching { audioStream?.close() }
        runCatching { controlStream.close() }
        runCatching { shellStream.close() }
    }
}
