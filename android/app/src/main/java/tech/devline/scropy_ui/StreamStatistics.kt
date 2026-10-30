package tech.devline.scropy_ui

import java.util.Locale

internal const val WAITING_VIDEO_RATES = "受信 — fps / 映像 — Mbps"

/** Keep separate translation units before joining the compact, scrollable status line. */
internal fun streamStatisticsParts(connectionStatus: String, videoRates: String): List<String> =
    listOf(connectionStatus) + videoRates.lineSequence().filter { it.isNotBlank() }.map {
        it.trim().removePrefix("受信 ").replace(" / 映像 ", " ").replace(" × ", "×")
    }.toList()

internal data class VideoRates(val framesPerSecond: Double, val megabitsPerSecond: Double) {
    val label: String
        get() = String.format(Locale.ROOT, "受信 %.1ffps / 映像 %.2fMbps", framesPerSecond, megabitsPerSecond)
}

/** Receiver throughput, not the encoder's configured limits or the display refresh rate. */
internal class VideoRateSampler(startedAtMs: Long) {
    private var lastTimeMs = startedAtMs
    private var lastFrames = 0L
    private var lastBytes = 0L

    fun sample(nowMs: Long, frames: Long, bytes: Long): VideoRates? {
        val elapsed = nowMs - lastTimeMs
        if (elapsed <= 0) return null
        val rates = VideoRates(
            (frames - lastFrames).coerceAtLeast(0).toDouble() * 1000 / elapsed,
            (bytes - lastBytes).coerceAtLeast(0).toDouble() * 8 / (elapsed * 1000.0),
        )
        lastTimeMs = nowMs
        lastFrames = frames
        lastBytes = bytes
        return rates
    }
}

internal fun streamConnectionStatus(usb: Boolean, connected: Boolean, failed: Boolean): String {
    val transport = if (usb) "USB" else "Wi-Fi"
    return "$transport ${if (connected) "接続中" else if (failed) "接続失敗" else "接続待機中…"}"
}
