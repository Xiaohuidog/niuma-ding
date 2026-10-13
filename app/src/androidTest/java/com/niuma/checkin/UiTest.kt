package com.niuma.checkin

import android.widget.*
import android.content.IntentFilter
import android.content.ComponentName
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.niuma.core.Alert
import com.niuma.core.MailStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiTest {
    private val app = ApplicationProvider.getApplicationContext<CheckinApp>()
    private fun waitFor(predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!predicate()) { check(System.currentTimeMillis() < end) { "UI state timeout" }; Thread.sleep(50) }
    }
    @Test fun entryReflectsSystemAccessibilityServiceState() {
        val service = ComponentName(app, DingTalkWorkbenchNavigator::class.java).flattenToString()
        val enabledInSystem = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.equals(service, ignoreCase = true) } == true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val notice = activity.findViewById<TextView>(R.id.accessibilityNotice)
                assertEquals(if (enabledInSystem) android.view.View.GONE else android.view.View.VISIBLE, notice.visibility)
                if (!enabledInSystem) assertTrue(notice.text.toString().contains("辅助服务未启用"))
            }
        }
    }
    @Test fun accessibilitySettingsCanBeOpenedFromRunSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(IntentFilter(Settings.ACTION_ACCESSIBILITY_SETTINGS), null, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor { var ready = false; scenario.onActivity { ready = it.findViewById<Button>(R.id.navSettings)?.isEnabled == true }; ready }
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.navSettings).performClick()
                    val card = activity.findViewById<LinearLayout>(R.id.checkCard)
                    val buttons = (0 until card.childCount).mapNotNull { card.getChildAt(it) as? Button }
                    val accessibilityButton = buttons.firstOrNull { it.text.toString() == "辅助服务设置" }
                    assertNotNull("运行设置应提供辅助服务设置入口", accessibilityButton)
                    accessibilityButton!!.performClick()
                }
                assertNotNull("应打开系统无障碍设置", instrumentation.waitForMonitorWithTimeout(monitor, 5_000))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
    @Test fun reviewMailAppearsInHistoryWithoutManualScreenshotSendButton() {
        val now = System.currentTimeMillis()
        val id = "ui-review-mail:$now"
        val pendingId = "ui-review-pending:$now"
        val previousClearTime = app.store.transaction { s ->
            val old = s.settings.mailHistoryClearedAt
            s.alerts.add(Alert(id, "review-run:$now", now, now + 86_400_000,
                "[牛马签到][需核查] 测试执行待核查", "结果无法确认",
                status = MailStatus.ACCEPTED, attempts = 1))
            s.alerts.add(Alert(pendingId, "review-pending:$now", now + 1, now + 86_400_000,
                "[牛马签到][需核查] 等待发送的执行", "结果无法确认",
                nextAttempt = now + 600_000))
            old
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor { var ready = false; scenario.onActivity { ready = it.findViewById<Button>(R.id.navMail)?.isEnabled == true }; ready }
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.navMail).performClick()
                    val content = activity.findViewById<android.view.ViewGroup>(R.id.content)
                    fun allTexts(view: android.view.View): List<String> =
                        (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                            (if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { allTexts(view.getChildAt(it)) } else emptyList())
                    val texts = allTexts(content)
                    assertTrue(texts.any { it.contains("测试执行待核查") && it.contains("服务商已接受") })
                    assertTrue(texts.any { it.contains("等待发送的执行") && it.contains("待发送") })
                    assertFalse(texts.any { it.contains("发送需核查截图测试邮件") })
                }
            }
        } finally {
            app.store.transaction { s ->
                s.alerts.removeAll { it.id == id || it.id == pendingId }
                s.settings.mailHistoryClearedAt = previousClearTime
            }
        }
    }
    @Test fun verifiedMailStatusSaysMailSentSuccessfully() {
        val previous = app.store.transaction { s -> s.settings.mailVerified }
        app.store.transaction { s -> s.settings.mailVerified = true }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor { var ready = false; scenario.onActivity { ready = it.findViewById<Button>(R.id.navMail)?.isEnabled == true }; ready }
                scenario.onActivity { it.findViewById<Button>(R.id.navMail).performClick() }
                waitFor {
                    var text = ""
                    scenario.onActivity { text = it.findViewById<TextView>(R.id.mailStatus)?.text?.toString().orEmpty() }
                    text.contains("邮件发送成功")
                }
            }
        } finally {
            app.store.transaction { s -> s.settings.mailVerified = previous }
        }
    }
    @Test fun saveTimesRecreateAndRejectInvalidWithoutLosingInput() {
        val previous = app.store.transaction { s -> s.plans.associate { it.kind to (it.time to it.enabled) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                waitFor { var ready = false; scenario.onActivity { ready = it.findViewById<Button>(R.id.saveTimes)?.isEnabled == true }; ready }
                scenario.onActivity {
                    assertTrue(it.findViewById<Switch>(R.id.inEnabled).isEnabled)
                    assertTrue(it.findViewById<Switch>(R.id.outEnabled).isEnabled)
                    it.findViewById<Switch>(R.id.inEnabled).isChecked = true
                    it.findViewById<Switch>(R.id.outEnabled).isChecked = true
                    it.findViewById<EditText>(R.id.inTime).setText("08:05")
                    it.findViewById<EditText>(R.id.outTime).setText("19:10")
                    it.findViewById<Button>(R.id.saveTimes).performClick()
                }
                waitFor { app.store.transaction { it.plans.first { p -> p.kind == com.niuma.core.Kind.IN }.time == "08:05" } }
                scenario.recreate()
                waitFor { var ready = false; scenario.onActivity { ready = it.findViewById<EditText>(R.id.inTime)?.text.toString() == "08:05" }; ready }
                scenario.onActivity {
                    assertEquals("19:10", it.findViewById<EditText>(R.id.outTime).text.toString())
                    assertTrue(it.findViewById<Switch>(R.id.inEnabled).isChecked)
                    assertTrue(it.findViewById<Switch>(R.id.outEnabled).isChecked)
                    it.findViewById<EditText>(R.id.inTime).setText("19:10")
                    it.findViewById<Button>(R.id.saveTimes).performClick()
                }
                waitFor { var failed = false; scenario.onActivity { failed = it.findViewById<TextView>(R.id.error).text.toString().contains("不能相同") }; failed }
                scenario.onActivity { assertEquals("19:10", it.findViewById<EditText>(R.id.inTime).text.toString()) }
                assertEquals("08:05", app.store.transaction { it.plans.first { p -> p.kind == com.niuma.core.Kind.IN }.time })
            } finally {
                app.engine.savePlanSettings(previous.getValue(com.niuma.core.Kind.IN).first, previous.getValue(com.niuma.core.Kind.OUT).first,
                    previous.getValue(com.niuma.core.Kind.IN).second, previous.getValue(com.niuma.core.Kind.OUT).second, System.currentTimeMillis())
            }
        }
    }
    @Test fun allFourPagesShowRealEmptyAndBlockedStates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor { var ready = false; scenario.onActivity { ready = it.findViewById<Button>(R.id.saveTimes)?.isEnabled == true }; ready }
            scenario.onActivity {
                val toggle = it.findViewById<Switch>(R.id.automaticSwitch)
                assertEquals(android.view.View.VISIBLE, toggle.visibility)
                it.findViewById<Button>(R.id.navRecords).performClick()
                assertEquals(android.view.View.GONE, toggle.visibility)
                assertNotNull(it.findViewById<Spinner>(R.id.filter))
                it.findViewById<Button>(R.id.navMail).performClick()
                assertEquals(android.view.View.GONE, toggle.visibility)
                assertTrue(it.findViewById<Button>(R.id.testMail).isEnabled)
                val clearMail = it.findViewById<ImageButton>(R.id.clearMail)
                assertTrue(clearMail.isEnabled)
                assertEquals("清除邮件记录", clearMail.contentDescription)
                it.findViewById<Button>(R.id.navSettings).performClick()
                assertEquals(android.view.View.GONE, toggle.visibility)
                assertEquals(android.view.View.VISIBLE, it.findViewById<ImageButton>(R.id.recheck).visibility)
                val rules = it.findViewById<LinearLayout>(R.id.rulesCard)
                val checks = it.findViewById<LinearLayout>(R.id.checkCard)
                assertTrue((rules.parent as LinearLayout).indexOfChild(rules) < (checks.parent as LinearLayout).indexOfChild(checks))
                it.findViewById<Button>(R.id.navPlan).performClick()
                assertEquals(android.view.View.VISIBLE, toggle.visibility)
                assertEquals(android.view.View.GONE, it.findViewById<ImageButton>(R.id.recheck).visibility)
            }
        }
    }
    @Test fun removedScriptDisablesAllCheckinEntrypoints() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor { var ready = false; scenario.onActivity { ready = it.findViewById<Button>(R.id.saveTimes)?.isEnabled == true }; ready }
            scenario.onActivity {
                assertFalse(it.findViewById<Switch>(R.id.automaticSwitch).isEnabled)
                assertFalse(it.findViewById<Button>(R.id.checkinNow).isEnabled)
                assertFalse(it.findViewById<Switch>(R.id.inEnabled).isEnabled)
                assertFalse(it.findViewById<Switch>(R.id.outEnabled).isEnabled)
            }
        }
    }
}
