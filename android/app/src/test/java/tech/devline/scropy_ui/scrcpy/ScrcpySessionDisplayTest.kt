package tech.devline.scropy_ui.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrcpySessionDisplayTest {

    @Test
    fun selectsInternalDisplayInsteadOfExternalDisplayZero() {
        val dumpsys = """
            mBaseDisplayInfo=DisplayInfo{"HDMI", displayId 0, real 3840 x 1200, type EXTERNAL}
            mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 4, real 1920 x 1200, type INTERNAL}
        """.trimIndent()

        assertEquals(4, parseInternalDisplayId(dumpsys))
    }

    @Test
    fun acceptsLocalizedInternalDisplayName() {
        val dumpsys =
            "mBaseDisplayInfo=DisplayInfo{\"内蔵スクリーン\", displayId 1, real 1920 x 1200, type INTERNAL}"

        assertEquals(1, parseInternalDisplayId(dumpsys))
    }

    @Test
    fun returnsNullWhenNoInternalDisplayIsReported() {
        val dumpsys =
            "mBaseDisplayInfo=DisplayInfo{\"HDMI\", displayId 0, real 3840 x 1200, type EXTERNAL}"

        assertNull(parseInternalDisplayId(dumpsys))
    }
}
