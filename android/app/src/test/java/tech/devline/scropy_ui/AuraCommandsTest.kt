package tech.devline.scropy_ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import java.net.URLEncoder
import org.junit.Test

class AuraCommandsTest {
    @Test fun worksetLaunchesExactlyFiveAppsInTheRequestedOrder() {
        val action = AuraCommands.controls.last()
        assertEquals("Gmail・Docs・YouTube・フォト・Maps 2D", action.label)
        val launches = action.command.split("; ")
        assertEquals(5, launches.size)
        listOf("com.google.android.gm/", "com.google.android.apps.docs.editors.docs/",
            "com.google.android.apps.youtube.xr/", "-p com.google.android.apps.photos",
            "com.google.android.apps.maps/").forEachIndexed { index, target ->
            assertTrue(launches[index].contains(target))
        }
        assertTrue(launches[3].contains("android.intent.category.LAUNCHER"))
    }
    @Test fun appPanelKeepsYoutubeAndMaps2dBeforeTheThreeWayXrGroup() {
        assertEquals(listOf("YouTube XR", "Google Maps 2D", "初回ガイド", "東京タワー", "竹芝ポートシティ"),
            AuraCommands.appPanelActions.take(5).map { it.label })
        assertEquals(AuraCommands.apps.take(2), AuraCommands.appPanelActions.take(2))
        assertEquals(AuraCommands.mapsXrGuide, AuraCommands.appPanelActions[2].command)
        assertEquals(AuraCommands.quickDestinations.subList(1, 3), AuraCommands.appPanelActions.subList(3, 5))
        assertEquals(AuraCommands.apps.drop(3), AuraCommands.appPanelActions.drop(5))
        assertEquals(listOf("XREAL 日本公式", "東京スカイツリー"), AuraCommands.shortcutDestinations.map { it.label })
        assertEquals(AuraCommands.controls.first { it.label == "全APPを閉じる" }, AuraCommands.closeAllApps)
    }
    @Test fun shortcutOrderAndWebsiteMatchTheRequestedDestinations() {
        assertEquals(listOf("XREAL 日本公式", "東京タワー", "竹芝ポートシティ", "東京スカイツリー"),
            AuraCommands.quickDestinations.map { it.label })
        assertEquals(AuraCommands.openUrl("https://www.xreal.com/jp/aura"),
            AuraCommands.quickDestinations[0].command)
    }

    @Test fun destinationSearchIsExplicitlyBoundToMapsXr() {
        listOf("東京タワー", "東京ポートシティ竹芝", "東京スカイツリー").forEachIndexed { index, query ->
            val command = AuraCommands.quickDestinations[index + 1].command
            assertTrue(command.contains("-n com.google.android.apps.immersiveviewxr/.ImmersiveViewXrActivity"))
            assertTrue(command.contains("-a android.intent.action.VIEW"))
            assertTrue(command.contains("geo:0,0?q=${URLEncoder.encode(query, "UTF-8")}"))
            assertFalse(command.contains("com.google.android.apps.maps/"))
        }
    }

    @Test fun placeSearchEncodesQuotesAndShellCharacters() {
        val command = AuraCommands.mapsXrSearch("a'b; & 東京")
        assertTrue(command.endsWith("'geo:0,0?q=a%27b%3B+%26+%E6%9D%B1%E4%BA%AC'"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun placeSearchRejectsEmptyQuery() { AuraCommands.mapsXrSearch(" ") }

    @Test fun dimmingUsesTheCalibratedSystemUiManager() {
        listOf("1.0", "0.7", "0.5", "0.3", "0.1").forEachIndexed { level, alpha ->
            assertEquals(
                "su 0 am broadcast -a com.android.systemui.xr.SET_PASSTHROUGH_DIMMING_ALPHA -p com.android.systemui --ef com.android.systemui.xr.PASSTHROUGH_OPACITY $alpha",
                AuraCommands.transparency(level),
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun dimmingRejectsNegativeLevels() { AuraCommands.transparency(-1) }

    @Test(expected = IllegalArgumentException::class)
    fun dimmingRejectsLevelsAboveFour() { AuraCommands.transparency(99) }

    @Test fun controllerCommandsAreBoundedAndSafe() {
        assertEquals(
            listOf(
                "YouTube XR",
                "Google Maps 2D",
                "Google Maps XR",
                "Gemini",
            ),
            AuraCommands.apps.map { it.label },
        )
        assertTrue(AuraCommands.apps.first { it.label == "Gemini" }.command.startsWith("su 0 "))
        assertEquals("su 0 cmd media_session volume --stream 3 --set 15", AuraCommands.mediaVolume(99))
        assertTrue(AuraCommands.openUrl("example.com/a'b").contains("'\\''"))
    }
}
