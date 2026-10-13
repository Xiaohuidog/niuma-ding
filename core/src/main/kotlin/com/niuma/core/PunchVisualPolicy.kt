package com.niuma.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class VisualBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}
data class VisualText(val text: String, val box: VisualBox)
data class VisualPoint(val x: Int, val y: Int)
data class VisualPunchTarget(val kind: Kind, val point: VisualPoint)

/** Page-specific visual checks. Coordinates come from fresh OCR boxes, never a stored tap position. */
object PunchVisualPolicy {
    /** The success dialog itself is a receipt when its shift, date and time all match this attempt. */
    fun successDialogTime(kind: Kind, date: LocalDate, submittedAt: Long, observedAt: Long,
                          height: Int, lines: List<VisualText>): LocalTime? {
        if (height <= 0) return null
        val rows = lines.sortedWith(compareBy<VisualText> { it.box.centerY }.thenBy { it.box.left })
            .fold(mutableListOf<MutableList<VisualText>>()) { grouped, line ->
                val row = grouped.lastOrNull()
                if (row != null && kotlin.math.abs(row.first().box.centerY - line.box.centerY) <= height * 0.018f)
                    row += line else grouped += mutableListOf(line)
                grouped
            }.map { row ->
                val text = row.sortedBy { it.box.left }.joinToString("") { it.text }
                    .replace(Regex("\\s+"), "").replace('：', ':')
                VisualText(text, VisualBox(row.minOf { it.box.left }, row.minOf { it.box.top },
                    row.maxOf { it.box.right }, row.maxOf { it.box.bottom }))
            }
        val title = rows.singleOrNull { it.text.contains("${kind.label}打卡成功") &&
            it.box.centerY in (height * 0.55f).toInt()..(height * 0.82f).toInt() } ?: return null
        val datePattern = "${date.year}[-/年]0?${date.monthValue}[-/月]0?${date.dayOfMonth}日?"
        val timePattern = Regex("(?:打卡时间:?)?$datePattern((?:[01]\\d|2[0-3]):[0-5]\\d)")
        val candidates = rows.filter { it.box.centerY > title.box.centerY &&
            it.box.centerY < height * 0.92f }.mapNotNull { row ->
            timePattern.find(row.text)?.groupValues?.get(1)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        }.distinct()
        val time = candidates.singleOrNull() ?: return null
        val minute = at(date, time.toString())
        return time.takeIf { minute + 60_000L > submittedAt && minute <= observedAt + 60_000L }
    }

    fun singleBlueButton(month: Int, day: Int, width: Int, height: Int,
                         lines: List<VisualText>, pixel: (Int, Int) -> Int): VisualPunchTarget? =
        Kind.entries.mapNotNull { kind ->
            blueButton(kind, month, day, width, height, lines, pixel)?.let {
                VisualPunchTarget(kind, it)
            }
        }.singleOrNull()

    fun blueButton(kind: Kind, month: Int, day: Int, width: Int, height: Int,
                   lines: List<VisualText>, pixel: (Int, Int) -> Int): VisualPoint? {
        if (width <= 0 || height <= 0) return null
        val normalized = lines.map { it.copy(text = it.text.replace(Regex("\\s+"), "")) }
        if (normalized.none { it.text.contains("${month}月${day}日") }) return null
        val targets = normalized.filter { it.text == "${kind.label}打卡" &&
            it.box.centerY in (height * 0.38f).toInt()..(height * 0.82f).toInt() &&
            it.box.right - it.box.left >= width * 0.12f }
        val target = targets.singleOrNull() ?: return null
        val x = target.box.centerX
        val y = target.box.centerY
        val ringCenterY = y + (width * 0.045f).roundToInt()
        val radius = (width * 0.11f).roundToInt()
        val outerRadius = (width * 0.255f).roundToInt()
        var blue = 0
        var outside = 0
        for (index in 0 until 8) {
            val angle = index * Math.PI / 4.0
            val sampleX = x + (radius * cos(angle)).roundToInt()
            val sampleY = ringCenterY + (radius * sin(angle)).roundToInt()
            if (sampleX !in 0 until width || sampleY !in 0 until height) return null
            if (isBlue(pixel(sampleX, sampleY))) blue++
            val outerX = x + (outerRadius * cos(angle)).roundToInt()
            val outerY = ringCenterY + (outerRadius * sin(angle)).roundToInt()
            if (outerX !in 0 until width || outerY !in 0 until height) return null
            if (!isBlue(pixel(outerX, outerY))) outside++
        }
        return if (blue >= 7 && outside >= 6) VisualPoint(x, y) else null
    }

    fun resultLink(kind: Kind, width: Int, height: Int, lines: List<VisualText>): VisualPoint? {
        if (width <= 0 || height <= 0) return null
        // Chinese OCR may split one visual row into several text lines or include the green check mark.
        val groupedRows = lines.sortedWith(compareBy<VisualText> { it.box.centerY }.thenBy { it.box.left })
            .fold(mutableListOf<MutableList<VisualText>>()) { grouped, line ->
                val row = grouped.lastOrNull()
                if (row != null && kotlin.math.abs(row.first().box.centerY - line.box.centerY) <= height * 0.018f)
                    row += line else grouped += mutableListOf(line)
                grouped
            }
        val rows = groupedRows.map { row ->
                val ordered = row.sortedBy { it.box.left }
                val text = ordered.joinToString("") { it.text }.replace(Regex("[\\s✓✔●•·]+"), "")
                val box = VisualBox(ordered.minOf { it.box.left }, ordered.minOf { it.box.top },
                    ordered.maxOf { it.box.right }, ordered.maxOf { it.box.bottom })
                VisualText(text, box)
            }
        val title = rows.singleOrNull { it.text.contains("${kind.label}打卡成功") &&
            it.box.centerY in (height * 0.25f).toInt()..(height * 0.90f).toInt() } ?: return null
        // The success dialog can add a second CTA (for example, “立即了解”) on
        // the same row. Do not use the whole row's box: its center is the gap
        // between the two buttons and the tap silently misses both.
        val linkCandidates = groupedRows.flatMap { row ->
            val ordered = row.sortedBy { it.box.left }
            ordered.indices.flatMap { start ->
                (start until ordered.size).mapNotNull { end ->
                    val fragment = ordered.subList(start, end + 1)
                    val joined = fragment.joinToString("") { it.text }.replace(Regex("\\s+"), "")
                    if (joined != "查看打卡结果") null else VisualText(joined,
                        VisualBox(fragment.minOf { it.box.left }, fragment.minOf { it.box.top },
                            fragment.maxOf { it.box.right }, fragment.maxOf { it.box.bottom }))
                }
            }
        }
        val links = linkCandidates.filter {
            it.box.centerY > title.box.centerY + height * 0.03f &&
            it.box.centerY < title.box.centerY + height * 0.35f &&
            it.box.centerY < height * 0.95f &&
            it.box.centerX in (width * 0.2f).toInt()..(width * 0.8f).toInt() }
        val link = links.singleOrNull() ?: return null
        return VisualPoint(link.box.centerX, link.box.centerY)
    }

    private fun isBlue(color: Int): Boolean {
        val red = color ushr 16 and 0xFF
        val green = color ushr 8 and 0xFF
        val blue = color and 0xFF
        return blue >= 125 && blue > red * 1.3 && blue > green * 1.12
    }
}
