package tech.devline.scropy_ui

import org.junit.Assert.*
import org.junit.Test

class AppExperienceTimerTest {
    private fun launch(label: String) = AuraCommands.apps.first { it.label == label }.command

    @Test fun eachExperienceHasThePlannedBudget() {
        assertEquals(listOf(120, 30, 150, 60, 60), experienceApps.map { it.seconds })
        AuraCommands.apps.forEach { assertEquals(it.label, experienceAppForCommand(it.command)?.label) }
    }

    @Test fun successfulSwitchStartsAnIndependentCountdown() {
        val youtube = nextAppCountdown(null, launch("YouTube XR"), true, 1000)!!
        assertEquals("02:00", youtube.display(1000).text)
        assertEquals("01:00", youtube.display(61_000).text)
        val maps = nextAppCountdown(youtube, launch("Google Maps 2D"), true, 65_000)!!
        assertEquals("Google Maps 2D", maps.app.label)
        assertEquals("00:30", maps.display(65_000).text)
        assertEquals("00:00", maps.display(100_000).text)
    }

    @Test fun failedLaunchAndUnrelatedCommandsNeverResetTheCountdown() {
        val current = nextAppCountdown(null, launch("YouTube XR"), true, 1000)
        assertSame(current, nextAppCountdown(current, launch("Google Maps 2D"), false, 20_000))
        assertSame(current, nextAppCountdown(current, AuraCommands.mediaVolume(8), true, 20_000))
        assertSame(current, nextAppCountdown(current, launch("YouTube XR"), true, 20_000))
    }

    @Test fun mapTutorialAndLandmarkChangesDoNotRestartTheSameApp() {
        val maps = nextAppCountdown(null, AuraCommands.mapsXrGuide, true, 1000)
        assertEquals("Google Maps XR", maps?.app?.label)
        AuraCommands.quickDestinations.drop(1).forEach { destination ->
            assertSame(maps, nextAppCountdown(maps, destination.command, true, 25_000))
        }
        assertSame(maps, nextAppCountdown(maps, launch("Google Maps XR"), true, 25_000))
    }

    @Test fun closeHidesOnlyTheAppThatActuallyClosed() {
        val youtube = nextAppCountdown(null, launch("YouTube XR"), true, 1000)
        assertSame(youtube, nextAppCountdown(youtube, AuraCommands.geminiClose, true, 2000))
        val gemini = nextAppCountdown(youtube, launch("Gemini"), true, 2000)
        assertNull(nextAppCountdown(gemini, AuraCommands.geminiClose, true, 3000))
        val closeAll = AuraCommands.controls.first { it.label == "全APPを閉じる" }.command
        assertSame(youtube, nextAppCountdown(youtube, closeAll, false, 3000))
        assertNull(nextAppCountdown(youtube, closeAll, true, 3000))
    }

    @Test fun taskVerificationMatchesExactPackageComponents() {
        val app = experienceApps.first()
        assertTrue(hasAppTask("taskId=2: ${app.packageName}/.MainActivity bounds=[0,0]", app))
        assertFalse(hasAppTask("taskId=2: ${app.packageName}.fake/.MainActivity", app))
        assertFalse(hasAppTask("taskId=2: fake.${app.packageName}/.MainActivity", app))
        assertFalse(hasAppTask("Permission denial", app))
    }

    @Test fun closingAnAppDoesNotChangeTheFifteenMinuteDeadline() {
        val totalDeadline = startExperienceTimer(null, 1000)
        val current = nextAppCountdown(null, launch("Google Maps 2D"), true, 50_000)
        assertNull(nextAppCountdown(current, AuraCommands.controls.first { it.label == "全APPを閉じる" }.command,
            true, 60_000))
        assertEquals("14:01", experienceTimerDisplay(totalDeadline, 60_000).text)
    }

    @Test fun externalCloseHidesOnlyAfterAValidTaskSnapshot() {
        val current = nextAppCountdown(null, launch("YouTube XR"), true, 1000)
        assertSame(current, appCountdownAfterTaskSnapshot(current, "permission denial"))
        assertSame(current, appCountdownAfterTaskSnapshot(current, ""))
        assertSame(current, appCountdownAfterTaskSnapshot(current, "__AURA_ALIVE__"))
        assertSame(current, appCountdownAfterTaskSnapshot(current,
            "RootTask id=2\n taskId=4: com.google.android.apps.youtube.xr/.Main"))
        assertNull(appCountdownAfterTaskSnapshot(current, "RootTask id=2\n taskId=5: com.android.launcher/.Main"))
    }
}
