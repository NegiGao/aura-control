package tech.devline.scropy_ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamStatisticsTest {
    @Test fun compactStatusPreservesEveryMetricWithoutSourceLineBreaks() {
        assertEquals(listOf("USB 接続中", "1280×800", "59.8fps 2.43Mbps"),
            streamStatisticsParts("USB 接続中", "1280 × 800\n受信 59.8fps / 映像 2.43Mbps"))
        assertEquals(listOf("Wi-Fi 接続中", "1920×1200", "— fps — Mbps"),
            streamStatisticsParts("Wi-Fi 接続中", "1920 × 1200\n$WAITING_VIDEO_RATES\n"))
    }

    @Test fun compactStatusKeepsWarningsAsSeparateTranslationUnits() {
        val warning = "音声転送が停止しました。映像と AURA の音声は継続します。"
        val parts = streamStatisticsParts("USB 接続中", "1280 × 800\n受信 59.8fps / 映像 2.43Mbps\n$warning")
        assertEquals(warning, parts.last())
        val english = parts.joinToString(" · ") { translateUi(it, UiLanguage.ENGLISH) }
        assertTrue(english.startsWith("USB connected · 1280×800 · 59.8fps 2.43Mbps"))
        assertFalse(english.contains("音声転送"))
        assertFalse(english.contains('\n'))
    }

    @Test fun countsReceivedFramesAndVideoPayloadInActualElapsedTime() {
        val sampler = VideoRateSampler(1000)
        val first = sampler.sample(2000, 60, 312500)!!
        assertEquals(60.0, first.framesPerSecond, 0.0001)
        assertEquals(2.5, first.megabitsPerSecond, 0.0001)
        assertEquals("受信 60.0fps / 映像 2.50Mbps", first.label)
        val delayed = sampler.sample(4000, 120, 812500)!!
        assertEquals(30.0, delayed.framesPerSecond, 0.0001)
        assertEquals(2.0, delayed.megabitsPerSecond, 0.0001)
    }

    @Test fun idleStreamShowsZeroRatherThanTheConfiguredSixtyFps() {
        val sampler = VideoRateSampler(0)
        sampler.sample(1000, 60, 500000)
        assertEquals("受信 0.0fps / 映像 0.00Mbps", sampler.sample(2000, 60, 500000)!!.label)
    }

    @Test fun invalidElapsedTimeCannotDivideByZeroOrConsumeCounters() {
        val sampler = VideoRateSampler(1000)
        assertNull(sampler.sample(1000, 20, 100000))
        assertNull(sampler.sample(999, 20, 100000))
        assertEquals(30.0, sampler.sample(2000, 30, 500000)!!.framesPerSecond, 0.0001)
    }

    @Test fun newSessionHasNoOldCountersAndResetCannotShowNegativeRates() {
        val sampler = VideoRateSampler(0)
        sampler.sample(1000, 60, 500000)
        assertEquals(VideoRates(0.0, 0.0), sampler.sample(2000, 1, 100))
        assertEquals(VideoRates(30.0, 4.0), VideoRateSampler(2000).sample(3000, 30, 500000))
    }

    @Test fun transportLabelFollowsTheAdbTransportNotTheOutputDisplay() {
        assertEquals("USB 接続中", streamConnectionStatus(true, true, false))
        assertEquals("Wi-Fi 接続中", streamConnectionStatus(false, true, false))
        assertEquals("USB 接続待機中…", streamConnectionStatus(true, false, false))
        assertEquals("Wi-Fi 接続失敗", streamConnectionStatus(false, false, true))
        assertEquals("受信 — fps / 映像 — Mbps", WAITING_VIDEO_RATES)
    }
}
