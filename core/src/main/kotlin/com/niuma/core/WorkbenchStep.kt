package com.niuma.core

data class WorkbenchPage(
    val isDingTalk: Boolean,
    val hasWorkbenchTitle: Boolean,
    val workbenchTabSelected: Boolean,
    val primaryTabsVisible: Boolean,
    val workbenchTabVisible: Boolean
)

data class WorkbenchLabel(val text: String, val verticalCenter: Float, val selected: Boolean)

enum class WorkbenchAction { DONE, OPEN_TAB, BACK, STOP }

/** One decision per fresh page read; the caller must inspect the screen again after every action. */
object WorkbenchStep {
    private val primaryTabs = setOf("千问办公", "消息", "工作台", "通讯录", "更多")

    fun readPage(packageName: String, labels: List<WorkbenchLabel>): WorkbenchPage {
        val bottomLabels = labels.filter { it.verticalCenter >= 0.78f && it.text in primaryTabs }
        val workbenchTab = bottomLabels.firstOrNull { it.text == "工作台" }
        return WorkbenchPage(
            isDingTalk = packageName == "com.alibaba.android.rimet",
            hasWorkbenchTitle = labels.any { it.text == "工作台" && it.verticalCenter <= 0.22f },
            workbenchTabSelected = workbenchTab?.selected == true,
            primaryTabsVisible = bottomLabels.map { it.text }.distinct().size >= 3,
            workbenchTabVisible = workbenchTab != null
        )
    }

    fun decide(page: WorkbenchPage, backs: Int): WorkbenchAction = when {
        !page.isDingTalk -> WorkbenchAction.STOP
        page.hasWorkbenchTitle && page.workbenchTabSelected && page.primaryTabsVisible -> WorkbenchAction.DONE
        page.primaryTabsVisible && page.workbenchTabVisible -> WorkbenchAction.OPEN_TAB
        backs >= 8 -> WorkbenchAction.STOP
        else -> WorkbenchAction.BACK
    }
}
