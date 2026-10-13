package com.niuma.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MineAttendanceEntryTest {
    @Test fun choosesMineEvenWhenBannerAndAllStaffHaveSameWords() {
        val labels = listOf(
            EntryLabel("打卡", 0.20f, 0.32f),
            EntryLabel("考勤打卡", 0.60f, 0.36f),
            EntryLabel("我的", 0.08f, 0.42f),
            EntryLabel("考勤打卡", 0.12f, 0.52f),
            EntryLabel("全员", 0.08f, 0.61f),
            EntryLabel("考勤打卡", 0.12f, 0.73f)
        )
        assertEquals(3, MineAttendanceEntry.targetIndex(labels))
    }

    @Test fun doesNotUseAllStaffEntryWhenMineEntryIsMissing() {
        val labels = listOf(
            EntryLabel("我的", 0.08f, 0.42f),
            EntryLabel("全员", 0.08f, 0.61f),
            EntryLabel("考勤打卡", 0.12f, 0.73f)
        )
        assertEquals(null, MineAttendanceEntry.targetIndex(labels))
    }

    @Test fun requiresBothSectionHeadersAndExactEntryName() {
        assertEquals(null, MineAttendanceEntry.targetIndex(listOf(
            EntryLabel("我的", 0.08f, 0.42f), EntryLabel("考勤打卡", 0.12f, 0.52f))))
        assertEquals(null, MineAttendanceEntry.targetIndex(listOf(
            EntryLabel("我的", 0.08f, 0.42f), EntryLabel("打卡", 0.12f, 0.52f),
            EntryLabel("全员", 0.08f, 0.61f))))
    }

    @Test fun refusesAmbiguousMineEntries() {
        val labels = listOf(
            EntryLabel("我的", 0.08f, 0.42f),
            EntryLabel("考勤打卡", 0.12f, 0.49f),
            EntryLabel("考勤打卡", 0.30f, 0.54f),
            EntryLabel("全员", 0.08f, 0.61f)
        )
        assertEquals(null, MineAttendanceEntry.targetIndex(labels))
    }

    @Test fun overlappingParentAndChildLabelsCountAsOneItem() {
        val labels = listOf(
            EntryLabel("我的", 0.08f, 0.42f),
            EntryLabel("考勤打卡", 0.120f, 0.520f),
            EntryLabel("考勤打卡", 0.121f, 0.521f),
            EntryLabel("全员", 0.08f, 0.61f)
        )
        assertEquals(1, MineAttendanceEntry.targetIndex(labels))
    }

    @Test fun visualOcrAcceptsObservedMisreadingOnlyInsideMineFirstColumn() {
        val labels = listOf(
            EntryLabel("我的", 0.11f, 0.41f),
            EntryLabel("考勒打卡", 0.13f, 0.52f),
            EntryLabel("全员", 0.11f, 0.62f),
            EntryLabel("考勒打卡", 0.13f, 0.73f)
        )
        assertEquals(null, MineAttendanceEntry.targetIndex(labels))
        assertEquals(1, MineAttendanceEntry.targetIndex(labels, visualOcr = true))
        assertEquals(null, MineAttendanceEntry.targetIndex(labels.mapIndexed { i, item ->
            if (i == 1) item.copy(centerX = 0.40f) else item
        }, visualOcr = true))
    }
}
