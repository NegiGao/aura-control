package tech.devline.scropy_ui

import androidx.compose.ui.graphics.luminance

import org.junit.Assert.*
import org.junit.Test

class ExperienceTimerTest {
    private val start = 42_000L
    private val deadline = startExperienceTimer(null, start)

    @Test fun finishedUsesSaturatedRedWithHighContrastWhiteText() {
        val red = ExperiencePhase.FINISHED.background
        val white = ExperiencePhase.FINISHED.ink
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE00000), red)
        assertEquals(androidx.compose.ui.graphics.Color.White, white)
        val contrast = (white.luminance() + 0.05f) / (red.luminance() + 0.05f)
        assertTrue(contrast >= 4.5f)
    }

    @Test fun startsAtFifteenMinutesOnlyOnRequest() {
        assertEquals(ExperienceTimerDisplay(900, ExperiencePhase.READY), experienceTimerDisplay(null, start))
        assertEquals("15:00", experienceTimerDisplay(deadline, start).text)
        assertEquals(ExperiencePhase.RUNNING, experienceTimerDisplay(deadline, start).phase)
    }

    @Test fun greenUntilExactlyFourteenMinutes() {
        assertEquals(ExperiencePhase.RUNNING, experienceTimerDisplay(deadline, start + 839_999).phase)
        assertEquals("01:01", experienceTimerDisplay(deadline, start + 839_000).text)
        assertEquals(ExperiencePhase.LAST_MINUTE, experienceTimerDisplay(deadline, start + 840_000).phase)
        assertEquals("01:00", experienceTimerDisplay(deadline, start + 840_000).text)
    }

    @Test fun yellowDuringFinalMinuteAndRedAtEnd() {
        assertEquals(ExperiencePhase.LAST_MINUTE, experienceTimerDisplay(deadline, start + 899_999).phase)
        assertEquals("00:01", experienceTimerDisplay(deadline, start + 899_999).text)
        assertEquals(ExperienceTimerDisplay(0, ExperiencePhase.FINISHED), experienceTimerDisplay(deadline, start + 900_000))
        assertEquals("00:00", experienceTimerDisplay(deadline, start + 950_000).text)
    }

    @Test fun repeatedStartDoesNotExtendExperience() {
        assertEquals(deadline, startExperienceTimer(deadline, start + 60_000))
        assertEquals(deadline, startExperienceTimer(deadline, start + 950_000))
    }

    @Test fun resetRestoresIdleAndNextStartGetsFullTime() {
        val resetDeadline: Long? = null
        assertFalse(experienceTimerDisplay(resetDeadline, start + 900_000).started)
        val restarted = startExperienceTimer(resetDeadline, start + 950_000)
        assertEquals("15:00", experienceTimerDisplay(restarted, start + 950_000).text)
    }

    @Test fun elapsedTimeDoesNotDependOnRenderingOrWallClock() {
        assertEquals("05:00", experienceTimerDisplay(deadline, start + 600_000).text)
        assertEquals(ExperiencePhase.FINISHED, experienceTimerDisplay(deadline, start + 1_200_000).phase)
    }
}
