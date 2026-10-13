package com.niuma.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class StatisticsRecordPolicyTest {
    private val today = LocalDate.of(2026, 10, 12)
    private val height = 2000
    private fun line(text: String, x: Int, y: Int) = VisualText(text, VisualBox(x, y, x + 160, y + 40))
    private val header = listOf(line("统计", 450, 120), line("2026", 90, 470), line("10.12", 230, 470))

    @Test fun onlyTodaysLabeledWorkTimeCountsForClockIn() {
        val collector = StatisticsRecordPolicy(today, Kind.IN)
        assertTrue(collector.acceptHeader(header, height))
        collector.addVisible(listOf(
            line("当日班次 15:30-15:50", 80, 1000),
            line("上班", 130, 1250), line("12:01", 270, 1250),
            line("打卡 16:16", 130, 1550),
            line("打卡无效，此记录已被更新", 130, 1610)
        ), height)
        assertEquals(LocalTime.of(12, 1), collector.verifiedTime())
    }

    @Test fun anotherDateNeverProducesSuccess() {
        val collector = StatisticsRecordPolicy(today, Kind.IN)
        assertFalse(collector.acceptHeader(listOf(line("统计", 450, 120),
            line("2026", 90, 470), line("10.11", 230, 470)), height))
        collector.addVisible(listOf(line("上班 12:01", 130, 1250)), height)
        assertNull(collector.verifiedTime())
    }

    @Test fun clockOutUsesLastLabeledRecordOnlyAfterTodaysFooter() {
        val collector = StatisticsRecordPolicy(today, Kind.OUT)
        assertTrue(collector.acceptHeader(header, height))
        collector.addVisible(listOf(line("打卡 18:18", 130, 1000),
            line("下班 18:20", 130, 1500)), height)
        assertNull(collector.verifiedTime())
        collector.addVisible(listOf(line("打卡 18:18", 130, 850),
            line("下班 21:55", 130, 1450),
            line("统计截至 2026-10-12 21:56", 80, 1770)), height)
        assertEquals(LocalTime.of(21, 55), collector.verifiedTime())
    }

    @Test fun wrongFooterOrMultipleClockInTimesRemainUnverified() {
        val out = StatisticsRecordPolicy(today, Kind.OUT)
        assertTrue(out.acceptHeader(header, height))
        out.addVisible(listOf(line("下班 21:55", 130, 1450),
            line("统计截至 2026-10-11 21:56", 80, 1770)), height)
        assertNull(out.verifiedTime())

        val input = StatisticsRecordPolicy(today, Kind.IN)
        assertTrue(input.acceptHeader(header, height))
        input.addVisible(listOf(line("上班 08:01", 130, 1200),
            line("上班 08:05", 130, 1400)), height)
        assertNull(input.verifiedTime())
    }
}
