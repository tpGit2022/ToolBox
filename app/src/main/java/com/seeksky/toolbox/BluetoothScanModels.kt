package com.seeksky.toolbox

internal enum class BluetoothScanSource(val label: String) {
    BLE("BLE 广播"),
    CLASSIC("经典蓝牙"),
}

internal data class BluetoothDataEntry(
    val key: String,
    val valueHex: String,
)

/** A merged snapshot of everything Android exposed while scanning one Bluetooth address. */
internal data class NearbyBluetoothDevice(
    val address: String,
    val systemName: String?,
    val scanReportedName: String?,
    val alias: String?,
    val sources: Set<BluetoothScanSource>,
    val rssiDbm: Int?,
    val firstSeenElapsedMillis: Long,
    val lastSeenElapsedMillis: Long,
    val bondState: String,
    val deviceType: String,
    val majorDeviceClass: String?,
    val deviceClassCode: Int?,
    val classServices: List<String>,
    val serviceUuids: List<String>,
    val solicitationUuids: List<String>,
    val manufacturerData: List<BluetoothDataEntry>,
    val serviceData: List<BluetoothDataEntry>,
    val advertisingFlags: Int?,
    val txPowerDbm: Int?,
    val primaryPhy: String?,
    val secondaryPhy: String?,
    val advertisingSid: Int?,
    val periodicAdvertisingIntervalMillis: Double?,
    val connectable: Boolean?,
    val legacyAdvertisement: Boolean?,
    val advertisingDataStatus: String?,
    val rawAdvertisingDataHex: String?,
) {
    val displayName: String
        get() = scanReportedName?.takeIf { it.isNotBlank() }
            ?: systemName?.takeIf { it.isNotBlank() }
            ?: "（未提供名称）"
}

internal fun bluetoothBytesToHex(bytes: ByteArray): String = bytes.joinToString(" ") {
    (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
}

internal fun bluetoothRssiDescription(rssiDbm: Int): String = when {
    rssiDbm >= -50 -> "很强"
    rssiDbm >= -65 -> "强"
    rssiDbm >= -75 -> "中等"
    rssiDbm >= -90 -> "弱"
    else -> "很弱"
}

internal fun mergeBluetoothDevice(
    previous: NearbyBluetoothDevice?,
    update: NearbyBluetoothDevice,
): NearbyBluetoothDevice {
    if (previous == null) return update
    return update.copy(
        systemName = update.systemName ?: previous.systemName,
        scanReportedName = update.scanReportedName ?: previous.scanReportedName,
        alias = update.alias ?: previous.alias,
        sources = previous.sources + update.sources,
        rssiDbm = update.rssiDbm ?: previous.rssiDbm,
        firstSeenElapsedMillis = minOf(previous.firstSeenElapsedMillis, update.firstSeenElapsedMillis),
        majorDeviceClass = update.majorDeviceClass ?: previous.majorDeviceClass,
        deviceClassCode = update.deviceClassCode ?: previous.deviceClassCode,
        classServices = (previous.classServices + update.classServices).distinct(),
        serviceUuids = (previous.serviceUuids + update.serviceUuids).distinct(),
        solicitationUuids = (previous.solicitationUuids + update.solicitationUuids).distinct(),
        manufacturerData = update.manufacturerData.ifEmpty { previous.manufacturerData },
        serviceData = update.serviceData.ifEmpty { previous.serviceData },
        advertisingFlags = update.advertisingFlags ?: previous.advertisingFlags,
        txPowerDbm = update.txPowerDbm ?: previous.txPowerDbm,
        primaryPhy = update.primaryPhy ?: previous.primaryPhy,
        secondaryPhy = update.secondaryPhy ?: previous.secondaryPhy,
        advertisingSid = update.advertisingSid ?: previous.advertisingSid,
        periodicAdvertisingIntervalMillis = update.periodicAdvertisingIntervalMillis
            ?: previous.periodicAdvertisingIntervalMillis,
        connectable = update.connectable ?: previous.connectable,
        legacyAdvertisement = update.legacyAdvertisement ?: previous.legacyAdvertisement,
        advertisingDataStatus = update.advertisingDataStatus ?: previous.advertisingDataStatus,
        rawAdvertisingDataHex = update.rawAdvertisingDataHex ?: previous.rawAdvertisingDataHex,
    )
}
