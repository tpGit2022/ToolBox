package com.seeksky.toolbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seeksky.toolbox.ui.theme.ToolBoxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiScannerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun networkCard_displaysRawDbmTypeChannelAndSampleAge() {
        composeRule.setContent {
            ToolBoxTheme {
                WifiNetworkCard(
                    network = WifiNetwork(
                        ssid = "测试 Wi-Fi",
                        bssid = "02:00:00:00:00:01",
                        frequencyMhz = 5180,
                        signalDbm = -57,
                        standard = "Wi-Fi 5（802.11ac）",
                        security = "WPA2-Personal",
                        timestampMicros = 10_000_000L
                    ),
                    elapsedMillis = 15_000L
                )
            }
        }

        composeRule.onNodeWithText("测试 Wi-Fi").assertIsDisplayed()
        composeRule.onNodeWithText("-57 dBm").assertIsDisplayed()
        composeRule.onNodeWithText("频段：5 GHz · 5180 MHz").assertIsDisplayed()
        composeRule.onNodeWithText("协议：Wi-Fi 5（802.11ac）").assertIsDisplayed()
        composeRule.onNodeWithText("安全类型：WPA2-Personal").assertIsDisplayed()
        composeRule.onNodeWithText("主信道：36").assertIsDisplayed()
        composeRule.onNodeWithText("信号采样：5 秒前").assertIsDisplayed()
    }
}
