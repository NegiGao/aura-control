package tech.devline.scropy_ui

import org.junit.Assert.*
import org.junit.Test

class AppExperienceTimerTest {
    @Test fun appTimersAreUniformAndNotADemoSchedule() {
        assertEquals(setOf(120), experienceApps.map { it.seconds }.toSet())
        AuraCommands.apps.forEach { assertNotNull(experienceAppForCommand(it.command)) }
        val first = nextAppCountdown(null, AuraCommands.apps.first().command, true, 1000)!!
        assertEquals("02:00", first.display(1000).text)
        assertSame(first, nextAppCountdown(first, AuraCommands.apps.first().command, true, 2000))
        assertNull(nextAppCountdown(first, AuraCommands.closeAllApps.command, true, 2000))
    }
}
