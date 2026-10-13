package com.niuma.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/** Read-only collection of one selected day in DingTalk's Statistics page. */
class StatisticsRecordPolicy(private val date: LocalDate, private val kind: Kind) {
    private var todayConfirmed = false
    private var bottomConfirmed = false
    private val times = mutableListOf<LocalTime>()

    fun acceptHeader(lines: List<VisualText>, height: Int): Boolean {
        if (height <= 0) return false
        val title = lines.any { normalize(it.text) == "统计" && it.box.centerY < height * 0.16f }
        val dateLines = lines.filter { it.box.centerY in (height * 0.15f).toInt()..(height * 0.40f).toInt() }
        val year = date.year.toString()
        val monthDay = "${date.monthValue}.${date.dayOfMonth}"
        val matchingYear = dateLines.filter { normalize(it.text).contains(year) }
        val matchingDay = dateLines.filter { normalize(it.text).contains(monthDay) }
        todayConfirmed = title && matchingYear.any { y -> matchingDay.any { d ->
            abs(y.box.centerY - d.box.centerY) <= height * 0.04f
        } }
        return todayConfirmed
    }

    fun addVisible(lines: List<VisualText>, height: Int) {
        if (!todayConfirmed || height <= 0) return
        for (row in rows(lines, height)) {
            val text = normalize(row)
            if (text.contains("统计截至${date}")) bottomConfirmed = true
            val match = Regex("^(上班|下班)((?:[01]\\d|2[0-3]):[0-5]\\d)$").matchEntire(text) ?: continue
            if (match.groupValues[1] == kind.label)
                times += LocalTime.parse(match.groupValues[2])
        }
    }

    fun atBottom(): Boolean = bottomConfirmed

    fun verifiedTime(): LocalTime? = when {
        !todayConfirmed -> null
        kind == Kind.IN -> times.distinct().singleOrNull()
        !bottomConfirmed -> null
        else -> times.lastOrNull()
    }

    private fun rows(lines: List<VisualText>, height: Int): List<String> {
        val grouped = mutableListOf<MutableList<VisualText>>()
        for (line in lines.sortedWith(compareBy<VisualText> { it.box.centerY }.thenBy { it.box.left })) {
            val row = grouped.lastOrNull()
            if (row != null && abs(row.first().box.centerY - line.box.centerY) <= height * 0.015f)
                row += line else grouped += mutableListOf(line)
        }
        return grouped.map { row -> row.sortedBy { it.box.left }.joinToString("") { it.text } }
    }

    private fun normalize(text: String): String = text.replace(Regex("[\\s●•·]+"), "")
}
