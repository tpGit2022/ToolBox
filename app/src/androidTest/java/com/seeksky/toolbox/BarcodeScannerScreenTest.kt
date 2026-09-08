package com.seeksky.toolbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seeksky.toolbox.ui.theme.ToolBoxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BarcodeScannerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resultCard_displaysOriginalContentAndForwardsActions() {
        val contents = "  二维码内容\nhttps://example.com/?value=00123  "
        var copyCount = 0
        var clearCount = 0
        composeRule.setContent {
            ToolBoxTheme {
                BarcodeScanResultCard(
                    contents = contents,
                    format = "QR_CODE",
                    onCopy = { copyCount++ },
                    onClear = { clearCount++ }
                )
            }
        }

        composeRule.onNodeWithText(contents).assertIsDisplayed()
        composeRule.onNodeWithText("码制：QR_CODE").assertIsDisplayed()
        composeRule.onNodeWithText("复制内容").performClick()
        composeRule.onNodeWithText("清除结果").performClick()
        composeRule.runOnIdle {
            assertEquals(1, copyCount)
            assertEquals(1, clearCount)
        }
    }

    @Test
    fun resultCard_handlesEmptyContentAndUnknownFormat() {
        composeRule.setContent {
            ToolBoxTheme {
                BarcodeScanResultCard(
                    contents = "",
                    format = null,
                    onCopy = {},
                    onClear = {}
                )
            }
        }

        composeRule.onNodeWithText("（内容为空）").assertIsDisplayed()
        composeRule.onNodeWithText("码制：未知").assertIsDisplayed()
    }
}
