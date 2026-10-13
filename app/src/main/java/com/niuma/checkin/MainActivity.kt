package com.niuma.checkin

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.niuma.checkin.databinding.*
import com.niuma.core.*

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var model: MainViewModel
    private var current: State? = null
    private var updatingAutomaticSwitch = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.version.text = packageManager.getPackageInfo(packageName, 0).versionName?.let { "v$it" }.orEmpty()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        model = ViewModelProvider(this)[MainViewModel::class.java]
        listOf(binding.navPlan, binding.navRecords, binding.navMail, binding.navSettings).forEachIndexed { index, button ->
            button.setOnClickListener { model.page = index; model.error.value = null; current?.let(::render) }
        }
        binding.automaticSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!updatingAutomaticSwitch) {
                // Keep the persisted state visible until validation and storage succeed.
                current?.let(::showAutomaticState)
                model.change {
                    val mailReady = model.app.store.transaction { it.settings.mailVerified && it.settings.recipient.isNotBlank() }
                    model.app.engine.setAutomaticEnabled(enabled, System.currentTimeMillis(),
                        model.app.automaticConfigured() && model.app.mail.configured && mailReady)
                }
            }
        }
        binding.reload.setOnClickListener { model.refresh() }
        binding.recheck.setOnClickListener { model.app.runtimeError = null; model.refresh() }
        binding.clearRecords.setOnClickListener {
            AlertDialog.Builder(this).setTitle("清空打卡记录")
                .setMessage("清空所有已结束的打卡记录及关联通知？计划和设置会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ -> model.change("打卡记录已清空") {
                    val ids = model.app.engine.clearCheckinHistory()
                    val evidenceDir = java.io.File(filesDir, "screenshot-evidence")
                    ids.forEach { java.io.File(evidenceDir, screenshotEvidenceFileName(it)).delete() }
                } }.show()
        }
        model.saved.observe(this) { message ->
            if (message != null) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); model.saved.value = null }
        }
        model.state.observe(this) { current = it; render(it) }
        model.error.observe(this) {
            binding.error.text = it
            binding.error.visibility = if (it == null) View.GONE else View.VISIBLE
            binding.reload.visibility = if (it == null) View.GONE else View.VISIBLE
        }
        model.busy.observe(this) { busy ->
            binding.automaticSwitch.isEnabled = !busy && current != null &&
                (current!!.automaticEnabled ||
                    (current!!.settings.mailVerified && model.app.mail.configured && model.app.automaticConfigured()))
            binding.recheck.isEnabled = !busy && current != null
            current?.let(::showAutomaticState)
            enableTree(binding.content, !busy)
            if (!busy) current?.let { applyGates(it) }
        }
        if (model.state.value == null) binding.content.addView(TextView(this).apply { text = getString(R.string.loading); setPadding(24, 24, 24, 24) })
    }
    override fun onResume() {
        super.onResume()
        updateAccessibilityNotice()
        if (model.page == 3) current?.let(::render)
        model.refresh()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenCaptureSession.REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) try {
                startForegroundService(Intent(this, ExecutionService::class.java)
                    .setAction(ExecutionService.ACTION_CAPTURE_SESSION)
                    .putExtra(ScreenCaptureSession.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenCaptureSession.EXTRA_DATA, data))
                model.saved.value = "正在建立截图会话"
            } catch (_: RuntimeException) { model.error.value = "截图前台服务启动失败，截图会话无法保持" }
            else model.saved.value = "截图授权未完成"
            current?.let(::render)
        }
    }
    private fun enableTree(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) enableTree(view.getChildAt(i), enabled)
    }
    private fun applyGates(s: State) {
        binding.content.findViewById<Switch>(R.id.inEnabled)?.isEnabled = model.busy.value != true
        binding.content.findViewById<Switch>(R.id.outEnabled)?.isEnabled = model.busy.value != true
        binding.content.findViewById<Button>(R.id.testMail)?.isEnabled = true
    }
    private fun showAutomaticState(s: State) {
        updatingAutomaticSwitch = true
        binding.automaticSwitch.isChecked = s.automaticEnabled
        updatingAutomaticSwitch = false
    }
    private fun render(s: State) {
        updateAccessibilityNotice()
        binding.automaticSwitch.visibility = if (model.page == 0) View.VISIBLE else View.GONE
        binding.version.visibility = if (model.page == 0) View.VISIBLE else View.GONE
        binding.recheck.visibility = if (model.page == 3) View.VISIBLE else View.GONE
        binding.clearRecords.visibility = if (model.page == 1) View.VISIBLE else View.GONE
        showAutomaticState(s)
        binding.automaticSwitch.isEnabled = model.busy.value != true &&
            (s.automaticEnabled || (s.settings.mailVerified && model.app.mail.configured && model.app.automaticConfigured()))
        binding.title.text = listOf("牛马签到", "打卡记录", "通知设置", "运行设置")[model.page]
        listOf(binding.navPlan, binding.navRecords, binding.navMail, binding.navSettings).forEachIndexed { i, b ->
            b.isSelected = i == model.page
            b.setTextColor(getColor(if (b.isSelected) R.color.blue else R.color.muted))
        }
        binding.content.removeAllViews()
        when (model.page) { 0 -> plan(s); 1 -> records(s); 2 -> mail(s); 3 -> settings(s) }
        applyGates(s)
    }
    private fun plan(s: State) {
        val b = PagePlanBinding.inflate(layoutInflater, binding.content, true)
        val next = if (s.automaticEnabled) s.plans.filter { it.enabled }.mapNotNull { it.nextDue }.minOrNull() else null
        b.nextTime.text = next?.let { stamp(it).take(16) } ?: "—"
        b.inTime.setText(s.plans.first { it.kind == Kind.IN }.time)
        b.outTime.setText(s.plans.first { it.kind == Kind.OUT }.time)
        b.inEnabled.isChecked = s.plans.first { it.kind == Kind.IN }.enabled
        b.outEnabled.isChecked = s.plans.first { it.kind == Kind.OUT }.enabled
        b.checkinNow.isEnabled = model.busy.value != true
        b.checkinNow.setOnClickListener { model.runManual() }
        b.saveTimes.setOnClickListener {
            val input = b.inTime.text.toString(); val output = b.outTime.text.toString()
            val inputEnabled = b.inEnabled.isChecked; val outputEnabled = b.outEnabled.isChecked
            model.change("设置已保存") { model.app.engine.savePlanSettings(input, output, inputEnabled, outputEnabled, System.currentTimeMillis()) }
        }
    }
    private fun records(s: State) {
        val b = PageRecordsBinding.inflate(layoutInflater, binding.content, true)
        b.filter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("全部", "成功", "需处理"))
        b.filter.setSelection(model.filter)
        fun rows() {
            b.rows.removeAllViews()
            val runs = s.runs.sortedByDescending { it.scheduled }.filter { run ->
                val result = RunPresentation.finalResultLabel(run.status)
                result != null && when (model.filter) { 1 -> result == "成功"; 2 -> result == "失败"; else -> true }
            }
            if (runs.isEmpty()) b.rows.addView(text(if (s.runs.isEmpty()) "还没有执行记录，启用计划后将在这里显示" else "暂无符合条件的记录"))
            runs.take(model.recordLimit).forEach { run ->
                val alert = s.alerts.firstOrNull { it.runId == run.id }
                val mail = alert?.takeIf { it.attempts > 0 && it.status != MailStatus.SENDING }?.let {
                    "邮件：${it.status.label}"
                }
                val result = if (run.reviewInProgress(alert, System.currentTimeMillis())) "复核中"
                    else requireNotNull(RunPresentation.finalResultLabel(run.status))
                b.rows.addView(TextView(this).apply {
                    text = listOf("${run.date} · ${run.kind.label} · $result",
                        "${stamp(run.scheduled)} · ${run.attempts.size} 次", mail).filterNotNull().joinToString("\n")
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    setTextColor(getColor(if (result == "失败") R.color.attention else R.color.ink))
                    textSize = 15f
                    setPadding(dp(16), dp(12), dp(16), dp(12)); minHeight = dp(72)
                    setBackgroundResource(R.drawable.card)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    }
                    isClickable = true
                    setOnClickListener {
                        val attempts = if (run.attempts.isEmpty()) "尚未开始尝试" else run.attempts.joinToString("\n\n") { attempt ->
                            val finished = attempt.finished?.let(::stamp) ?: "未结束"
                            "第 ${attempt.number} 次\n开始：${stamp(attempt.started)}\n结束：$finished\n是否提交：${if (attempt.submitted) "是" else "否"}\n结果：${attempt.reason}"
                        }
                        val executionEvents = s.executionEvents.filter { it.runId == run.id }.sortedBy { it.created }
                        val eventDetail = if (executionEvents.isEmpty()) "" else executionEvents.joinToString(
                            prefix = "\n\n执行过程：\n", separator = "\n"
                        ) { event ->
                            val stage = when (event.stage) {
                                AutomaticExecutionStage.SERVICE_STARTED -> "服务启动"
                                AutomaticExecutionStage.WAKE_LOCK_HELD -> "设备唤醒"
                                AutomaticExecutionStage.UNLOCK_CONFIRMED -> "屏幕解锁"
                                AutomaticExecutionStage.WIFI_VALIDATED -> "网络恢复"
                                AutomaticExecutionStage.ACCESSIBILITY_CONNECTED -> "辅助服务"
                                AutomaticExecutionStage.PAGE_RECOGNIZED -> "页面识别"
                                AutomaticExecutionStage.FINISHED -> "执行结束"
                                AutomaticExecutionStage.READ_ONLY_VERIFICATION -> "只读复核"
                            }
                            val result = when (event.result) {
                                ExecutionEventResult.STARTED -> "开始"
                                ExecutionEventResult.PASSED -> "通过"
                                ExecutionEventResult.FAILED -> "失败"
                            }
                            "${stamp(event.created)} · $stage · $result${event.reason.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}"
                        }
                        val mailDetail = alert?.let { "\n\n通知邮件：${it.status.label}（${it.attempts}/3 次）\n${it.reason}" }.orEmpty()
                        AlertDialog.Builder(this@MainActivity).setTitle("${run.date} ${run.kind.label}签到 · $result")
                            .setMessage("计划：${stamp(run.scheduled)}\n结束：${run.finished?.let(::stamp) ?: "未结束"}\n最终原因：${run.reason.ifBlank { "暂无" }}$eventDetail\n\n$attempts$mailDetail")
                            .setPositiveButton("关闭", null).show()
                    }
                })
            }
            b.more.visibility = if (runs.size > model.recordLimit) View.VISIBLE else View.GONE
        }
        b.filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { model.filter = position; rows() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        b.more.setOnClickListener { model.recordLimit += 20; rows() }
        rows()
    }
    private fun mail(s: State) {
        val b = PageMailBinding.inflate(layoutInflater, binding.content, true)
        b.recipient.setText(s.settings.recipient)
        b.mailStatus.text = if (s.settings.mailVerified)
            "当前状态：邮件配置已验证（邮件发送成功）"
        else "当前状态：邮件尚未验证；立即打卡前需完成配置和测试"
        b.mailStatus.visibility = View.VISIBLE
        b.saveMail.setOnClickListener {
            val value = b.recipient.text.toString()
            model.change("收件邮箱已保存") { model.app.engine.configureMail(value, s.settings.credentialRef) }
        }
        b.testMail.setOnClickListener {
            if (b.recipient.text.toString().trim() != s.settings.recipient) { model.error.value = "请先保存修改后的邮箱"; return@setOnClickListener }
            if (s.settings.recipient.isBlank()) { model.error.value = "请先填写并保存收件邮箱"; return@setOnClickListener }
            if (!model.app.mail.configured) { model.error.value = "发信服务尚未配置"; return@setOnClickListener }
            if (!model.app.online()) { model.error.value = "设备离线，测试邮件未发送"; return@setOnClickListener }
            b.mailStatus.text = "正在发送测试邮件…"
            model.change("邮件发送成功") {
                val now = System.currentTimeMillis()
                val alert = Alert("test:$now", null, now, now + 60_000, "[牛马签到] 测试邮件", "这是一封配置测试邮件，无需回执。")
                val accepted = try { model.app.mail.send(alert, s.settings.recipient, s.settings.credentialRef) }
                catch (_: Exception) { throw IllegalArgumentException("邮件发送失败，请检查网络和发信配置") }
                require(accepted) { "邮件发送失败，请检查发信配置" }
                model.app.engine.acceptMailTest(s.settings.recipient, s.settings.credentialRef)
            }
        }
        b.clearMail.setOnClickListener {
            model.change("邮件记录已清空") { model.app.engine.clearMailHistory(System.currentTimeMillis()) }
        }
        val visibleAlerts = s.alerts.filter { it.created > s.settings.mailHistoryClearedAt }
        if (visibleAlerts.isEmpty()) b.alerts.addView(text("暂无通知邮件"))
        visibleAlerts.sortedByDescending { it.created }.forEach { a ->
            b.alerts.addView(Button(this).apply {
                val result = a.status.label
                text = "${a.subject}\n$result"
                isAllCaps = false
                setOnClickListener { AlertDialog.Builder(this@MainActivity).setTitle(result)
                    .setMessage("${a.body}\n\n${a.reason}").setPositiveButton("关闭", null).show() }
            })
        }
    }
    private fun settings(s: State) {
        val b = PageSettingsBinding.inflate(layoutInflater, binding.content, true)
        b.accessibilityStatus.text = if (model.app.accessibilityEnabled())
            "已启用：钉钉工作台导航辅助服务"
        else "未启用：钉钉工作台导航辅助服务，请在系统无障碍设置中开启"
        b.accessibilitySettings.setOnClickListener { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.checkResults.text = model.app.checks().joinToString("\n\n") { "${it.label} · ${if (it.passed) "条件通过" else "需要处理"}\n${it.detail}" } +
            "\n\n邮件配置 · ${if (s.settings.mailVerified) "已完成" else "待配置发信服务和测试"}"
        val p = model.app.engine.policy
        b.rules.text = "立即打卡与定时计划共用四步流程。定时运行会唤亮屏幕，但需要关闭密码、指纹和图案锁，并保持截图会话有效及 Wi-Fi 可用；条件失效会记录失败并通知。\n历史执行记录保留 ${p.retentionDays} 天。"
        val batteryExempt = model.app.backgroundAllowed()
        b.batteryOptimizationStatus.text = if (batteryExempt) "已关闭：关闭电池优化" else "未关闭：关闭电池优化"
        b.allowBackground.setOnClickListener {
            if (model.app.backgroundAllowed())
                openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            else openBatteryOptimizationSettings()
        }
        b.captureStatus.text = if (model.app.screenCapture.authorized)
            "已授权：可读取钉钉页面并核验打卡结果"
        else "未授权：立即打卡前须授权本机截图"
        b.authorizeCapture.setOnClickListener { model.app.screenCapture.requestPermission(this) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun updateAccessibilityNotice() {
        val enabled = (application as CheckinApp).accessibilityEnabled()
        binding.accessibilityNotice.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.accessibilityNotice.text = if (enabled) "" else "辅助服务未启用，请在“运行设置”中打开“辅助服务设置”"
    }
    private fun text(value: String) = TextView(this).apply { text = value; textSize = 15f; setPadding(20, 20, 20, 20); setTextColor(getColor(R.color.ink)) }
    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: android.content.ActivityNotFoundException) {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { model.error.value = "当前系统无此入口，请在系统设置中手动查找" }
    }
}
