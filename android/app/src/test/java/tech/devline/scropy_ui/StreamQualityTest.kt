package tech.devline.scropy_ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamQualityTest {
    @Test
    fun auraResolutionLabelsMatchTheExistingEncodingPresets() {
        assertEquals(listOf("800p", "800p", "1000p", "1200p"),
            StreamQuality.entries.map { it.resolutionLabel })
        assertEquals(listOf(1280, 1280, 1600, 1920),
            StreamQuality.entries.map { it.maxSize })
        assertEquals(listOf(2_500_000, 4_000_000, 6_000_000, 8_000_000),
            StreamQuality.entries.map { it.videoBitRate })
    }

    @Test
    fun existingSavedSelectionsAndDefaultsStillWork() {
        StreamQuality.entries.forEach { assertEquals(it, StreamQuality.from(it.name)) }
        StreamFrameRate.entries.forEach { assertEquals(it, StreamFrameRate.from(it.name)) }
        assertEquals(StreamQuality.SMOOTH, StreamQuality.from(null))
        assertEquals(StreamFrameRate.FPS_60, StreamFrameRate.from(null))
        assertEquals(listOf("30fps", "60fps"), StreamFrameRate.entries.map { it.label })
    }
}
