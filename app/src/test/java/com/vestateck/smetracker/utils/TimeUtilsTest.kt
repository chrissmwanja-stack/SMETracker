package com.vestateck.smetracker.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TimeUtilsTest {

    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Calendar {
        return Calendar.getInstance(TimeZone.getDefault()).apply {
            set(year, month, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun `getStartOfDay zeroes out hours, minutes, seconds, and millis`() {
        val midAfternoon = calendarAt(2026, Calendar.JULY, 18, 15, 42, 30).timeInMillis
        val startOfDay = TimeUtils.getStartOfDay(midAfternoon)

        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = startOfDay }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
        // Day itself must not shift.
        assertEquals(18, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `getStartOfDay is idempotent when already at midnight`() {
        val midnight = calendarAt(2026, Calendar.JULY, 18, 0, 0, 0).timeInMillis
        assertEquals(midnight, TimeUtils.getStartOfDay(midnight))
    }

    @Test
    fun `getStartOfWeek result is before or equal to the input timestamp`() {
        val now = calendarAt(2026, Calendar.JULY, 18, 12, 0, 0).timeInMillis
        val startOfWeek = TimeUtils.getStartOfWeek(now)
        assertTrue(startOfWeek <= now)
    }

    @Test
    fun `getStartOfWeek is within 7 days of the input timestamp`() {
        val now = calendarAt(2026, Calendar.JULY, 18, 12, 0, 0).timeInMillis
        val startOfWeek = TimeUtils.getStartOfWeek(now)
        val diffDays = (now - startOfWeek) / (24 * 60 * 60 * 1000)
        assertTrue("expected diff < 7 days, was $diffDays", diffDays < 7)
    }

    @Test
    fun `getStartOfMonth always lands on day 1 at midnight`() {
        val midMonth = calendarAt(2026, Calendar.JULY, 18, 9, 15, 0).timeInMillis
        val startOfMonth = TimeUtils.getStartOfMonth(midMonth)

        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = startOfMonth }
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.JULY, cal.get(Calendar.MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `getStartOfMonth on the first day of the month returns midnight of that same day`() {
        val firstOfMonth = calendarAt(2026, Calendar.JULY, 1, 18, 30, 0).timeInMillis
        val startOfMonth = TimeUtils.getStartOfMonth(firstOfMonth)

        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = startOfMonth }
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.JULY, cal.get(Calendar.MONTH))
    }

    @Test
    fun `ordering holds - start of month is on or before start of week which is on or before start of day`() {
        val now = calendarAt(2026, Calendar.JULY, 18, 12, 0, 0).timeInMillis
        val startOfDay = TimeUtils.getStartOfDay(now)
        val startOfWeek = TimeUtils.getStartOfWeek(now)
        val startOfMonth = TimeUtils.getStartOfMonth(now)

        assertTrue(startOfWeek <= startOfDay)
        assertTrue(startOfMonth <= startOfWeek)
    }
}