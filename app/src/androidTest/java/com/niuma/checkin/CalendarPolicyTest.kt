package com.niuma.checkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.niuma.core.Day
import com.niuma.core.WorkCalendar
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CalendarPolicyTest {
    @Test fun packagedMainlandCalendarMatchesUpcomingHolidayPolicy() {
        val calendar = WorkCalendar.bundled()

        assertEquals(Day.WORK, calendar.classify(LocalDate.of(2026, 9, 18)))
        assertEquals(Day.REST, calendar.classify(LocalDate.of(2026, 9, 19)))
        assertEquals(Day.MAKEUP, calendar.classify(LocalDate.of(2026, 9, 20)))
        listOf(25, 26, 27).forEach { day ->
            assertEquals(Day.REST, calendar.classify(LocalDate.of(2026, 9, day)))
        }
        (1..7).forEach { day ->
            assertEquals(Day.REST, calendar.classify(LocalDate.of(2026, 10, day)))
        }
        assertEquals(Day.MAKEUP, calendar.classify(LocalDate.of(2026, 10, 10)))
    }
}
