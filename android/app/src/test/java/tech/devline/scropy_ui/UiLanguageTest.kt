package tech.devline.scropy_ui

import org.junit.Assert.*
import org.junit.Test

class UiLanguageTest {
    @Test fun supportedLanguagesAndFallbackPreserveExistingJapaneseDefault() {
        assertEquals(listOf("zh", "en", "ja"), UiLanguage.entries.map { it.code })
        UiLanguage.entries.forEach { assertEquals(it, UiLanguage.from(it.code)) }
        assertEquals(UiLanguage.JAPANESE, UiLanguage.from(null))
        assertEquals(UiLanguage.JAPANESE, UiLanguage.from("invalid"))
    }

    @Test fun everyCatalogEntryHasTwoNonemptyTranslationsAndJapaneseRoundTrips() {
        for ((source, translations) in uiMessages) {
            assertEquals(2, translations.size)
            assertTrue(translations.all { it.isNotBlank() })
            assertEquals(source, translateUi(source, UiLanguage.JAPANESE))
            assertEquals(translations[0], translateUi(source, UiLanguage.CHINESE))
            assertEquals(translations[1], translateUi(source, UiLanguage.ENGLISH))
        }
    }

    @Test fun usbChoicesAndActualRatesAreLocalizedWithoutChangingTheNumbers() {
        assertEquals("USB 投屏", translateUi("USBで投影", UiLanguage.CHINESE))
        assertEquals("Wi-Fi casting", translateUi("Wi-Fiで投影", UiLanguage.ENGLISH))
        assertEquals("USB connected", translateUi("USB 接続中", UiLanguage.ENGLISH))
        assertEquals("Received 59.8fps / Video 2.43Mbps", translateUi("受信 59.8fps / 映像 2.43Mbps", UiLanguage.ENGLISH))
        assertEquals("接收 59.8fps / 视频 2.43Mbps", translateUi("受信 59.8fps / 映像 2.43Mbps", UiLanguage.CHINESE))
    }

    @Test fun qualityTimerAndMultilineFeedbackUseTheChosenLanguage() {
        assertEquals("Remaining 00:59", translateUi("残り 00:59", UiLanguage.ENGLISH))
        assertEquals("不足 1 分钟・00:59", translateUi("残り1分以内・00:59", UiLanguage.CHINESE))
        assertEquals("Quality Smooth (~800p) / 60fps · AURA + Beam Pro audio",
            translateUi("画質 滑らか（800p目安）/ 60fps ・ AURA + Beam Pro 音声", UiLanguage.ENGLISH))
        assertEquals("USB connected\nReceived 59.8fps / Video 2.43Mbps",
            translateUi("USB 接続中\n受信 59.8fps / 映像 2.43Mbps", UiLanguage.ENGLISH))
    }
}
