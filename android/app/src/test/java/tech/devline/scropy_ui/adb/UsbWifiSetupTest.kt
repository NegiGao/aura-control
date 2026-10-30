package tech.devline.scropy_ui.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbWifiSetupTest {
    @Test fun readsOnlyValidWifiAddresses() {
        assertEquals("192.168.0.199", wifiIpv4("16: wlan0 inet 192.168.0.199/24 brd 192.168.0.255"))
        assertNull(wifiIpv4("inet 999.168.0.1/24"))
        assertNull(wifiIpv4("inet 127.0.0.1/8"))
        assertNull(wifiIpv4("inet 169.254.1.2/16"))
        assertNull(wifiIpv4(""))
    }
}
