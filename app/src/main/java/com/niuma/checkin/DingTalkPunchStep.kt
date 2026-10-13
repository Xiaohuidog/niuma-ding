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
import com.niuma.core.BEIJING
import com.niuma.core.dateAt
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Step 3 only: wait, inspect the blue punch circle, submit once, then open the result link. */
class DingTalkPunchStep(
    private val app: CheckinApp,
    private val navigator: DingTalkWorkbenchNavigator
) {
    enum class Status { STATISTICS_REACHED, SUCCESS_MODAL_VERIFIED, FAILED, UNKNOWN }
    data class Outcome(val status: Status, val detail: String, val returnedToPrimary: Boolean = false,
                       val kind: Kind? = null)
    data class Prepared(val kind: Kind, val button: VisualPoint)
    data class Preparation(val target: Prepared? = null, val detail: String = "")
    private data class ResultScreen(val statistics: Boolean, val link: VisualPoint?,
        val successVisible: Boolean, val successTime: LocalTime?, val fallbackLink: VisualPoint)

    /** Wait five seconds, then find the one large blue circle without reading its label. */
    fun prepare(scheduledKind: Kind? = null): Preparation {
        check(Looper.myLooper() != Looper.getMainLooper()) { "打卡步骤必须在后台线程执行" }
        app.awaitInitialization()
        if (!navigator.isDingTalkVisible()) return Preparation(detail = "当前不是钉钉打卡页面")

        Thread.sleep(5_000)
        if (!navigator.isDingTalkVisible()) return Preparation(detail = "停留 5 秒后钉钉打卡页面不可见")
        DingTalkVisualReader(app, navigator).use { reader ->
            val screenshot = reader.capture()
                ?: return Preparation(detail = "无法读取大圆形按钮：${reader.lastFailure ?: "截图不可用"}")
            val selected = screenshot.use { screen ->
                PunchVisualPolicy.singleBlueCircle(screen.bitmap.width, screen.bitmap.height,
                    screen.bitmap::getPixel)
            }
                ?: return Preparation(detail = "无法定位唯一蓝色大圆按钮")
            val kind = scheduledKind ?: kindAt(Instant.now().atZone(BEIJING).toLocalTime())
            return Preparation(Prepared(kind, selected))
        }
    }

    fun execute(ticket: Engine.Ticket, prepared: Prepared): Outcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "打卡步骤必须在后台线程执行" }
        require(ticket.date == dateAt(System.currentTimeMillis())) {
            "打卡日期与执行任务不一致"
        }
        if (!navigator.isDingTalkVisible()) return fail(ticket, "蓝色按钮确认后钉钉页面不可见")
        DingTalkVisualReader(app, navigator).use { reader ->
            val now = System.currentTimeMillis()
            val allowed = app.engine.permitSubmit(ticket, Evidence(Result.NOT_DONE, ticket.date,
                ticket.kind, "已定位钉钉打卡页面唯一蓝色大圆按钮", allowsSubmit = true), now, true)
            if (!allowed) return fail(ticket, "打卡提交许可未通过")
            if (!navigator.tapDingTalkPoint(prepared.button)) return unknown(ticket, "蓝色按钮点击结果不明")

            val deadline = SystemClock.elapsedRealtime() + 45_000
            var readableScreens = 0
            var resultTaps = 0
            var lastResultTap = 0L
            var screenshotSaved = false
            val submittedAtElapsed = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(700)
                val modal = reader.capture(allowSystemOverlay = true) ?: continue
                readableScreens++
                val scan = modal.use { screen ->
                    val statistics = StatisticsRecordPolicy(ticket.date, ticket.kind)
                    val successVisible = PunchVisualPolicy.hasSuccessDialog(screen.bitmap.height, screen.text)
                    val receiptTime = Kind.entries.mapNotNull { kind ->
                        PunchVisualPolicy.successDialogTime(kind, ticket.date, now,
                            System.currentTimeMillis(), screen.bitmap.height, screen.text)
                    }.distinct().singleOrNull()
                    val result = ResultScreen(statistics.acceptHeader(screen.text, screen.bitmap.height),
                        Kind.entries.mapNotNull { kind -> PunchVisualPolicy.resultLink(kind,
                            screen.bitmap.width, screen.bitmap.height, screen.text) }.singleOrNull(),
                        successVisible, receiptTime,
                        VisualPoint(screen.bitmap.width / 2, (screen.bitmap.height * 0.80f).roundToInt()))
                    if (result.successVisible && !screenshotSaved)
                        screenshotSaved = app.screenCapture.saveEvidence(ticket.runId, screen.bitmap).ready
                    result
                }
                if (scan.successVisible)
                    return successFromModal(ticket, scan.successTime, now,
                        scan.link ?: scan.fallbackLink)
                if (scan.statistics) {
                    return Outcome(Status.STATISTICS_REACHED,
                        "已从成功弹窗的“查看打卡结果”进入今日统计页；打卡记录待下一步核验")
                }
                val nowAfterCapture = SystemClock.elapsedRealtime()
                if (resultTaps < 8 && nowAfterCapture - submittedAtElapsed >= 2_500 &&
                    nowAfterCapture - lastResultTap >= 2_500) {
                    val point = if (resultTaps < 2) scan.link ?: scan.fallbackLink else scan.fallbackLink
                    val tapped = navigator.tapVerifiedResultPoint(point) ||
                        navigator.tapVisibleResultLink(ticket.kind)
                    if (tapped) {
                        resultTaps++
                        lastResultTap = nowAfterCapture
                    }
                }
            }
            val reason = if (readableScreens == 0) "点击蓝色按钮后无法读取成功弹窗：${reader.lastFailure ?: "截图不可用"}"
                else if (resultTaps == 0) "点击蓝色按钮后未能定位成功弹窗底部的“查看打卡结果”入口"
                else "已点击成功弹窗底部的“查看打卡结果”${resultTaps}次，但未确认进入今日统计页"
            return unknown(ticket, reason)
        }
    }

    private fun kindAt(time: LocalTime): Kind = app.store.transaction { state ->
        val start = LocalTime.parse(state.plans.first { it.kind == Kind.IN }.time)
        if (time.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) <= start) Kind.IN else Kind.OUT
    }

    private fun successFromModal(ticket: Engine.Ticket, time: LocalTime?, submittedAt: Long,
                                 visibleLink: VisualPoint?): Outcome {
        val punchTime = time ?: Instant.ofEpochMilli(submittedAt).atZone(BEIJING).toLocalTime()
        val actualKind = kindAt(punchTime)
        val resolved = app.engine.acceptReceiptKind(ticket, actualKind)
            ?: return unknown(ticket, "打卡时间对应${actualKind.label}，与当前自动计划不一致")
        val detail = "打卡时间：${ticket.date} ${punchTime.format(DateTimeFormatter.ofPattern("HH:mm"))}（北京时间）；依据：钉钉打卡成功弹窗"
        app.engine.complete(resolved, Evidence(Result.COMPLETE, ticket.date, actualKind, detail),
            System.currentTimeMillis())
        app.enqueueMail()
        if (visibleLink != null) {
            navigator.tapVerifiedResultPoint(visibleLink) || navigator.tapVisibleResultLink(actualKind)
            Thread.sleep(900)
        }
        val returned = runCatching { navigator.enterWorkbench().reached }.getOrDefault(false)
        return Outcome(Status.SUCCESS_MODAL_VERIFIED,
            if (returned) detail else "$detail；未能返回工作台", returned, actualKind)
    }

    private fun fail(ticket: Engine.Ticket, reason: String): Outcome {
        app.captureDingTalkFailure(ticket.runId, navigator)
        app.engine.complete(ticket, Evidence(Result.FAILURE, summary = reason), System.currentTimeMillis())
        app.enqueueMail()
        val returned = runCatching { navigator.enterWorkbench().reached }.getOrDefault(false)
        return Outcome(Status.FAILED, reason, returned)
    }

    private fun unknown(ticket: Engine.Ticket, reason: String): Outcome {
        app.captureDingTalkFailure(ticket.runId, navigator)
        app.engine.completeSubmittedUnknown(ticket, reason, System.currentTimeMillis())
        app.enqueueMail()
        val returned = runCatching { navigator.enterWorkbench().reached }.getOrDefault(false)
        return Outcome(Status.UNKNOWN, reason, returned)
    }

}
