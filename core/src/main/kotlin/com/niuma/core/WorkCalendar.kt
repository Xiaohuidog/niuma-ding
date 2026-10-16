package com.niuma.core

import java.time.LocalDate
import java.util.Properties

enum class Day(val label: String) { WORK("普通工作日"), MAKEUP("调休补班"), REST("休息日"), MISSING("工作日历缺失，计划已暂停执行") }
class WorkCalendar(private val years: Map<Int, Year>) {
    data class Year(val year: Int, val version: String, val source: String, val verified: LocalDate,
                    val start: LocalDate, val end: LocalDate, val exceptions: Map<LocalDate, Boolean>) {
        init {
            require(version.length in 1..64 && source.startsWith("https://") && source.length <= 2048)
            require(start == LocalDate.of(year, 1, 1) && end == LocalDate.of(year, 12, 31))
            require(exceptions.keys.all { it.year == year })
        }
    }
    fun year(year: Int) = years[year]
    fun classify(date: LocalDate): Day {
        val year = years[date.year] ?: return Day.MISSING
        // The user's schedule never punches on Saturday or Sunday, including official makeup days.
        if (date.dayOfWeek.value >= 6) return Day.REST
        return when (year.exceptions[date]) {
            true -> Day.MAKEUP
            false -> Day.REST
            null -> if (date.dayOfWeek.value <= 5) Day.WORK else Day.REST
        }
    }
    companion object {
        fun bundled(): WorkCalendar {
            val stream = requireNotNull(WorkCalendar::class.java.getResourceAsStream("/calendar/2026.properties"))
            val p = Properties().apply { stream.use { load(it) } }
            val exceptions = mutableMapOf<LocalDate, Boolean>()
            p.getProperty("rest").split(',').forEach { range ->
                val (from, to) = range.split('/').map(LocalDate::parse)
                var d = from
                while (d <= to) { require(exceptions.put(d, false) == null); d = d.plusDays(1) }
            }
            p.getProperty("work").split(',').map(LocalDate::parse).forEach { require(exceptions.put(it, true) == null) }
            val y = Year(p.getProperty("year").toInt(), p.getProperty("version"), p.getProperty("source"),
                LocalDate.parse(p.getProperty("verified")), LocalDate.parse(p.getProperty("start")),
                LocalDate.parse(p.getProperty("end")), exceptions)
            return WorkCalendar(mapOf(y.year to y))
        }
    }
}
