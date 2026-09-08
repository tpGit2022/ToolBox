package com.seeksky.toolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiScanModelsTest {
    @Test
    fun channel_converts24GhzIncludingChannel14() {
        assertEquals(1, wifiChannel(2412))
        assertEquals(6, wifiChannel(2437))
        assertEquals(13, wifiChannel(2472))
        assertEquals(14, wifiChannel(2484))
    }

    @Test
    fun channel_converts49And5Ghz() {
        assertEquals(182, wifiChannel(4910))
        assertEquals(36, wifiChannel(5180))
        assertEquals(100, wifiChannel(5500))
        assertEquals(149, wifiChannel(5745))
        assertEquals(177, wifiChannel(5885))
    }

    @Test
    fun channel_converts6GhzIncludingSpecialChannel2() {
        assertEquals(2, wifiChannel(5935))
        assertEquals(1, wifiChannel(5955))
        assertEquals(5, wifiChannel(5975))
        assertEquals(233, wifiChannel(7115))
    }

    @Test
    fun channel_converts60Ghz() {
        assertEquals(1, wifiChannel(58320))
        assertEquals(6, wifiChannel(69120))
    }

    @Test
    fun channel_rejectsUnknownAndMisalignedFrequencies() {
        listOf(0, -1, 2400, 2413, 2485, 5181, 5900, 5956, 7125, 58321, 72000).forEach { frequency ->
            assertNull("frequency=$frequency", wifiChannel(frequency))
        }
    }

    @Test
    fun band_distinguishesOverlappingChannelNumbers() {
        assertEquals("2.4 GHz", wifiBand(2412))
        assertEquals("4.9 GHz", wifiBand(4910))
        assertEquals("5 GHz", wifiBand(5180))
        assertEquals("6 GHz", wifiBand(5935))
        assertEquals("6 GHz", wifiBand(5955))
        assertEquals("60 GHz", wifiBand(58320))
        assertEquals("未知频段", wifiBand(0))
    }

    @Test
    fun security_recognizesPersonalAndTransitionModes() {
        assertEquals("WPA2-Personal", wifiSecurity("[WPA2-PSK-CCMP][ESS]"))
        assertEquals("WPA-Personal", wifiSecurity("[WPA-PSK-TKIP][ESS]"))
        assertEquals("WPA3-Personal", wifiSecurity("[RSN-SAE-CCMP][ESS]"))
        assertEquals("WPA3-Personal / WPA2-Personal", wifiSecurity("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals("WPA2-Personal / WPA-Personal", wifiSecurity("[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]"))
    }

    @Test
    fun security_recognizesEnterpriseEnhancedOpenAndLegacy() {
        assertEquals("企业认证（EAP）", wifiSecurity("[WPA2-EAP-CCMP][ESS]"))
        assertEquals("WPA3-Enterprise", wifiSecurity("[RSN-EAP_SUITE_B_192-GCMP-256][ESS]"))
        assertEquals("增强开放（OWE）", wifiSecurity("[RSN-OWE-CCMP][ESS]"))
        assertEquals("增强开放（OWE 过渡模式）", wifiSecurity("[OWE_TRANSITION][ESS]"))
        assertEquals("WEP", wifiSecurity("[WEP][ESS]"))
        assertEquals("WAPI", wifiSecurity("[WAPI-PSK-SMS4][ESS]"))
        assertEquals("开放网络", wifiSecurity("[ESS]"))
        assertEquals("开放网络", wifiSecurity("[WPS][ESS]"))
    }

    @Test
    fun security_doesNotLabelUnknownCapabilitiesAsOpen() {
        assertEquals("未知", wifiSecurity(""))
        assertEquals("未知", wifiSecurity("[RSN-UNKNOWN][ESS]"))
        assertEquals("未知（[NEW-AKM][ESS]）", wifiSecurity("[NEW-AKM][ESS]"))
    }
}
