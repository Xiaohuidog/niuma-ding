package com.niuma.core

import java.time.LocalDate

/** All decisions that consume an attempt or permit submission commit before the caller acts. */
class Engine(val store: Store, val calendar: WorkCalendar = WorkCalendar.bundled(), val policy: Policy = Policy(),
             private val scheduleOffsetSeconds: ((Kind, LocalDate, String) -> Int)? = null) {
    fun upgradeRandomSchedule(now: Long) = store.transaction { s ->
        if (s.settings.scheduleVersion < RANDOM_SCHEDULE_VERSION) {
            s.settings.scheduleVersion = RANDOM_SCHEDULE_VERSION
            s.plans.forEach { plan ->
                plan.nextDue = if (plan.enabled && !s.settings.paused) future(plan, now) else null
            }
        }
    }

    fun saveTimes(input: String, output: String, now: Long) = store.transaction { s ->
        val format = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
        require(format.matches(input) && format.matches(output)) { "请填写完整时间（00:00–23:59）" }
        require(input != output) { "上班与下班时间不能相同" }
        s.plans.forEach { p ->
            val value = if (p.kind == Kind.IN) input else output
            if (p.time != value) {
                cancelSuperseded(s, p.kind, now)
                p.time = value
                if (p.enabled && !s.settings.paused) p.nextDue = future(p, now)
            }
        }
    }

    fun savePlanSettings(input: String, output: String, inputEnabled: Boolean, outputEnabled: Boolean, now: Long) = store.transaction { s ->
        val format = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
        require(format.matches(input) && format.matches(output)) { "请填写完整时间（00:00–23:59）" }
        require(input != output) { "上班与下班时间不能相同" }
        s.plans.forEach { p ->
            val time = if (p.kind == Kind.IN) input else output
            val enabled = if (p.kind == Kind.IN) inputEnabled else outputEnabled
            if (p.time != time || p.enabled != enabled) {
                if (p.time != time) cancelSuperseded(s, p.kind, now)
                p.time = time
                p.enabled = enabled
                p.nextDue = if (enabled && !s.settings.paused) future(p, now) else null
                if (!enabled) cancel(s, p.kind, now)
            }
        }
    }

    fun setEnabled(kind: Kind, enabled: Boolean, now: Long, deviceReady: Boolean) = store.transaction { s ->
        if (enabled) requireReady(s, now, deviceReady)
        val p = s.plans.first { it.kind == kind }
        if (p.enabled != enabled) {
            p.enabled = enabled
            p.nextDue = if (enabled && !s.settings.paused) future(p, now) else null
            if (!enabled) cancel(s, kind, now)
        }
    }

    fun pause(paused: Boolean, now: Long, deviceReady: Boolean) = store.transaction { s ->
        if (!paused) requireReady(s, now, deviceReady)
        if (s.settings.paused != paused) {
            s.settings.paused = paused
            s.plans.forEach { p -> p.nextDue = if (p.enabled && !paused) future(p, now) else null }
            if (paused) Kind.entries.forEach { cancel(s, it, now) }
        }
    }

    fun setAutomaticEnabled(enabled: Boolean, now: Long, deviceReady: Boolean) = store.transaction { s ->
        if (enabled) requireReady(s, now, deviceReady)
        if (enabled && s.automaticEnabled) return@transaction
        if (enabled && s.plans.none { it.enabled }) s.plans.forEach { it.enabled = true }
        s.settings.paused = !enabled
        s.plans.forEach { p -> p.nextDue = if (enabled && p.enabled) future(p, now) else null }
        if (!enabled) Kind.entries.forEach { cancel(s, it, now) }
    }

    fun configureMail(recipient: String, credentialRef: String) = store.transaction { s ->
        val email = recipient.trim()
        require(email.length <= 254 && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email)) { "请输入有效的收件邮箱" }
        require(credentialRef.length <= 128) { "认证引用过长" }
        if (s.settings.recipient != email || s.settings.credentialRef != credentialRef) {
            s.settings.recipient = email
            s.settings.credentialRef = credentialRef
            s.settings.mailVerified = false
        }
    }

    fun acceptMailTest(recipient: String, credentialRef: String) = store.transaction { s ->
        require(credentialRef.isNotBlank() && s.settings.credentialRef == credentialRef && s.settings.recipient == recipient)
        s.settings.mailVerified = true
    }

    fun clearMailHistory(now: Long) = store.transaction { s ->
        s.settings.mailHistoryClearedAt = maxOf(s.settings.mailHistoryClearedAt, now)
    }

    fun clearCheckinHistory(): List<String> = store.transaction { s ->
        val clearedIds = s.runs.filter { it.status.terminal }.map { it.id }
        val ids = clearedIds.toSet()
        s.runs.removeAll { it.id in ids }
        s.executionEvents.removeAll { it.runId in ids }
        s.alerts.removeAll { it.runId in ids }
        clearedIds
    }

    private fun requireReady(s: State, now: Long, deviceReady: Boolean) {
        require(deviceReady) { "自动签到待设备验证，请先完成运行检查" }
        require(calendar.classify(dateAt(now)) != Day.MISSING) { "当前年度工作日历不可用" }
    }

    private data class AutomaticWindow(val date: LocalDate, val start: Long, val end: Long)

    private fun automaticWindow(plan: Plan, date: LocalDate): AutomaticWindow {
        val boundary = at(date, plan.time)
        return if (plan.kind == Kind.IN) AutomaticWindow(date, boundary - AUTOMATIC_WINDOW_MS, boundary)
        else AutomaticWindow(date, boundary, boundary + AUTOMATIC_WINDOW_MS)
    }

    private fun randomizedDue(plan: Plan, date: LocalDate): Long {
        val seed = date.toEpochDay() * 1_000_003L + plan.kind.ordinal * 97_409L + plan.time.hashCode()
        val offsetSeconds = scheduleOffsetSeconds?.invoke(plan.kind, date, plan.time)
            ?: java.util.Random(seed xor 0x5DEECE66DL).nextInt(AUTOMATIC_WINDOW_SECONDS)
        require(offsetSeconds in 0 until AUTOMATIC_WINDOW_SECONDS) { "随机签到偏移必须在 10 分钟内" }
        return automaticWindow(plan, date).start + offsetSeconds * 1000L
    }

    private fun scheduleDate(plan: Plan, scheduled: Long): LocalDate {
        val observed = dateAt(scheduled)
        return listOf(observed.minusDays(1), observed, observed.plusDays(1)).firstOrNull { date ->
            val window = automaticWindow(plan, date)
            scheduled >= window.start && scheduled < window.end
        } ?: error("随机签到时间不在计划窗口内")
    }

    private fun future(plan: Plan, now: Long): Long {
        var date = dateAt(now).minusDays(1)
        repeat(4) {
            val due = randomizedDue(plan, date)
            if (due > now) return due
            date = date.plusDays(1)
        }
        error("无法生成未来签到时间")
    }

    private fun cancel(s: State, kind: Kind, now: Long) {
        s.runs.filter { it.kind == kind && !it.status.terminal }.forEach { run ->
            run.cancelRequested = true
            // A submitted action cannot be undone; preserve the subsequent verification.
            if (run.status != RunStatus.RUNNING || run.attempts.lastOrNull()?.submitted != true) {
                run.attempts.lastOrNull()?.let { if (it.finished == null) { it.finished = now; it.reason = "用户暂停" } }
                finish(s, run, RunStatus.CANCELLED, "用户暂停，不再发起新的签到操作", now)
            }
        }
    }

    private fun cancelSuperseded(s: State, kind: Kind, now: Long) {
        s.runs.filter { !it.manual && it.kind == kind && !it.status.terminal &&
            it.attempts.none { attempt -> attempt.submitted } }.forEach { run ->
            run.cancelRequested = true
            run.attempts.lastOrNull()?.let { attempt ->
                if (attempt.finished == null) {
                    attempt.finished = now
                    attempt.reason = "计划时间已修改"
                }
            }
            finish(s, run, RunStatus.CANCELLED, "计划时间已修改", now)
        }
    }

    /** System event reconciliation; disabled plans have no cursor and therefore never backfill. */
    fun tick(now: Long, deviceReady: Boolean, deviceReason: String = "设备运行条件暂时不可用") = store.transaction { s ->
        val floor = dateAt(now).minusDays(policy.retentionDays).atStartOfDay(BEIJING).toInstant().toEpochMilli()
        s.settings.retentionFloor = maxOf(s.settings.retentionFloor, floor)
        s.runs.removeAll { it.status.terminal && it.scheduled < s.settings.retentionFloor }
        s.executionEvents.removeAll { event ->
            event.created < s.settings.retentionFloor || s.runs.none { it.id == event.runId }
        }
        s.alerts.removeAll { it.created < s.settings.retentionFloor && it.status != MailStatus.SENDING }
        val nextYear = dateAt(now).year + 1
        if (dateAt(now) >= LocalDate.of(nextYear, 1, 1).minusDays(30) && calendar.year(nextYear) == null &&
            s.settings.mailVerified && s.remindedYears.add(nextYear)) {
            s.alerts.add(Alert("calendar:$nextYear", null, now, Long.MAX_VALUE,
                "[牛马签到][需处理] $nextYear 年工作日历缺失", "请更新年度工作日历，缺失年份不会执行签到。截图不可用。"))
        }
        if (!s.settings.paused) s.plans.filter { it.enabled }.forEach { p ->
            var due = p.nextDue ?: future(p, now)
            if (due < s.settings.retentionFloor) due = future(p, s.settings.retentionFloor)
            while (due <= now) {
                val scheduleDate = scheduleDate(p, due)
                if (!hasBlockingRun(s, p.kind, scheduleDate, due)) {
                    val id = automaticRunId(s, p.kind, scheduleDate, due)
                    val r = Run(id, p.kind, due, automaticWindow(p, scheduleDate).end,
                        scheduleDate = scheduleDate)
                    s.runs.add(r)
                    when {
                        calendar.classify(r.date) == Day.MISSING -> finish(s, r, RunStatus.FAILED, "当前年度工作日历不可用", now)
                        calendar.classify(r.date) == Day.REST -> finish(s, r, RunStatus.SKIPPED, "休息日，跳过签到", now)
                        now >= r.deadline -> finish(s, r, RunStatus.FAILED, "已超过执行窗口，未执行", now)
                        !deviceReady -> deferForReadiness(s, r, now, deviceReason)
                    }
                }
                due = randomizedDue(p, scheduleDate.plusDays(1))
            }
            p.nextDue = due
        }
        s.runs.filter { !it.status.terminal }.forEach { r ->
            if (r.status == RunStatus.RUNNING && now >= r.attempts.last().started + policy.timeoutMs) {
                endAttempt(s, r, Evidence(Result.UNKNOWN, summary = "单次超时，结果未确认"), now)
            } else if (r.status != RunStatus.RUNNING && now >= r.deadline) {
                val reason = r.reason.takeIf { it.isNotBlank() }?.let { "执行窗口已结束：$it" } ?: "执行窗口已结束"
                finish(s, r, RunStatus.FAILED, reason, now)
            } else if (r.status != RunStatus.RUNNING && !deviceReady && now >= (r.nextAttempt ?: r.scheduled)) {
                deferForReadiness(s, r, now, deviceReason)
            }
        }
        s.alerts.filter { it.status == MailStatus.SENDING }.forEach { a ->
            if (now >= (a.sendingSince ?: now) + policy.timeoutMs) {
                retryMail(a, now, "发送超时")
            }
        }
        s.alerts.filter { it.status == MailStatus.PENDING && now >= it.expires }.forEach { a ->
            a.status = MailStatus.EXPIRED; a.reason = "24 小时通知窗口已结束"
        }
    }

    /** A process restart never resets budgets or assumes a previously submitted attempt did not happen. */
    fun recover(now: Long) = store.transaction { s ->
        s.runs.filter { it.status == RunStatus.MISSED }.forEach { run ->
            run.status = RunStatus.FAILED
            run.nextAttempt = null
            s.alerts.firstOrNull { it.runId == run.id }?.apply {
                subject = "[牛马签到][需处理] ${run.date} ${run.kind.label}签到失败"
                body = body.replace("结果：未执行", "结果：失败")
            }
        }
        s.runs.filter { it.status == RunStatus.RUNNING }.forEach {
            endAttempt(s, it, Evidence(Result.UNKNOWN, summary = "执行中断，恢复后必须先核验"), now)
        }
        s.alerts.filter { it.status == MailStatus.SENDING }.forEach {
            retryMail(it, now, "发送过程被系统中断")
        }
    }

    data class Ticket(val runId: String, val attempt: Int, val date: LocalDate, val kind: Kind,
                      val scheduled: Long, val deadline: Long,
                      val previousSubmissionMinutes: Set<String> = emptySet())
    private fun verificationTimes(run: Run) = listOfNotNull(run.finished?.let { it + 30_000L },
        run.finished?.let { it + 90_000L })

    private fun verificationCandidates(s: State): List<Run> = s.runs.filter { run ->
        run.status == RunStatus.UNKNOWN && run.attempts.any { it.submitted } &&
            s.alerts.any { alert -> alert.runId == run.id && alert.status == MailStatus.PENDING && alert.attempts == 0 &&
                run.finished?.let { alert.nextAttempt >= it + UNKNOWN_VERIFICATION_GRACE_MS } == true } &&
            s.executionEvents.count { it.runId == run.id && it.stage == AutomaticExecutionStage.READ_ONLY_VERIFICATION } < 2
    }

    fun pendingVerification(now: Long): Ticket? = store.transaction { s ->
        verificationCandidates(s).sortedBy { it.finished }.firstNotNullOfOrNull { run ->
            val checks = s.executionEvents.count { it.runId == run.id && it.stage == AutomaticExecutionStage.READ_ONLY_VERIFICATION }
            if (now < verificationTimes(run)[checks] || dateAt(now) != run.date) null
            else Ticket(run.id, run.attempts.size, run.date, run.kind, run.scheduled, run.deadline,
                run.attempts.filter { it.submitted }.flatMap { listOf(it.started, it.finished ?: it.started) }
                    .map { stamp(it).substring(11, 16) }.toSet())
        }
    }

    fun completeVerification(ticket: Ticket, evidence: Evidence?, now: Long) = store.transaction { s ->
        val run = verificationCandidates(s).firstOrNull { it.id == ticket.runId } ?: return@transaction
        val count = s.executionEvents.count { it.runId == run.id && it.stage == AutomaticExecutionStage.READ_ONLY_VERIFICATION }
        if (count >= 2 || now < verificationTimes(run)[count]) return@transaction
        val verified = evidence?.result == Result.COMPLETE && evidence.date == run.date && evidence.kind == run.kind
        val event = ExecutionEvent("${run.id}:${s.executionEvents.count { it.runId == run.id }}", run.id, now,
            AutomaticExecutionStage.READ_ONLY_VERIFICATION,
            if (verified) ExecutionEventResult.PASSED else ExecutionEventResult.FAILED,
            evidence?.summary?.take(1000) ?: "只读复核未取得对应成功回执")
        s.executionEvents.add(event)
        if (verified) {
            run.status = RunStatus.SUCCESS
            run.reason = evidence!!.summary.take(1000)
            s.alerts.firstOrNull { it.runId == run.id && it.status == MailStatus.PENDING }?.apply {
                subject = "[牛马签到][成功] ${run.date} ${run.kind.label}签到成功"
                body = "计划时间：${stamp(run.scheduled)}（北京时间）\n结果：成功\n原因：${run.reason}\n本次签到已完成。"
                nextAttempt = now
            }
        }
    }
    fun requestLatest(kind: Kind, now: Long, deviceReady: Boolean): String = store.transaction { s ->
        require(deviceReady) { "设备操作条件未就绪" }
        require(s.runs.none { !it.status.terminal }) { "已有签到任务进行中，请等待结果" }
        require(now >= s.settings.retentionFloor) { "系统时间已回拨，请校准时间" }
        val canonical = key(dateAt(now), kind)
        val id = if (s.runs.none { it.id == canonical }) canonical else "$canonical:manual:$now"
        require(s.runs.none { it.id == id }) { "请勿重复点击" }
        s.runs.add(Run(id, kind, now, now + policy.windowMs, manual = true))
        id
    }

    fun requestImmediate(kind: Kind, now: Long, deviceReady: Boolean): String = store.transaction { s ->
        requireReady(s, now, deviceReady)
        require(calendar.classify(dateAt(now)) != Day.REST) { "今天是休息日，不执行签到" }
        val id = key(dateAt(now), kind)
        val existing = s.runs.firstOrNull { it.id == id }
        require(existing == null) { "今日${kind.label}已有执行记录，请在记录页查看；不能重复发起" }
        require(s.runs.none { !it.status.terminal }) { "已有签到任务进行中，请等待结果" }
        require(now >= s.settings.retentionFloor) { "系统时间已回拨，请先校准时间" }
        s.runs.add(Run(id, kind, now, now + policy.windowMs, manual = true))
        id
    }

    fun begin(now: Long, deviceReady: Boolean, deviceReason: String = "设备运行条件暂时不可用",
              runId: String? = null): Ticket? = store.transaction { s ->
        if (s.runs.any { it.status == RunStatus.RUNNING }) return@transaction null
        val r = s.runs.filter { (runId == null || it.id == runId) && (it.manual || !s.settings.paused) &&
            (it.status == RunStatus.PENDING || it.status == RunStatus.RETRY) }
            .filter { now >= (it.nextAttempt ?: it.scheduled) }.minByOrNull { it.scheduled } ?: return@transaction null
        if (now >= r.deadline || r.attempts.size >= policy.maxAttempts) {
            finish(s, r, RunStatus.FAILED, "执行预算已用尽", now)
            return@transaction null
        }
        if (!deviceReady || (!r.manual && (calendar.classify(r.date) == Day.MISSING))) {
            if (r.manual) finish(s, r, RunStatus.FAILED, deviceReason, now)
            else deferForReadiness(s, r, now, deviceReason)
            return@transaction null
        }
        val previousSubmissionMinutes = r.attempts.filter { it.submitted }.flatMap { previous ->
            listOf(previous.started, previous.finished ?: previous.started)
        }.map { stamp(it).substring(11, 16) }.toSet()
        r.status = RunStatus.RUNNING; r.nextAttempt = null
        r.attempts.add(Attempt(r.attempts.size + 1, now))
        Ticket(r.id, r.attempts.size, r.date, r.kind, r.scheduled, r.deadline, previousSubmissionMinutes)
    }

    fun permitSubmit(ticket: Ticket, evidence: Evidence, now: Long, deviceReady: Boolean): Boolean = store.transaction { s ->
        val r = active(s, ticket) ?: return@transaction false
        val a = r.attempts.last()
        if ((!r.manual && s.settings.paused) || r.cancelRequested || !deviceReady ||
            (!r.manual && !s.plans.first { it.kind == r.kind }.enabled) || now >= r.deadline || now >= a.started + policy.timeoutMs ||
            a.submitted || (evidence.result != Result.NOT_DONE &&
                !(r.manual && evidence.result == Result.UPDATE_READY)) || !evidence.allowsSubmit ||
            evidence.date != r.date || evidence.kind != r.kind) return@transaction false
        a.submitted = true; a.reason = "提交许可已持久化，等待结果核验"
        true
    }

    fun complete(ticket: Ticket, evidence: Evidence, now: Long) = store.transaction { s ->
        val r = active(s, ticket) ?: return@transaction
        endAttempt(s, r, evidence, now)
    }

    /** A user initiated single pass stops after an uncertain submit; it must never submit again automatically. */
    fun completeManualUnknown(ticket: Ticket, reason: String, now: Long) = store.transaction { s ->
        val r = active(s, ticket) ?: return@transaction
        require(r.manual) { "仅手动签到可结束为待核查" }
        val detail = reason.take(1000).ifBlank { "结果未确认，请检查钉钉打卡记录" }
        r.attempts.last().apply { finished = now; this.reason = detail }
        finish(s, r, RunStatus.UNKNOWN, detail, now)
    }

    /** An uncertain submitted punch must never be submitted again, including by an alarm retry. */
    fun completeSubmittedUnknown(ticket: Ticket, reason: String, now: Long) = store.transaction { s ->
        val r = active(s, ticket) ?: return@transaction
        val detail = reason.take(1000).ifBlank { "结果未确认，请检查钉钉打卡记录" }
        r.attempts.last().apply { finished = now; this.reason = detail }
        finish(s, r, RunStatus.UNKNOWN, detail, now)
    }

    fun currentAutomaticRunId(now: Long): String? = store.transaction { s ->
        s.runs.filter { !it.manual && !it.status.terminal && it.scheduled <= now && now < it.deadline }
            .maxByOrNull { it.scheduled }?.id
    }

    fun recordExecutionEvent(runId: String, stage: AutomaticExecutionStage, result: ExecutionEventResult,
                             now: Long, reason: String = "") = store.transaction { s ->
        require(s.runs.any { it.id == runId }) { "执行记录不存在" }
        val sequence = s.executionEvents.count { it.runId == runId }
        s.executionEvents.add(ExecutionEvent("$runId:$sequence", runId, now, stage, result, reason.take(1000)))
    }

    private fun active(s: State, t: Ticket): Run? = s.runs.firstOrNull {
        it.id == t.runId && it.status == RunStatus.RUNNING && it.attempts.size == t.attempt
    }

    private fun endAttempt(s: State, r: Run, evidence: Evidence, now: Long) {
        val success = evidence.result == Result.COMPLETE && evidence.date == r.date && evidence.kind == r.kind
        val reason = if (success) evidence.summary.take(1000).ifBlank { "已核验当日${r.kind.label}签到完成" } else evidence.summary.take(1000).ifBlank { "结果未确认，请检查钉钉打卡记录" }
        r.attempts.last().apply { finished = now; this.reason = reason }
        if (success) { finish(s, r, RunStatus.SUCCESS, reason, now); return }
        val status = if (evidence.result == Result.UNKNOWN) RunStatus.UNKNOWN else RunStatus.FAILED
        if (r.cancelRequested) {
            finish(s, r, if (r.attempts.last().submitted) status else RunStatus.CANCELLED, reason, now)
        } else if (evidence.result in setOf(Result.FAILURE, Result.BLOCKED) ||
            r.attempts.size >= policy.maxAttempts || now + policy.retryMs >= r.deadline) {
            finish(s, r, status, reason, now)
        } else {
            r.status = RunStatus.RETRY; r.nextAttempt = now + policy.retryMs; r.reason = reason
        }
    }

    private fun finish(s: State, r: Run, status: RunStatus, reason: String, now: Long) {
        r.status = status; r.reason = reason; r.finished = now; r.nextAttempt = null
        if ((status == RunStatus.SUCCESS || status.attention) && s.alerts.none { it.id == "run:${r.id}" }) {
            val success = status == RunStatus.SUCCESS
            val action = when (status) {
                RunStatus.SUCCESS -> "成功"
                RunStatus.UNKNOWN -> "需核查"
                else -> "需处理"
            }
            val guidance = when (status) {
                RunStatus.SUCCESS -> "本次签到已完成。"
                RunStatus.UNKNOWN -> "程序未能核验结果，不代表打卡失败。\n请只读核查钉钉打卡记录，不要重复提交。"
                else -> "截图不可用\n请按企业允许的方式核查和处理。"
            }
            s.alerts.add(Alert("run:${r.id}", r.id, now, now + MAIL_EXPIRY_MS,
                "[牛马签到][$action] ${r.date} ${r.kind.label}签到${status.label}",
                "计划时间：${stamp(r.scheduled)}（北京时间）\n结束时间：${stamp(now)}\n尝试次数：${r.attempts.size}\n结果：${status.label}\n原因：$reason\n$guidance",
                nextAttempt = if (status == RunStatus.UNKNOWN && r.attempts.any { it.submitted }) now + UNKNOWN_VERIFICATION_GRACE_MS else now))
        }
    }

    private fun deferForReadiness(s: State, r: Run, now: Long, reason: String) {
        val detail = reason.take(1000).ifBlank { "设备运行条件暂时不可用" }
        r.attempts.add(Attempt(r.attempts.size + 1, now, now, submitted = false, reason = detail))
        if (r.attempts.size >= policy.maxAttempts || now + policy.retryMs >= r.deadline) {
            finish(s, r, RunStatus.FAILED, "连续 ${r.attempts.size} 次未执行：$detail", now)
        } else {
            r.status = RunStatus.RETRY
            r.reason = detail
            r.nextAttempt = now + policy.retryMs
        }
    }

    fun recordManualFailure(now: Long, reason: String) = store.transaction { s ->
        val id = "manual-preflight:$now"
        if (s.alerts.none { it.id == id }) {
            s.alerts.add(Alert(id, null, now, now + MAIL_EXPIRY_MS,
                "[牛马签到][需处理] 手动签到失败",
                "结束时间：${stamp(now)}（北京时间）\n结果：失败\n原因：${reason.take(1000)}\n请检查钉钉页面和签到辅助服务。"))
        }
    }

    fun recordPreflightFailure(scheduled: Long, kind: Kind, now: Long, reason: String) = store.transaction { s ->
        val plan = s.plans.first { it.kind == kind }
        val id = "preflight:${scheduleDate(plan, scheduled)}:${kind.name}:$scheduled"
        if (s.alerts.none { it.id == id }) {
            s.alerts.add(Alert(id, null, now, now + MAIL_EXPIRY_MS,
                "[牛马签到][需处理] ${kind.label}签到运行条件异常",
                "计划时间：${stamp(scheduled)}（北京时间）\n检查时间：${stamp(now)}\n原因：${reason.take(1000)}\n请在计划时间前恢复运行条件。"))
        }
    }

    /** SMTP acceptance is retried at most three times; it is not inbox-delivery proof. */
    data class Delivery(val alert: Alert, val recipient: String, val credentialRef: String) {
        val id get() = alert.id
        val attempts get() = alert.attempts
    }
    fun claimMail(now: Long, online: Boolean, transportReady: Boolean): Delivery? = store.transaction { s ->
        if (!online || !transportReady || !s.settings.mailVerified || s.settings.credentialRef.isBlank()) return@transaction null
        if (s.alerts.any { it.status == MailStatus.SENDING }) return@transaction null
        val a = s.alerts.firstOrNull {
            it.status == MailStatus.PENDING && it.attempts < MAIL_MAX_ATTEMPTS && now >= it.nextAttempt && now < it.expires
        } ?: return@transaction null
        a.attempts++; a.status = MailStatus.SENDING; a.sendingSince = now
        Delivery(a.copy(), s.settings.recipient, s.settings.credentialRef)
    }

    fun finishMail(id: String, attempt: Int, sent: Boolean, now: Long) = store.transaction { s ->
        val a = s.alerts.firstOrNull { it.id == id && it.attempts == attempt && it.status == MailStatus.SENDING } ?: return@transaction
        if (sent) {
            a.status = MailStatus.ACCEPTED; a.reason = "SMTP 服务商已接受，收件箱送达需另行核验"; a.sendingSince = null
        } else retryMail(a, now, "发送失败")
    }

    fun nextMailWake(now: Long, transportReady: Boolean = true): Long? = store.transaction { s ->
        if (!transportReady) return@transaction null
        s.alerts.filter { it.status == MailStatus.PENDING && it.attempts < MAIL_MAX_ATTEMPTS && now < it.expires }
            .minOfOrNull { maxOf(now + 1000, it.nextAttempt) }
    }

    private fun retryMail(a: Alert, now: Long, reason: String) {
        a.sendingSince = null
        if (a.attempts >= MAIL_MAX_ATTEMPTS) {
            a.status = MailStatus.FAILED; a.reason = "$reason，已用尽 $MAIL_MAX_ATTEMPTS 次发送机会"
        } else if (now + policy.retryMs >= a.expires) {
            a.status = MailStatus.EXPIRED; a.reason = "$reason，24 小时通知窗口已结束"
        } else {
            a.status = MailStatus.PENDING; a.nextAttempt = now + policy.retryMs
            a.reason = "$reason，等待第 ${a.attempts + 1} 次发送"
        }
    }

    fun nextExecution(now: Long): Pair<Long, Kind>? = store.transaction { s ->
        if (s.settings.paused) return@transaction null
        s.plans.filter { it.enabled }.mapNotNull { p ->
            var due = p.nextDue ?: future(p, now)
            repeat(370) {
                val scheduleDate = scheduleDate(p, due)
                if (calendar.classify(scheduleDate) == Day.MISSING) return@mapNotNull null
                if (due > now && calendar.classify(scheduleDate) != Day.REST &&
                    !hasBlockingRun(s, p.kind, scheduleDate, due)) return@mapNotNull due to p.kind
                due = randomizedDue(p, scheduleDate.plusDays(1))
            }
            null
        }.minByOrNull { it.first }
    }

    fun nextWake(now: Long): Long? = store.transaction { s ->
        val candidates = mutableListOf<Long>()
        if (!s.settings.paused) candidates.addAll(s.plans.filter { it.enabled }.mapNotNull { it.nextDue })
        s.runs.filter { !it.status.terminal }.forEach { r ->
            candidates.add(if (r.status == RunStatus.RUNNING) r.attempts.last().started + policy.timeoutMs else r.nextAttempt ?: r.scheduled)
        }
        verificationCandidates(s).filter { dateAt(now) == it.date }.forEach { run ->
            val count = s.executionEvents.count { it.runId == run.id && it.stage == AutomaticExecutionStage.READ_ONLY_VERIFICATION }
            candidates.add(verificationTimes(run)[count])
        }
        candidates.minOrNull()?.coerceAtLeast(now + 1000)
    }

    private fun hasBlockingRun(s: State, kind: Kind, scheduleDate: LocalDate, scheduled: Long): Boolean = s.runs.any { run ->
        !run.manual && run.kind == kind && run.date == scheduleDate &&
            (run.scheduled == scheduled || (!run.status.terminal && run.deadline > scheduled))
    }

    private fun automaticRunId(s: State, kind: Kind, scheduleDate: LocalDate, scheduled: Long): String {
        val canonical = key(scheduleDate, kind)
        return if (s.runs.none { it.id == canonical }) canonical else "$canonical:scheduled:$scheduled"
    }

    companion object {
        const val RANDOM_SCHEDULE_VERSION = 1
        const val MAIL_MAX_ATTEMPTS = 3
        const val MAIL_EXPIRY_MS = 24L * 60 * 60 * 1000
    }
}
