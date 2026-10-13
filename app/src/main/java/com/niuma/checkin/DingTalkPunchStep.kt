package com.niuma.checkin

import android.os.Looper
import android.os.SystemClock
import com.niuma.core.Engine
import com.niuma.core.Evidence
import com.niuma.core.Kind
import com.niuma.core.PunchVisualPolicy
import com.niuma.core.Result
import com.niuma.core.StatisticsRecordPolicy
import com.niuma.core.VisualPoint
import com.niuma.core.dateAt
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Step 3 only: wait, inspect the blue punch circle, submit once, then open the result link. */
class DingTalkPunchStep(
    private val app: CheckinApp,
    private val navigator: DingTalkWorkbenchNavigator
) {
    enum class Status { STATISTICS_REACHED, SUCCESS_MODAL_VERIFIED, FAILED, UNKNOWN }
    data class Outcome(val status: Status, val detail: String, val returnedToPrimary: Boolean = false)
    data class Prepared(val kind: Kind, val button: VisualPoint)
    data class Preparation(val target: Prepared? = null, val detail: String = "")

    /** Wait five seconds, then choose only the one blue large circle labeled 上班/下班打卡. */
    fun prepare(): Preparation {
        check(Looper.myLooper() != Looper.getMainLooper()) { "打卡步骤必须在后台线程执行" }
        app.awaitInitialization()
        if (!navigator.isDingTalkVisible()) return Preparation(detail = "当前不是钉钉打卡页面")

        Thread.sleep(5_000)
        if (!navigator.isDingTalkVisible()) return Preparation(detail = "停留 5 秒后钉钉打卡页面不可见")
        DingTalkVisualReader(app, navigator).use { reader ->
            val screenshot = reader.capture()
                ?: return Preparation(detail = "无法读取大圆形按钮：${reader.lastFailure ?: "截图不可用"}")
            val selected = screenshot.use { screen ->
                val today = dateAt(System.currentTimeMillis())
                PunchVisualPolicy.singleBlueButton(today.monthValue, today.dayOfMonth,
                    screen.bitmap.width, screen.bitmap.height, screen.text, screen.bitmap::getPixel)
            }
                ?: return Preparation(detail = "无法唯一确认当天上班或下班的蓝色大圆按钮")
            return Preparation(Prepared(selected.kind, selected.point))
        }
    }

    fun execute(ticket: Engine.Ticket, prepared: Prepared): Outcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "打卡步骤必须在后台线程执行" }
        require(ticket.kind == prepared.kind && ticket.date == dateAt(System.currentTimeMillis())) {
            "已确认的打卡班次或日期与执行任务不一致"
        }
        if (!navigator.isDingTalkVisible()) return fail(ticket, "蓝色按钮确认后钉钉页面不可见")
        DingTalkVisualReader(app, navigator).use { reader ->
            val now = System.currentTimeMillis()
            val allowed = app.engine.permitSubmit(ticket, Evidence(Result.NOT_DONE, ticket.date,
                ticket.kind, "已确认当日对应班次的大圆形按钮为蓝色", allowsSubmit = true), now, true)
            if (!allowed) return fail(ticket, "打卡提交许可未通过")
            if (!navigator.tapDingTalkPoint(prepared.button)) return unknown(ticket, "蓝色按钮点击结果不明")

            val deadline = SystemClock.elapsedRealtime() + 45_000
            var readableScreens = 0
            var resultTaps = 0
            var lastResultTap = 0L
            var modalTime: LocalTime? = null
            var modalSeenAt = 0L
            while (SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(700)
                val modal = reader.capture(allowSystemOverlay = true) ?: continue
                readableScreens++
                val (statisticsConfirmed, link, verifiedTime) = modal.use { screen ->
                    val statistics = StatisticsRecordPolicy(ticket.date, ticket.kind)
                    Triple(statistics.acceptHeader(screen.text, screen.bitmap.height),
                        PunchVisualPolicy.resultLink(ticket.kind, screen.bitmap.width, screen.bitmap.height, screen.text),
                        PunchVisualPolicy.successDialogTime(ticket.kind, ticket.date, now,
                            System.currentTimeMillis(), screen.bitmap.height, screen.text))
                }
                if (verifiedTime != null) {
                    modalTime = verifiedTime
                    if (modalSeenAt == 0L) modalSeenAt = SystemClock.elapsedRealtime()
                }
                if (statisticsConfirmed && resultTaps > 0) {
                    return Outcome(Status.STATISTICS_REACHED,
                        "已从成功弹窗的“查看打卡结果”进入今日统计页；打卡记录待下一步核验")
                }
                val nowAfterCapture = SystemClock.elapsedRealtime()
                if (resultTaps < 5 && nowAfterCapture - lastResultTap >= 2_500) {
                    val tapped = if (link != null) navigator.tapVerifiedResultPoint(link)
                        else navigator.tapVisibleResultLink(ticket.kind)
                    if (tapped) {
                        resultTaps++
                        lastResultTap = nowAfterCapture
                    }
                }
                if (modalTime != null && nowAfterCapture - modalSeenAt >= 12_000L)
                    return successFromModal(ticket, modalTime!!)
            }
            if (modalTime != null) return successFromModal(ticket, modalTime!!)
            val reason = if (readableScreens == 0) "点击蓝色按钮后无法读取成功弹窗：${reader.lastFailure ?: "截图不可用"}"
                else if (resultTaps == 0) "点击蓝色按钮后未能定位成功弹窗底部的“查看打卡结果”入口"
                else "已点击成功弹窗底部的“查看打卡结果”${resultTaps}次，但未确认进入今日统计页"
            return unknown(ticket, reason)
        }
    }

    private fun successFromModal(ticket: Engine.Ticket, time: LocalTime): Outcome {
        val stamp = "${ticket.date} ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        val detail = "打卡时间：$stamp（北京时间）；依据：钉钉${ticket.kind.label}打卡成功弹窗"
        app.engine.complete(ticket, Evidence(Result.COMPLETE, ticket.date, ticket.kind, detail),
            System.currentTimeMillis())
        app.enqueueMail()
        val returned = runCatching { navigator.enterWorkbench().reached }.getOrDefault(false)
        return Outcome(Status.SUCCESS_MODAL_VERIFIED,
            if (returned) detail else "$detail；未能返回工作台", returned)
    }

    private fun fail(ticket: Engine.Ticket, reason: String): Outcome {
        app.engine.complete(ticket, Evidence(Result.FAILURE, summary = reason), System.currentTimeMillis())
        app.enqueueMail()
        val returned = runCatching { navigator.enterWorkbench().reached }.getOrDefault(false)
        return Outcome(Status.FAILED, reason, returned)
    }

    private fun unknown(ticket: Engine.Ticket, reason: String): Outcome {
        app.engine.completeSubmittedUnknown(ticket, reason, System.currentTimeMillis())
        app.enqueueMail()
        return Outcome(Status.UNKNOWN, reason)
    }

}
