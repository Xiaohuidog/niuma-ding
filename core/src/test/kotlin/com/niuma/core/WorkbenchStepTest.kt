package com.niuma.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkbenchStepTest {
    @Test fun screenLabelsIdentifyWorkbenchWithoutUsingFixedCoordinates() {
        val labels = listOf(
            WorkbenchLabel("工作台", 0.06f, false),
            WorkbenchLabel("千问办公", 0.91f, false),
            WorkbenchLabel("消息", 0.91f, false),
            WorkbenchLabel("工作台", 0.91f, true),
            WorkbenchLabel("通讯录", 0.91f, false),
            WorkbenchLabel("更多", 0.91f, false)
        )
        assertEquals(WorkbenchAction.DONE,
            WorkbenchStep.decide(WorkbenchStep.readPage("com.alibaba.android.rimet", labels), 0))
    }

    @Test fun workbenchWordOutsideBottomNavigationIsNotAFirstLevelTab() {
        val labels = listOf(
            WorkbenchLabel("工作台", 0.44f, false),
            WorkbenchLabel("返回", 0.06f, false)
        )
        assertEquals(WorkbenchAction.BACK,
            WorkbenchStep.decide(WorkbenchStep.readPage("com.alibaba.android.rimet", labels), 0))
    }

    @Test fun alreadyOnWorkbenchDoesNothing() {
        assertEquals(WorkbenchAction.DONE, WorkbenchStep.decide(
            WorkbenchPage(true, true, true, true, true), 0))
    }

    @Test fun firstLevelPageOpensWorkbenchTab() {
        assertEquals(WorkbenchAction.OPEN_TAB, WorkbenchStep.decide(
            WorkbenchPage(true, false, false, true, true), 0))
    }

    @Test fun deeperPageReturnsToFirstLevel() {
        assertEquals(WorkbenchAction.BACK, WorkbenchStep.decide(
            WorkbenchPage(true, false, false, false, false), 0))
    }

    @Test fun unknownAppOrTooManyBacksStops() {
        assertEquals(WorkbenchAction.STOP, WorkbenchStep.decide(
            WorkbenchPage(false, false, false, false, false), 0))
        assertEquals(WorkbenchAction.STOP, WorkbenchStep.decide(
            WorkbenchPage(true, false, false, false, false), 8))
    }

    @Test fun selectedTabWithoutWorkbenchTitleMustBeRechecked() {
        assertEquals(WorkbenchAction.OPEN_TAB, WorkbenchStep.decide(
            WorkbenchPage(true, false, true, true, true), 0))
    }

    @Test fun titleAndSelectedWordWithoutPrimaryNavigationAreNotEnough() {
        assertEquals(WorkbenchAction.BACK, WorkbenchStep.decide(
            WorkbenchPage(true, true, true, false, true), 0))
    }
}
