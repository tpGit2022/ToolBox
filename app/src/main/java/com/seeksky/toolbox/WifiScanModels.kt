package com.seeksky.toolbox

internal data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val signalDbm: Int,
    val standard: String,
    val security: String,
    val timestampMicros: Long
)

internal fun wifiChannel(frequencyMhz: Int): Int? = when {
    frequencyMhz == 2484 -> 14
    frequencyMhz in 2412..2472 && (frequencyMhz - 2407) % 5 == 0 ->
        (frequencyMhz - 2407) / 5
    frequencyMhz in 4910..4980 && frequencyMhz % 5 == 0 ->
        (frequencyMhz - 4000) / 5
    frequencyMhz in 5160..5885 && frequencyMhz % 5 == 0 ->
        (frequencyMhz - 5000) / 5
    frequencyMhz == 5935 -> 2
    frequencyMhz in 5955..7115 && (frequencyMhz - 5950) % 5 == 0 ->
        (frequencyMhz - 5950) / 5
    frequencyMhz in 58320..70200 && (frequencyMhz - 56160) % 2160 == 0 ->
        (frequencyMhz - 56160) / 2160
    else -> null
}

internal fun wifiBand(frequencyMhz: Int): String = when (frequencyMhz) {
    in 2400..2500 -> "2.4 GHz"
    in 4900..4999 -> "4.9 GHz"
    in 5000..5924 -> "5 GHz"
    in 5925..7125 -> "6 GHz"
    in 57000..71000 -> "60 GHz"
    else -> "未知频段"
}

internal fun wifiSecurity(capabilities: String): String {
    val flags = capabilities.uppercase(java.util.Locale.ROOT)
    val types = mutableListOf<String>()
    if ("WEP" in flags) types += "WEP"
    if ("SAE" in flags) types += "WPA3-Personal"
    if ("PSK" in flags && "WAPI" !in flags) {
        if ("WPA2" in flags || "RSN" in flags) types += "WPA2-Personal"
        if ("WPA-PSK" in flags) types += "WPA-Personal"
        if (types.none { it.endsWith("Personal") && it != "WPA3-Personal" }) {
            types += "PSK（版本未知）"
        }
    }
    if ("EAP" in flags || "SUITE_B" in flags) {
        types += if ("SUITE_B" in flags) "WPA3-Enterprise" else "企业认证（EAP）"
    }
    if ("OWE_TRANSITION" in flags) types += "增强开放（OWE 过渡模式）"
    else if ("OWE" in flags) types += "增强开放（OWE）"
    if ("WAPI" in flags) types += "WAPI"
    if ("DPP" in flags) types += "DPP"
    return types.joinToString(" / ").ifEmpty {
        if (flags.isEmpty() || listOf("WPA", "RSN", "PRIVACY").any { it in flags }) {
            "未知"
        } else if (flags == "[ESS]" || flags == "[IBSS]" || flags == "[WPS][ESS]" || flags == "[ESS][WPS]") {
            "开放网络"
        } else {
            "未知（$capabilities）"
        }
    }
}
