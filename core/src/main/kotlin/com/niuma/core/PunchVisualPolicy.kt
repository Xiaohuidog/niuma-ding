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
    data class ContinuePunchDialog(val visible: Boolean, val button: VisualPoint?)

    /** The organization-change prompt is a confirmation for the submitted punch. */
    fun continuePunchDialog(width: Int, height: Int, lines: List<VisualText>): ContinuePunchDialog {
        if (width <= 0 || height <= 0) return ContinuePunchDialog(false, null)
        val normalized = lines.map { it.copy(text = it.text.replace(Regex("\\s+"), "")) }
        val body = normalized.sortedWith(compareBy<VisualText> { it.box.centerY }.thenBy { it.box.left })
            .joinToString("") { it.text }
        if (!body.contains("当前组织") || !body.contains("继续打卡吗"))
            return ContinuePunchDialog(false, null)
        val buttons = normalized.filter { it.text == "继续打卡" &&
            it.box.centerX > width * 0.58f && it.box.centerY in
                (height * 0.50f).toInt()..(height * 0.72f).toInt() }
        val cancel = normalized.any { it.text == "取消" &&
            it.box.centerX < width * 0.58f && it.box.centerY in
                (height * 0.50f).toInt()..(height * 0.72f).toInt() }
        val button = buttons.singleOrNull()?.takeIf { cancel }
        return ContinuePunchDialog(true, button?.let { VisualPoint(it.box.centerX, it.box.centerY) })
    }

    /** Read the popup only to confirm that a punch succeeded; the schedule determines its type. */
    fun hasSuccessDialog(height: Int, lines: List<VisualText>): Boolean {
        if (height <= 0) return false
        val text = lines.sortedWith(compareBy<VisualText> { it.box.centerY }.thenBy { it.box.left })
            .joinToString("") { it.text }.replace(Regex("\\s+"), "")
        return text.contains("打卡成功")
    }

    /** A freshly opened success dialog is enough to identify the actual punch type. */
    fun successDialogKind(height: Int, lines: List<VisualText>): Kind? {
        val rows = lines.groupBy { (it.box.centerY / (height * 0.018f).coerceAtLeast(1f)).toInt() }
            .values.map { row -> row.sortedBy { it.box.left }.joinToString("") { it.text }
                .replace(Regex("\\s+"), "") }
        val combined = lines.sortedWith(compareBy<VisualText> { it.box.centerY }.thenBy { it.box.left })
            .joinToString("") { it.text }.replace(Regex("\\s+"), "")
        return Kind.entries.filter { kind ->
            rows.any { it.contains("${kind.label}打卡成功") } || combined.contains("${kind.label}打卡成功")
        }.singleOrNull()
    }

    /** Locate the one large blue punch circle by its color and shape, regardless of its label. */
    fun singleBlueCircle(width: Int, height: Int, pixel: (Int, Int) -> Int): VisualPoint? {
        if (width <= 0 || height <= 0) return null
        val step = (width * 0.025f).roundToInt().coerceAtLeast(8)
        val radius = (width * 0.11f).roundToInt()
        val outerRadius = (width * 0.255f).roundToInt()
        val centers = mutableListOf<VisualPoint>()
        for (y in (height * 0.32f).roundToInt()..(height * 0.85f).roundToInt() step step) {
            for (x in (width * 0.2f).roundToInt()..(width * 0.8f).roundToInt() step step) {
                var blue = 0
                var outside = 0
                var valid = true
                for (index in 0 until 16) {
                    val angle = index * Math.PI / 8.0
                    val innerX = x + (radius * cos(angle)).roundToInt()
                    val innerY = y + (radius * sin(angle)).roundToInt()
                    val outerX = x + (outerRadius * cos(angle)).roundToInt()
                    val outerY = y + (outerRadius * sin(angle)).roundToInt()
                    if (innerX !in 0 until width || innerY !in 0 until height ||
                        outerX !in 0 until width || outerY !in 0 until height) {
                        valid = false
                        break
                    }
                    if (isBlue(pixel(innerX, innerY))) blue++
                    if (!isBlue(pixel(outerX, outerY))) outside++
                }
                if (valid && blue >= 14 && outside >= 13) centers += VisualPoint(x, y)
            }
        }
        if (centers.isEmpty()) return null
        if (centers.maxOf { it.x } - centers.minOf { it.x } > width * 0.2f ||
            centers.maxOf { it.y } - centers.minOf { it.y } > width * 0.2f) return null
        return VisualPoint(centers.map { it.x }.average().roundToInt(),
            centers.map { it.y }.average().roundToInt())
    }

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
