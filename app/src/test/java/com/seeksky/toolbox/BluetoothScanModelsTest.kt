package com.seeksky.toolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothScanModelsTest {
    @Test
    fun bytesToHex_formatsUnsignedBytes() {
        assertEquals("00 7F 80 FF", bluetoothBytesToHex(byteArrayOf(0, 127, -128, -1)))
    }

    @Test
    fun rssiDescription_usesReadableRanges() {
        assertEquals("很强", bluetoothRssiDescription(-45))
        assertEquals("强", bluetoothRssiDescription(-60))
        assertEquals("中等", bluetoothRssiDescription(-70))
        assertEquals("弱", bluetoothRssiDescription(-85))
        assertEquals("很弱", bluetoothRssiDescription(-95))
    }

    @Test
    fun merge_combinesDiscoverySourcesAndPreservesBleDetails() {
        val ble = device(
            source = BluetoothScanSource.BLE,
            rssi = -72,
            serviceUuids = listOf("service-a"),
            rawAdvertisingDataHex = "01 02",
        )
        val classic = device(
            source = BluetoothScanSource.CLASSIC,
            rssi = -60,
            serviceUuids = listOf("service-b"),
        )

        val merged = mergeBluetoothDevice(ble, classic)

        assertEquals(-60, merged.rssiDbm)
        assertTrue(merged.sources.containsAll(BluetoothScanSource.entries))
        assertEquals(listOf("service-a", "service-b"), merged.serviceUuids)
        assertEquals("01 02", merged.rawAdvertisingDataHex)
    }

    private fun device(
        source: BluetoothScanSource,
        rssi: Int,
        serviceUuids: List<String>,
        rawAdvertisingDataHex: String? = null,
    ) = NearbyBluetoothDevice(
        address = "00:11:22:33:44:55",
        systemName = "Test",
        scanReportedName = null,
        alias = null,
        sources = setOf(source),
        rssiDbm = rssi,
        firstSeenElapsedMillis = 1L,
        lastSeenElapsedMillis = 2L,
        bondState = "未配对",
        deviceType = "双模（经典 + BLE）",
        majorDeviceClass = null,
        deviceClassCode = null,
        classServices = emptyList(),
        serviceUuids = serviceUuids,
        solicitationUuids = emptyList(),
        manufacturerData = emptyList(),
        serviceData = emptyList(),
        advertisingFlags = null,
        txPowerDbm = null,
        primaryPhy = null,
        secondaryPhy = null,
        advertisingSid = null,
        periodicAdvertisingIntervalMillis = null,
        connectable = null,
        legacyAdvertisement = null,
        advertisingDataStatus = null,
        rawAdvertisingDataHex = rawAdvertisingDataHex,
    )
}
