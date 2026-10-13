package com.niuma.checkin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.niuma.core.EntryLabel
import com.niuma.core.MineAttendanceEntry
import com.niuma.core.Kind
import com.niuma.core.VisualPoint
import com.niuma.core.WorkbenchAction
import com.niuma.core.WorkbenchLabel
import com.niuma.core.WorkbenchStep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Independently callable DingTalk navigation steps. Run from a worker thread. */
class DingTalkWorkbenchNavigator : AccessibilityService() {
    data class Outcome(val reached: Boolean, val detail: String)

    companion object {
        private const val DINGTALK_PACKAGE = "com.alibaba.android.rimet"
        @Volatile var connected: DingTalkWorkbenchNavigator? = null
            private set
    }

    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    fun enterWorkbench(): Outcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "工作台导航必须在后台线程执行" }
        if (rootInActiveWindow?.packageName?.toString() != DINGTALK_PACKAGE) {
            val launch = packageManager.getLaunchIntentForPackage(DINGTALK_PACKAGE)
                ?: return Outcome(false, "未找到钉钉启动入口")
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        var backs = 0
        var tabClicks = 0
        repeat(12) {
            val root = waitForDingTalkRoot() ?: return Outcome(false, "无法读取钉钉页面")
            val screen = Rect().also(root::getBoundsInScreen)
            if (screen.height() <= 0) return Outcome(false, "钉钉页面尺寸无效")
            val nodes = mutableListOf<VisibleNode>()
            collect(root, screen, nodes)
            val page = WorkbenchStep.readPage(root.packageName?.toString().orEmpty(),
                nodes.map { WorkbenchLabel(it.label, it.centerY, it.selected) })
            when (WorkbenchStep.decide(page, backs)) {
                WorkbenchAction.DONE -> return Outcome(true, "已进入工作台")
                WorkbenchAction.OPEN_TAB -> {
                    if (tabClicks >= 2) return Outcome(false, "点击工作台后未能确认目标页面")
                    val tab = nodes.firstOrNull { it.label == "工作台" && it.centerY >= 0.78f }
                        ?: return Outcome(false, "未找到底部工作台入口")
                    if (!click(tab.node)) return Outcome(false, "工作台入口无法点击")
                    tabClicks++
                }
                WorkbenchAction.BACK -> {
                    if (!performGlobalAction(GLOBAL_ACTION_BACK)) return Outcome(false, "无法返回一级页面")
                    backs++
                }
                WorkbenchAction.STOP -> return Outcome(false, "当前页面不属于已识别的钉钉导航路径")
            }
            Thread.sleep(500)
        }
        return Outcome(false, "进入工作台超时")
    }

    fun openMineAttendance(): Outcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "考勤入口导航必须在后台线程执行" }
        val root = waitForDingTalkRoot() ?: return Outcome(false, "无法读取钉钉工作台")
        val screen = Rect().also(root::getBoundsInScreen)
        if (screen.width() <= 0 || screen.height() <= 0) return Outcome(false, "工作台页面尺寸无效")
        val nodes = mutableListOf<VisibleNode>()
        collect(root, screen, nodes)
        val page = WorkbenchStep.readPage(root.packageName?.toString().orEmpty(),
            nodes.map { WorkbenchLabel(it.label, it.centerY, it.selected) })
        if (WorkbenchStep.decide(page, 0) != WorkbenchAction.DONE)
            return Outcome(false, "当前不是已确认的工作台页面")
        val index = MineAttendanceEntry.targetIndex(nodes.map {
            EntryLabel(it.label, it.centerX, it.centerY)
        })
        if (index != null) {
            if (!tap(nodes[index].node)) return Outcome(false, "“我的”中的“考勤打卡”无法点击")
        } else {
            // DingTalk renders the Workbench application cards without accessibility text on some devices.
            // The Workbench shell can appear before its app cards. Re-read this same section
            // while it loads; never substitute the duplicate entry under 全员.
            val point = DingTalkVisualReader(application as CheckinApp, this).use { reader ->
                var targetPoint: VisualPoint? = null
                repeat(8) {
                    if (!isDingTalkVisible()) {
                        Thread.sleep(600)
                        return@repeat
                    }
                    targetPoint = reader.capture()?.use { shot ->
                        val labels = shot.text.map { line ->
                            EntryLabel(line.text.replace(Regex("\\s+"), ""),
                                line.box.centerX.toFloat() / shot.bitmap.width,
                                line.box.centerY.toFloat() / shot.bitmap.height)
                        }
                        val target = MineAttendanceEntry.targetIndex(labels, visualOcr = true)
                        target?.let { VisualPoint(shot.text[it].box.centerX, shot.text[it].box.centerY) }
                    }
                    if (targetPoint != null) return@use targetPoint
                    Thread.sleep(600)
                }
                targetPoint
            } ?: return Outcome(false, "工作台内容重读 8 次后仍无法唯一定位“我的”中的“考勤打卡”")
            if (!tapDingTalkPoint(point)) return Outcome(false, "截图定位的“我的”考勤入口无法点击")
        }
        return Outcome(true, "已点击“我的”中的“考勤打卡”；目标页面待下一步核验")
    }

    fun isDingTalkVisible(): Boolean = rootInActiveWindow?.packageName?.toString() == DINGTALK_PACKAGE

    fun isSystemOverlayVisible(): Boolean = rootInActiveWindow?.packageName?.toString() == "com.android.systemui"

    /** Result-link coordinates are accepted only after the current screenshot identifies the success dialog. */
    fun tapVerifiedResultPoint(point: VisualPoint): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "结果入口点击必须在后台线程执行" }
        return (isDingTalkVisible() || isSystemOverlayVisible()) && tapAt(point.x.toFloat(), point.y.toFloat())
    }

    /** Accessibility fallback for the success dialog; never taps without the matching shift title. */
    fun tapVisibleResultLink(kind: Kind): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "结果入口点击必须在后台线程执行" }
        val root = rootInActiveWindow?.takeIf { it.packageName?.toString() == DINGTALK_PACKAGE } ?: return false
        val screen = Rect().also(root::getBoundsInScreen)
        if (screen.isEmpty) return false
        val nodes = mutableListOf<VisibleNode>()
        collect(root, screen, nodes)
        val title = nodes.firstOrNull { it.label.replace(Regex("\\s+"), "").contains("${kind.label}打卡成功") &&
            it.centerY > 0.55f } ?: return false
        val links = nodes.filter { it.label.replace(Regex("\\s+"), "").contains("查看打卡结果") &&
            it.centerY > title.centerY && it.centerY > 0.65f && it.centerX in 0.2f..0.8f }
        val first = links.firstOrNull() ?: return false
        if (links.any { kotlin.math.abs(it.centerX - first.centerX) > 0.03f ||
                kotlin.math.abs(it.centerY - first.centerY) > 0.03f }) return false
        return click(first.node)
    }

    /** Tap a location obtained from a fresh screenshot of DingTalk. */
    fun tapDingTalkPoint(point: VisualPoint): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "钉钉点击必须在后台线程执行" }
        return isDingTalkVisible() && tapAt(point.x.toFloat(), point.y.toFloat())
    }

    /** Scroll only the current DingTalk page; the caller must reread it after every gesture. */
    fun scrollDingTalkUp(): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "钉钉滚动必须在后台线程执行" }
        if (!isDingTalkVisible()) return false
        val screen = Rect().also { rootInActiveWindow?.getBoundsInScreen(it) }
        if (screen.isEmpty) return false
        val x = screen.exactCenterX()
        val path = Path().apply {
            moveTo(x, screen.top + screen.height() * 0.78f)
            lineTo(x, screen.top + screen.height() * 0.26f)
        }
        return performGesture(path, 550)
    }

    private fun waitForDingTalkRoot(): AccessibilityNodeInfo? {
        repeat(10) {
            rootInActiveWindow?.let { root ->
                if (root.packageName?.toString() == DINGTALK_PACKAGE) return root
            }
            Thread.sleep(300)
        }
        return null
    }

    private data class VisibleNode(
        val node: AccessibilityNodeInfo,
        val label: String,
        val centerX: Float,
        val centerY: Float,
        val selected: Boolean
    )

    private fun collect(node: AccessibilityNodeInfo, screen: Rect, output: MutableList<VisibleNode>) {
        if (!node.isVisibleToUser) return
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!bounds.isEmpty) {
            val label = labelOf(node)
            if (label != null) {
                val center = (bounds.centerY() - screen.top).toFloat() / screen.height()
                val horizontalCenter = (bounds.centerX() - screen.left).toFloat() / screen.width()
                output += VisibleNode(node, label, horizontalCenter, center, selectedInHierarchy(node))
            }
        }
        for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it, screen, output) }
    }

    private fun labelOf(node: AccessibilityNodeInfo): String? {
        val raw = node.text?.toString()?.trim().orEmpty().ifEmpty {
            node.contentDescription?.toString()?.trim().orEmpty()
        }
        if (raw.isEmpty()) return null
        val tabs = listOf("千问办公", "消息", "工作台", "通讯录", "更多")
        return tabs.firstOrNull { raw == it || raw.startsWith("$it，") || raw.startsWith("$it,") || raw.startsWith("$it ") }
            ?: raw
    }

    private fun selectedInHierarchy(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(3) {
            if (current?.isSelected == true || current?.contentDescription?.contains("已选中") == true)
                return true
            current = current?.parent
        }
        return false
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            if (current?.isClickable == true && current?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                return true
            current = current?.parent
        }
        return tap(node)
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        return tapAt(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return performGesture(path, 80)
    }

    private fun performGesture(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val completed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                completed.set(true)
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription) { latch.countDown() }
        }
        if (!dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))) return false
        return latch.await(2, TimeUnit.SECONDS) && completed.get()
    }
}
