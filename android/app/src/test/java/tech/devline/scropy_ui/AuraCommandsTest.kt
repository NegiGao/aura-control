package tech.devline.scropy_ui

import org.junit.Assert.*
import org.junit.Test

class AuraCommandsTest {
    @Test fun onlyPublicGoogle2dLaunchersAreAvailable() {
        assertEquals(11, AuraCommands.apps.size)
        assertEquals("com.google.android.youtube", AuraCommands.packages["YouTube"])
        assertEquals("com.google.android.apps.maps", AuraCommands.packages["Google Maps"])
        assertEquals("com.google.android.apps.photos", AuraCommands.packages["Google Photos"])
        AuraCommands.apps.forEach { action ->
            assertTrue(action.command.contains("android.intent.category.LAUNCHER"))
            assertFalse(action.command.contains("su 0"))
            assertFalse(action.command.contains(".xr"))
        }
    }
    @Test fun urlEscapesShellQuotes() {
        assertTrue(AuraCommands.openUrl("example.com/a'b").contains("'\\''"))
        assertEquals("cmd media_session volume --stream 3 --set 15", AuraCommands.mediaVolume(99))
    }
}
