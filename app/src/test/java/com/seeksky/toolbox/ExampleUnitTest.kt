package com.seeksky.toolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ExampleUnitTest {
    @Test
    fun parseGestureConfig_acceptsValidValues() {
        val config = GestureConfig.parse("12", "250", "4", "900").getOrThrow()

        assertEquals(12, config.clickCount)
        assertEquals(250L, config.clickIntervalMs)
        assertEquals(4, config.swipeCount)
        assertEquals(900L, config.swipeIntervalMs)
    }

    @Test
    fun parseGestureConfig_rejectsInvalidCountAndInterval() {
        assertTrue(GestureConfig.parse("0", "250", "4", "900").isFailure)
        assertTrue(GestureConfig.parse("1", "-1", "4", "900").isFailure)
        assertTrue(GestureConfig.parse("1", "250", "10001", "900").isFailure)
    }

    @Test
    fun monthlyReminder_keepsOriginalDayAfterShortMonth() {
        val january31 = Calendar.getInstance().apply {
            clear()
            set(2027, Calendar.JANUARY, 31, 9, 30)
        }.timeInMillis

        val february = ReminderPeriod.MONTHLY.nextAfter(january31, 31)
        val march = ReminderPeriod.MONTHLY.nextAfter(february, 31)
        val result = Calendar.getInstance().apply { timeInMillis = march }

        assertEquals(Calendar.MARCH, result.get(Calendar.MONTH))
        assertEquals(31, result.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, result.get(Calendar.MINUTE))
    }
}
