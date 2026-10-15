package com.niuma.checkin

import android.os.Looper
import com.niuma.core.Engine
import com.niuma.core.Evidence
import com.niuma.core.Kind
import com.niuma.core.Result
import com.niuma.core.dateAt

/** Executes the four user specified steps in order, with one Engine ticket throughout. */
class DingTalkCheckinFlow(private val app: CheckinApp) {
    data class Outcome(val success: Boolean, val detail: String, val returnedToWorkbench: Boolean,
                       val kind: Kind? = null)

    fun execute(automaticTicket: Engine.Ticket? = null): Outcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "签到流程必须在后台线程执行" }
        app.awaitInitialization()
        val navigator = DingTalkWorkbenchNavigator.connected
            ?: return failWithoutNavigator("请先在系统设置中启用钉钉工作台导航辅助服务", automaticTicket)
        if (!app.screenCapture.authorized)
            return failBeforeSubmit(navigator, "请先在运行设置中授权本机截图会话", automaticTicket)
        val mailReady = app.store.transaction { it.settings.mailVerified && it.settings.recipient.isNotBlank() }
        if (!app.mail.configured || !mailReady)
            return failBeforeSubmit(navigator, "请先完成收件邮箱与发信服务配置，并发送测试邮件验证", automaticTicket)

        var ticket: Engine.Ticket? = automaticTicket
        return try {
            val workbench = navigator.enterWorkbench()
            if (!workbench.reached) return failBeforeSubmit(navigator, "进入工作台失败：${workbench.detail}", ticket)

            val attendance = navigator.openMineAttendance()
            if (!attendance.reached) return failBeforeSubmit(navigator, "打开“我的”考勤打卡失败：${attendance.detail}", ticket)

            val punchStep = DingTalkPunchStep(app, navigator)
            var prepared = punchStep.prepare(automaticTicket?.kind)
            if (prepared.target == null && navigator.dismissOneStepWindowTutorial()) {
                Thread.sleep(400)
                val resumedWorkbench = navigator.enterWorkbench()
                if (!resumedWorkbench.reached)
                    return failBeforeSubmit(navigator, "关闭小窗引导后进入工作台失败：${resumedWorkbench.detail}", ticket)
                val resumedAttendance = navigator.openMineAttendance()
                if (!resumedAttendance.reached)
                    return failBeforeSubmit(navigator, "关闭小窗引导后打开考勤打卡失败：${resumedAttendance.detail}", ticket)
                prepared = punchStep.prepare(automaticTicket?.kind)
            }
            val target = prepared.target
                ?: return failBeforeSubmit(navigator, prepared.detail, ticket)
            if (automaticTicket != null && dateAt(System.currentTimeMillis()) != automaticTicket.date)
                return failBeforeSubmit(navigator, "打卡日期与定时计划不一致", ticket)
            if (automaticTicket == null) {
                app.engine.requestLatest(target.kind, System.currentTimeMillis(), true)
                ticket = app.engine.begin(System.currentTimeMillis(), true)
                    ?: return failBeforeSubmit(navigator, "签到任务未能启动，请查看执行记录")
            }
            val currentTicket = requireNotNull(ticket)
            val punch = punchStep.execute(currentTicket, target)
            when (punch.status) {
                    DingTalkPunchStep.Status.SUCCESS_MODAL_VERIFIED ->
                        Outcome(true, punch.detail, punch.returnedToPrimary, punch.kind ?: target.kind)
                    DingTalkPunchStep.Status.FAILED -> Outcome(false, punch.detail, punch.returnedToPrimary, target.kind)
                    DingTalkPunchStep.Status.UNKNOWN -> {
                        Outcome(false, "结果待核查：${punch.detail}", punch.returnedToPrimary, target.kind)
                    }
                    DingTalkPunchStep.Status.STATISTICS_REACHED -> {
                        val result = DingTalkStatisticsStep(app, navigator).execute(currentTicket)
                        val returned = result.returnedToWorkbench
                        val detail = when {
                            result.status == DingTalkStatisticsStep.Status.UNKNOWN ->
                                "结果待核查：${result.detail}${if (returned) "；已返回工作台" else ""}"
                            returned -> result.detail
                            else -> "${result.detail}；未能返回工作台"
                        }
                        Outcome(result.status == DingTalkStatisticsStep.Status.SUCCESS, detail, returned, target.kind)
                    }
            }
        } catch (error: Exception) {
            val currentTicket = ticket
                ?: return failBeforeSubmit(navigator, "签到流程中断：${error.message ?: error.javaClass.simpleName}")
            val submitted = app.store.transaction { state ->
                state.runs.firstOrNull { it.id == currentTicket.runId }?.attempts?.lastOrNull()?.submitted == true
            }
            val reason = "签到流程中断：${error.message ?: error.javaClass.simpleName}"
            app.captureDingTalkFailure(currentTicket.runId, navigator)
            if (submitted) app.engine.completeSubmittedUnknown(currentTicket, reason, System.currentTimeMillis())
            else app.engine.complete(currentTicket, Evidence(Result.FAILURE, summary = reason), System.currentTimeMillis())
            app.enqueueMail()
            val returned = if (submitted) false else runCatching { navigator.enterWorkbench().reached }.getOrDefault(false)
            Outcome(false, "签到流程中断，结果待核查；请查看执行记录", returned, currentTicket.kind)
        }
    }

    private fun failBeforeSubmit(navigator: DingTalkWorkbenchNavigator, reason: String,
                                 ticket: Engine.Ticket? = null): Outcome {
        ticket?.let { app.captureDingTalkFailure(it.runId, navigator) }
        if (ticket == null) app.engine.recordManualFailure(System.currentTimeMillis(), reason)
        else app.engine.complete(ticket, Evidence(Result.FAILURE, summary = reason), System.currentTimeMillis())
        app.enqueueMail()
        return Outcome(false, reason, runCatching { navigator.enterWorkbench().reached }.getOrDefault(false))
    }

    private fun failWithoutNavigator(reason: String, ticket: Engine.Ticket?): Outcome {
        if (ticket == null) app.engine.recordManualFailure(System.currentTimeMillis(), reason)
        else app.engine.complete(ticket, Evidence(Result.FAILURE, summary = reason), System.currentTimeMillis())
        app.enqueueMail()
        return Outcome(false, reason, false, ticket?.kind)
    }
}
