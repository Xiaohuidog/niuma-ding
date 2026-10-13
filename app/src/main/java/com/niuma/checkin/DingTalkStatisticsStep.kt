package com.niuma.checkin

import android.os.Looper
import android.os.SystemClock
import com.niuma.core.Engine
import com.niuma.core.Evidence
import com.niuma.core.Kind
import com.niuma.core.Result
import com.niuma.core.StatisticsRecordPolicy
import com.niuma.core.dateAt
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Step 4: verify the selected day's Statistics records, then return to Workbench. */
class DingTalkStatisticsStep(
    private val app: CheckinApp,
    private val navigator: DingTalkWorkbenchNavigator
) {
    enum class Status { SUCCESS, UNKNOWN }
    data class Outcome(val status: Status, val punchTime: LocalTime? = null,
                       val detail: String, val returnedToWorkbench: Boolean)

    fun execute(ticket: Engine.Ticket): Outcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "统计页核验必须在后台线程执行" }
        app.awaitInitialization()
        if (dateAt(System.currentTimeMillis()) != ticket.date)
            return unknown(ticket, "当前日期与签到任务日期不一致")
        val collector = StatisticsRecordPolicy(ticket.date, ticket.kind)
        DingTalkVisualReader(app, navigator).use { reader ->
            var headerFound = false
            var readableScreens = 0
            val headerDeadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < headerDeadline) {
                // The success notification can briefly own the active window while Statistics loads.
                val screen = reader.capture(allowSystemOverlay = true)
                if (screen != null) {
                    readableScreens++
                    screen.use {
                        if (collector.acceptHeader(it.text, it.bitmap.height)) {
                            collector.addVisible(it.text, it.bitmap.height)
                            headerFound = true
                        }
                    }
                }
                if (headerFound) break
                Thread.sleep(800)
            }
            if (!headerFound) {
                val reason = if (readableScreens == 0)
                    "无法读取统计页：${reader.lastFailure ?: "截图不可用"}"
                else "统计页截图未能确认选中日期为今日"
                return unknown(ticket, reason)
            }

            if (ticket.kind == Kind.IN) {
                val time = collector.verifiedTime()
                    ?: return unknown(ticket, "今日统计页未能唯一确认上班打卡时间")
                return success(ticket, time)
            }

            for (scroll in 0 until 20) {
                // The result page can already be at its bottom. Read it before requesting
                // another gesture, since a failed gesture must not hide visible evidence.
                reader.capture()?.use { collector.addVisible(it.text, it.bitmap.height) }
                if (collector.atBottom()) break
                if (!navigator.scrollDingTalkUp()) {
                    reader.capture()?.use { collector.addVisible(it.text, it.bitmap.height) }
                    if (!collector.atBottom())
                        return unknown(ticket, "统计页无法继续滑向底部")
                    break
                }
                Thread.sleep(600)
            }
            val time = collector.verifiedTime()
                ?: return unknown(ticket, "未到今日统计记录底部，或未找到最后的下班打卡时间")
            return success(ticket, time)
        }
    }

    private fun success(ticket: Engine.Ticket, time: LocalTime): Outcome {
        val stamp = "${ticket.date} ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        val detail = "打卡时间：$stamp（北京时间）；依据：钉钉今日统计页${ticket.kind.label}记录"
        app.engine.complete(ticket, Evidence(Result.COMPLETE, ticket.date, ticket.kind, detail),
            System.currentTimeMillis())
        app.enqueueMail()
        val returned = runCatching { navigator.enterWorkbench().reached }.getOrDefault(false)
        return Outcome(Status.SUCCESS, time, detail, returned)
    }

    private fun unknown(ticket: Engine.Ticket, reason: String): Outcome {
        app.engine.completeSubmittedUnknown(ticket, reason, System.currentTimeMillis())
        app.enqueueMail()
        val returned = runCatching { navigator.enterWorkbench().reached }.getOrDefault(false)
        return Outcome(Status.UNKNOWN, detail = reason, returnedToWorkbench = returned)
    }
}
