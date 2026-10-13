package com.niuma.core

data class EntryLabel(val text: String, val centerX: Float, val centerY: Float)

/** Picks only the attendance item bounded by the Workbench's 我的 and 全员 sections. */
object MineAttendanceEntry {
    fun targetIndex(labels: List<EntryLabel>, visualOcr: Boolean = false): Int? {
        val mine = labels.withIndex().filter { it.value.text == "我的" }
        if (mine.size != 1) return null
        val allStaff = labels.withIndex().filter { it.value.text == "全员" &&
            it.value.centerY > mine.single().value.centerY }
        if (allStaff.size != 1) return null
        val top = mine.single().value.centerY
        val bottom = allStaff.single().value.centerY
        val targetNames = if (visualOcr) setOf("考勤打卡", "考勒打卡") else setOf("考勤打卡")
        val targets = labels.withIndex().filter { it.value.text in targetNames &&
            it.value.centerY > top && it.value.centerY < bottom &&
            (!visualOcr || it.value.centerX < 0.25f) }
        val first = targets.firstOrNull() ?: return null
        return first.index.takeIf { targets.all { target ->
            kotlin.math.abs(target.value.centerX - first.value.centerX) < 0.03f &&
                kotlin.math.abs(target.value.centerY - first.value.centerY) < 0.03f
        } }
    }
}
