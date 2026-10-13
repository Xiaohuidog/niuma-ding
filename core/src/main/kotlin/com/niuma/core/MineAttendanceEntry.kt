package com.niuma.core

data class EntryLabel(val text: String, val centerX: Float, val centerY: Float)

/** Picks attendance only from 工作台 → 个人 → 最近使用. */
object MineAttendanceEntry {
    fun targetIndex(labels: List<EntryLabel>, visualOcr: Boolean = false): Int? {
        val personal = labels.singleOrNull { it.text == "个人" && it.centerY < 0.25f } ?: return null
        val recent = labels.singleOrNull { it.text == "最近使用" &&
            it.centerY > personal.centerY && it.centerY < 0.35f } ?: return null
        val top = recent.centerY
        val bottom = labels.filter { it.text == "高德打车" && it.centerY > top }
            .minOfOrNull { it.centerY } ?: 0.39f
        val targetNames = if (visualOcr) setOf("考勤打卡", "考勒打卡") else setOf("考勤打卡")
        val targets = labels.withIndex().filter { it.value.text in targetNames &&
            it.value.centerY > top && it.value.centerY < bottom &&
            it.value.centerX < 0.30f }
        val first = targets.firstOrNull() ?: return null
        return first.index.takeIf { targets.all { target ->
            kotlin.math.abs(target.value.centerX - first.value.centerX) < 0.03f &&
                kotlin.math.abs(target.value.centerY - first.value.centerY) < 0.03f
        } }
    }
}
