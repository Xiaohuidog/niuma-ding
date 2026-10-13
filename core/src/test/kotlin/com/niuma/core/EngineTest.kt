package com.niuma.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class EngineTest {
    @Test fun unknownSubmissionWaitsForReadOnlyChecksBeforeMailAndCanBecomeSuccess() {
        val ticket = start()
        assertTrue(engine.permitSubmit(ticket, allowed(ticket), due, true))
        engine.complete(ticket, Evidence(Result.UNKNOWN, summary = "回执未确认"), due + 590_000)
        assertEquals(RunStatus.UNKNOWN, run.status)
        assertTrue(run.reviewInProgress(state.alerts.single(), due + 590_001))
        assertFalse(run.reviewInProgress(state.alerts.single(), due + 890_000))
        assertNull(engine.claimMail(due + 590_001, true, true))
        assertEquals(due + 620_000, engine.nextWake(due + 590_001))
        val verification = engine.pendingVerification(due + 620_000)
        assertEquals(ticket.runId, verification?.runId)
        assertFalse(engine.permitSubmit(verification!!, allowed(verification), due + 620_000, true))
        engine.completeVerification(verification, complete(verification), due + 620_001)
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(MailStatus.PENDING, state.alerts.single().status)
        assertTrue(state.alerts.single().subject.contains("[成功]"))
        assertNull(engine.pendingVerification(due + 680_000))
    }

    @Test fun inconclusiveReadOnlyChecksSendExplicitUnverifiedNoticeAfterGracePeriod() {
        val ticket = start()
        assertTrue(engine.permitSubmit(ticket, allowed(ticket), due, true))
        engine.complete(ticket, Evidence(Result.UNKNOWN, summary = "回执未确认"), due + 590_000)
        val first = engine.pendingVerification(due + 620_000)!!
        engine.completeVerification(first, null, due + 620_001)
        assertNull(engine.pendingVerification(due + 620_001))
        val second = engine.pendingVerification(due + 680_000)!!
        engine.completeVerification(second, null, due + 680_001)
        assertNull(engine.pendingVerification(due + 800_000))
        assertNull(engine.claimMail(due + 889_999, true, true))
        assertTrue(state.alerts.single().body.contains("程序未能核验结果"))
        val delivery = requireNotNull(engine.claimMail(due + 890_000, true, true))
        assertTrue(delivery.alert.subject.contains("[需核查]"))
        assertEquals(ticket.runId, delivery.alert.runId)
        engine.finishMail(delivery.id, delivery.attempts, true, due + 890_001)
        assertEquals(MailStatus.ACCEPTED, state.alerts.single().status)
        assertEquals(1, state.alerts.single().attempts)
    }
    @Test fun unknownAlertRequiresScreenshotAttachment() {
        val unknown = Alert(
            "run:2026-09-20:OUT", "2026-09-20:OUT", 1L, 2L,
            "[牛马签到][需核查] 2026-09-20 下班签到待核查", "结果未确认"
        )
        val success = unknown.copy(subject = "[牛马签到][成功] 2026-09-20 下班签到成功")

        assertTrue(unknown.requiresScreenshotAttachment())
        assertFalse(success.requiresScreenshotAttachment())
        assertEquals("2026-09-20_OUT.png", screenshotEvidenceFileName("2026-09-20:OUT"))
    }

    @Test fun validatedWifiMayExistBeforeItBecomesTheDefaultNetwork() {
        val defaultCellular = NetworkState(wifi = false, internet = true, validated = true)
        val connectedWifi = NetworkState(wifi = true, internet = true, validated = true)
        assertTrue(NetworkReadiness.hasValidatedWifi(listOf(defaultCellular, connectedWifi)))
        assertFalse(NetworkReadiness.hasValidatedWifi(listOf(defaultCellular)))
    }

    private class MemoryStore(val state: State = State()) : Store {
        @Synchronized override fun <T> transaction(block: (State) -> T): T = block(state)
    }
    private val store = MemoryStore()
    private fun fixedEngine() = Engine(store, scheduleOffsetSeconds = { _, _, _ -> 0 })
    private var engine = fixedEngine()
    private val day = LocalDate.of(2026, 9, 7)
    private val due = at(day, "07:20")
    private val state get() = store.state
    private val run get() = state.runs.first()
    private fun ready(now: Long = due - 60_000) {
        engine.configureMail("me@example.com", "test-only-ref")
        engine.acceptMailTest("me@example.com", "test-only-ref")
        engine.setEnabled(Kind.IN, true, now, true)
    }
    private fun start(): Engine.Ticket {
        ready(); engine.tick(due, true)
        return requireNotNull(engine.begin(due, true))
    }
    private fun complete(t: Engine.Ticket) = Evidence(Result.COMPLETE, t.date, t.kind)
    private fun allowed(t: Engine.Ticket) = Evidence(Result.NOT_DONE, t.date, t.kind, allowsSubmit = true)

    @Test fun automaticTimesVaryByWorkDateInsideConfiguredWindows() {
        val inputTimes = mutableSetOf<Long>()
        val outputTimes = mutableSetOf<Long>()
        repeat(5) { offset ->
            val scheduleDate = day.plusDays(offset.toLong())
            val isolated = MemoryStore()
            Engine(isolated).savePlanSettings("07:30", "18:00", true, true, at(scheduleDate, "00:00"))
            val inputDue = isolated.state.plans.first { it.kind == Kind.IN }.nextDue!!
            val outputDue = isolated.state.plans.first { it.kind == Kind.OUT }.nextDue!!
            assertTrue(inputDue >= at(scheduleDate, "07:20") && inputDue < at(scheduleDate, "07:30"))
            assertTrue(outputDue >= at(scheduleDate, "18:00") && outputDue < at(scheduleDate, "18:10"))
            inputTimes.add(inputDue - at(scheduleDate, "07:20"))
            outputTimes.add(outputDue - at(scheduleDate, "18:00"))
        }
        assertTrue(inputTimes.size > 1)
        assertTrue(outputTimes.size > 1)
    }

    @Test fun automaticInputRetriesCannotCrossConfiguredTime() {
        val isolated = MemoryStore()
        val isolatedEngine = Engine(isolated)
        isolatedEngine.savePlanSettings("07:30", "18:00", true, false, at(day, "00:00"))
        val randomizedDue = isolated.state.plans.first { it.kind == Kind.IN }.nextDue!!

        isolatedEngine.tick(randomizedDue, true)

        assertEquals(at(day, "07:30"), isolated.state.runs.single().deadline)
    }

    @Test fun legacyAutomaticInputKeepsCanonicalWorkDateAcrossMidnight() {
        val legacy = Run(key(day, Kind.IN), Kind.IN, at(day, "23:55"),
            at(day.plusDays(1), "00:05"))

        assertEquals(day, legacy.date)
    }

    @Test fun scheduleUpgradeRandomizesLegacyDueOnlyOnce() {
        val isolated = MemoryStore()
        isolated.state.plans.first { it.kind == Kind.IN }.apply {
            enabled = true
            nextDue = at(day, "07:30")
        }
        val isolatedEngine = Engine(isolated)

        isolatedEngine.upgradeRandomSchedule(at(day, "00:00"))
        val migratedDue = isolated.state.plans.first { it.kind == Kind.IN }.nextDue
        isolatedEngine.upgradeRandomSchedule(at(day, "00:01"))

        assertEquals(1, isolated.state.settings.scheduleVersion)
        assertTrue(migratedDue!! >= at(day, "07:20") && migratedDue < at(day, "07:30"))
        assertEquals(migratedDue, isolated.state.plans.first { it.kind == Kind.IN }.nextDue)
    }

    @Test fun saveAllPlanSettingsWithoutMailOrDeviceAndRejectInvalidAtomically() {
        engine.savePlanSettings("08:05", "19:10", true, false, due - 1)
        assertTrue(state.plans.first { it.kind == Kind.IN }.enabled)
        assertFalse(state.plans.first { it.kind == Kind.OUT }.enabled)
        assertEquals("08:05", state.plans.first { it.kind == Kind.IN }.time)
        try { engine.savePlanSettings("25:00", "19:10", false, true, due); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(state.plans.first { it.kind == Kind.IN }.enabled)
        assertEquals("08:05", state.plans.first { it.kind == Kind.IN }.time)
    }
    @Test fun automaticRunsWithoutMailAndOffCancelsFutureSubmission() {
        engine.savePlanSettings("07:30", "18:30", true, false, due - 1)
        engine.setAutomaticEnabled(true, due - 1, true)
        engine.tick(due, true)
        val t = requireNotNull(engine.begin(due, true))
        assertTrue(engine.permitSubmit(t, allowed(t), due, true))
        engine.complete(t, complete(t), due + 1)
        assertEquals(RunStatus.SUCCESS, run.status)
        assertFalse(state.settings.mailVerified)
        engine.setAutomaticEnabled(false, due + 2, true)
        assertTrue(state.plans.all { it.nextDue == null })
    }

    @Test fun firstInstallDisabledAndUnconfiguredCannotEnable() {
        assertTrue(state.plans.none { it.enabled }); assertFalse(state.settings.mailVerified)
        try { engine.setEnabled(Kind.IN, true, due, false); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun officialCalendarAndMissingYear() {
        val c = engine.calendar
        assertEquals(Day.WORK, c.classify(day))
        assertEquals(Day.REST, c.classify(LocalDate.of(2026, 10, 1)))
        assertEquals(Day.MAKEUP, c.classify(LocalDate.of(2026, 9, 20)))
        assertEquals(Day.REST, c.classify(LocalDate.of(2026, 9, 6)))
        assertEquals(Day.MISSING, c.classify(LocalDate.of(2027, 1, 4)))
        assertEquals(6, c.year(2026)!!.exceptions.values.count { it })
        assertEquals(33, c.year(2026)!!.exceptions.values.count { !it })
    }
    @Test fun mainlandCalendarCoversUpcomingMidAutumnAndNationalDayRules() {
        val c = engine.calendar
        assertEquals(Day.WORK, c.classify(LocalDate.of(2026, 9, 18)))
        assertEquals(Day.REST, c.classify(LocalDate.of(2026, 9, 19)))
        assertEquals(Day.MAKEUP, c.classify(LocalDate.of(2026, 9, 20)))
        listOf(25, 26, 27).forEach { dayOfMonth ->
            assertEquals(Day.REST, c.classify(LocalDate.of(2026, 9, dayOfMonth)))
        }
        (1..7).forEach { dayOfMonth ->
            assertEquals(Day.REST, c.classify(LocalDate.of(2026, 10, dayOfMonth)))
        }
        assertEquals(Day.MAKEUP, c.classify(LocalDate.of(2026, 10, 10)))
    }
    @Test fun holidaySkipsAndMakeupRuns() {
        val holiday = at(LocalDate.of(2026, 10, 1), "07:20")
        ready(holiday - 1); engine.tick(holiday, true)
        assertEquals(RunStatus.SKIPPED, run.status); assertTrue(state.alerts.isEmpty())
        val makeup = at(LocalDate.of(2026, 10, 10), "07:20")
        engine.tick(makeup, true)
        assertNotNull(engine.begin(makeup, true))
    }
    @Test fun sameDateAndKindRequiredForSuccess() {
        val t = start()
        engine.complete(t, Evidence(Result.COMPLETE, day.minusDays(1), Kind.IN), due + 1)
        assertEquals(RunStatus.RETRY, run.status)
        val next = engine.begin(due + 120_001, true)!!
        engine.complete(next, Evidence(Result.COMPLETE, day, Kind.OUT), due + 120_002)
        assertEquals(RunStatus.RETRY, run.status)
    }
    @Test fun firstSuccessStopsAndCreatesOneSuccessMail() {
        val t = start(); engine.complete(t, complete(t), due + 1)
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(1, state.alerts.size)
        assertEquals(run.id, state.alerts.single().runId)
        assertTrue(state.alerts.single().subject.contains("成功"))
        assertTrue(state.alerts.single().body.contains("结果：成功"))
        assertNull(engine.begin(due + 120_001, true)); assertEquals(1, run.attempts.size)
    }
    @Test fun secondSuccessAfterUnknownPreventsThird() {
        val t = start(); engine.complete(t, Evidence(Result.UNKNOWN), due + 1)
        assertNull(engine.begin(due + 120_000, true))
        val t2 = engine.begin(due + 120_001, true)!!
        engine.complete(t2, complete(t2), due + 120_002)
        assertNull(engine.begin(due + 240_002, true))
        assertEquals(1, state.alerts.size)
        assertTrue(state.alerts.single().subject.contains("成功"))
    }
    @Test fun clearingMailHistoryKeepsStoredAlertsAndMarksOnlyOlderOnesHidden() {
        val t = start(); engine.complete(t, complete(t), due + 1)
        val storedAlert = state.alerts.single()

        engine.clearMailHistory(due + 2)

        assertEquals(1, state.alerts.size)
        assertEquals(storedAlert.id, state.alerts.single().id)
        assertEquals(due + 2, state.settings.mailHistoryClearedAt)
        assertTrue(storedAlert.created <= state.settings.mailHistoryClearedAt)
        state.alerts.add(Alert("new", null, due + 3, due + 60_000, "new", "new"))
        assertEquals(listOf("new"), state.alerts.filter { it.created > state.settings.mailHistoryClearedAt }.map { it.id })
    }
    @Test fun threeUnknownResultsCreateOneReviewAlertAndNeverFourth() {
        var t = start()
        repeat(3) { i ->
            engine.complete(t, Evidence(Result.UNKNOWN, summary = "结果未确认"), due + i * 120_001L + 1)
            if (i < 2) t = engine.begin(due + (i + 1) * 120_001L, true)!!
        }
        engine.tick(due + 500_000, true)
        assertEquals(3, run.attempts.size); assertEquals(RunStatus.UNKNOWN, run.status)
        assertEquals(1, state.alerts.size); assertNull(engine.begin(due + 500_000, true))
        assertTrue(state.alerts.single().body.contains("尝试次数：3"))
        assertTrue(state.alerts.single().subject.contains("需核查"))
    }
    @Test fun submitOnceAndUnknownPageNeverPermitted() {
        val t = start()
        assertFalse(engine.permitSubmit(t, Evidence(Result.UNKNOWN), due, true))
        assertFalse(engine.permitSubmit(t, allowed(t).copy(kind = Kind.OUT), due, true))
        assertTrue(engine.permitSubmit(t, allowed(t), due, true))
        assertTrue(run.attempts.single().submitted)
        assertFalse(engine.permitSubmit(t, allowed(t), due, true))
    }
    @Test fun completedCardUpdateRequiresAnExplicitManualRun() {
        val update = Evidence(Result.UPDATE_READY, day, Kind.IN, "已打卡，准备更新", allowsSubmit = true)
        val scheduled = start()
        assertFalse(engine.permitSubmit(scheduled, update, due, true))
        engine.complete(scheduled, Evidence(Result.BLOCKED, summary = "未更新"), due + 1)
        engine.requestLatest(Kind.IN, due + 2, true)
        val manual = requireNotNull(engine.begin(due + 2, true))
        assertTrue(engine.permitSubmit(manual, update, due + 2, true))
        assertFalse(engine.permitSubmit(manual, update, due + 2, true))
    }
    @Test fun processRecoveryRetainsBudgetAndVerifiesFirst() {
        val t = start(); assertTrue(engine.permitSubmit(t, allowed(t), due, true))
        engine = fixedEngine(); engine.recover(due + 1000)
        assertEquals(1, run.attempts.size); assertEquals(RunStatus.RETRY, run.status)
        val t2 = engine.begin(due + 121_000, true)!!
        engine.complete(t2, complete(t2), due + 121_001)
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(1, run.attempts.count { it.submitted })
    }
    @Test fun recoveryPreservesUnknownAndConvertsLegacyMissedToFailure() {
        state.runs.add(Run("legacy-unknown", Kind.IN, due, due + engine.policy.windowMs,
            status = RunStatus.UNKNOWN, finished = due + 1, reason = "结果未确认"))
        state.runs.add(Run("legacy-missed", Kind.OUT, due, due + engine.policy.windowMs,
            status = RunStatus.MISSED, finished = due + 2, reason = "未执行"))
        state.alerts.add(Alert("run:legacy-unknown", "legacy-unknown", due + 1, due + 86_400_000,
            "上班签到结果未知", "结果：结果未知"))
        state.alerts.add(Alert("run:legacy-missed", "legacy-missed", due + 2, due + 86_400_000,
            "下班签到未执行", "结果：未执行"))

        engine.recover(due + 3)

        assertEquals(RunStatus.UNKNOWN, state.runs.first { it.id == "legacy-unknown" }.status)
        assertEquals(RunStatus.FAILED, state.runs.first { it.id == "legacy-missed" }.status)
        assertEquals("上班签到结果未知", state.alerts.first { it.id == "run:legacy-unknown" }.subject)
        assertTrue(state.alerts.first { it.id == "run:legacy-missed" }.subject.endsWith("签到失败"))
        assertTrue(state.alerts.first { it.id == "run:legacy-missed" }.body.contains("结果：失败"))
    }
    @Test fun unknownAtEndOfBudgetBecomesReviewState() {
        val t = start(); engine.complete(t, Evidence(Result.UNKNOWN), due + 590_000)
        assertEquals(RunStatus.UNKNOWN, run.status); assertEquals(1, state.alerts.size)
        assertTrue(state.alerts.single().body.contains("结果：待核查"))
    }
    @Test fun timeoutConsumesAttemptAndStaleCallbackIgnored() {
        val t = start(); engine.tick(due + 90_000, true)
        assertEquals(RunStatus.RETRY, run.status)
        engine.complete(t, complete(t), due + 90_001)
        assertEquals(RunStatus.RETRY, run.status)
    }
    @Test fun expiredWindowWithoutAnAttemptIsFailure() {
        ready(); engine.tick(due + 600_000, true)
        assertEquals(RunStatus.FAILED, run.status); assertTrue(run.attempts.isEmpty())
        assertTrue(state.alerts.single().body.contains("尝试次数：0"))
    }
    @Test fun temporaryDeviceFailureConsumesOneAttemptAndKeepsWindowOpen() {
        ready()
        engine.tick(due, false)
        assertEquals(RunStatus.RETRY, run.status)
        assertEquals(1, run.attempts.size)
        assertFalse(run.attempts.single().submitted)
        assertTrue(state.alerts.isEmpty())

        engine.tick(due + engine.policy.retryMs, true)
        val ticket = requireNotNull(engine.begin(due + engine.policy.retryMs, true))
        assertEquals(2, ticket.attempt)
    }
    @Test fun threeNotExecutedReadinessChecksBecomeFailure() {
        ready()
        repeat(3) { index ->
            engine.tick(due + index * engine.policy.retryMs, false, "Wi-Fi 不可用")
            if (index < 2) assertEquals(RunStatus.RETRY, run.status)
        }

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(3, run.attempts.size)
        assertTrue(run.attempts.all { !it.submitted && it.finished != null })
        assertEquals(1, state.alerts.size)
        assertTrue(state.alerts.single().body.contains("尝试次数：3"))
    }
    @Test fun preparationEventsKeepEachStageAndFailureReasonWithoutConsumingAttempts() {
        ready()
        engine.tick(due, true)
        val runId = requireNotNull(engine.currentAutomaticRunId(due))

        engine.recordExecutionEvent(runId, AutomaticExecutionStage.WAKE_LOCK_HELD,
            ExecutionEventResult.PASSED, due, "已持有唤醒锁")
        engine.recordExecutionEvent(runId, AutomaticExecutionStage.UNLOCK_CONFIRMED,
            ExecutionEventResult.FAILED, due + 15_000, "普通锁屏未能解除")

        assertTrue(run.attempts.isEmpty())
        assertEquals(2, state.executionEvents.size)
        assertEquals(AutomaticExecutionStage.WAKE_LOCK_HELD, state.executionEvents[0].stage)
        assertEquals(ExecutionEventResult.FAILED, state.executionEvents[1].result)
        assertEquals("普通锁屏未能解除", state.executionEvents[1].reason)
    }
    @Test fun eventsAndTimeEditsDoNotDuplicateInstances() {
        val t = start(); engine.complete(t, complete(t), due + 1)
        engine.saveTimes("07:35", "18:30", due + 2)
        engine.tick(due + 300_000, true); engine.tick(due + 300_000, true)
        assertEquals(1, state.runs.size); assertEquals(due, run.scheduled)
    }
    @Test fun changingFailedCheckoutToLaterTodayCreatesANewAutomaticRun() {
        val oldCheckout = at(day, "07:45")
        val changedAt = at(day, "14:45")
        val newCheckout = at(day, "18:00")
        engine.savePlanSettings("07:30", "07:45", false, true, oldCheckout - 1)
        engine.setAutomaticEnabled(true, oldCheckout - 1, true)
        engine.tick(oldCheckout + engine.policy.windowMs + 1, true)
        assertEquals(RunStatus.FAILED, state.runs.single().status)

        engine.savePlanSettings("07:30", "18:00", false, true, changedAt)
        assertEquals(newCheckout, engine.nextExecution(changedAt)?.first)
        engine.tick(newCheckout, true)

        assertEquals(2, state.runs.size)
        val replacement = state.runs.single { it.scheduled == newCheckout }
        assertNotEquals(state.runs.single { it.scheduled == oldCheckout }.id, replacement.id)
        assertEquals(Kind.OUT, requireNotNull(engine.begin(newCheckout, true)).kind)
    }
    @Test fun manualCheckoutUpdatesDoNotConsumeLaterAutomaticCheckout() {
        val oldCheckout = at(day, "07:45")
        val changedAt = at(day, "14:45")
        val firstManual = at(day, "15:17")
        val secondManual = at(day, "15:18")
        val automaticCheckout = at(day, "18:00")
        engine.savePlanSettings("07:30", "07:45", false, true, oldCheckout - 1)
        engine.setAutomaticEnabled(true, oldCheckout - 1, true)
        engine.tick(oldCheckout + engine.policy.windowMs + 1, true)
        engine.savePlanSettings("07:30", "18:00", false, true, changedAt)

        listOf(firstManual, secondManual).forEach { manualAt ->
            engine.requestLatest(Kind.OUT, manualAt, true)
            val ticket = requireNotNull(engine.begin(manualAt, true))
            assertTrue(engine.permitSubmit(ticket, allowed(ticket), manualAt, true))
            engine.complete(ticket, complete(ticket), manualAt + 1)
        }

        assertEquals(automaticCheckout, engine.nextExecution(secondManual + 1)?.first)
        engine.tick(automaticCheckout, true)
        val scheduled = state.runs.single { !it.manual && it.scheduled == automaticCheckout }
        assertEquals(RunStatus.PENDING, scheduled.status)
        assertEquals(Kind.OUT, requireNotNull(engine.begin(automaticCheckout, true)).kind)
    }
    @Test fun invalidTimesAndEditingDoesNotChangeRunningDeadline() {
        start(); val deadline = run.deadline
        listOf("", "24:00", "7:30", "12:60").forEach {
            try { engine.saveTimes(it, "18:30", due); fail() } catch (_: IllegalArgumentException) { }
        }
        try { engine.saveTimes("18:30", "18:30", due); fail() } catch (_: IllegalArgumentException) { }
        engine.saveTimes("08:00", "19:00", due)
        assertEquals(deadline, run.deadline); assertEquals(due, run.scheduled)
    }
    @Test fun pauseCancelsRetryWithoutMailAndResumeOnlyFuture() {
        val t = start(); engine.complete(t, Evidence(Result.UNKNOWN), due + 1)
        engine.pause(true, due + 2, true)
        assertEquals(RunStatus.CANCELLED, run.status); assertTrue(state.alerts.isEmpty())
        engine.pause(false, due + 300_000, true)
        assertTrue(state.plans.first().nextDue!! > due + 600_000)
    }
    @Test fun pauseBeforeSubmitStopsAndAfterSubmitPreservesVerification() {
        val t = start(); engine.pause(true, due + 1, true)
        assertFalse(engine.permitSubmit(t, allowed(t), due + 2, true))
        assertEquals(RunStatus.CANCELLED, run.status)
    }
    @Test fun submittedAttemptCanVerifyAfterPauseAndDeadline() {
        val t = start(); engine.permitSubmit(t, allowed(t), due, true)
        engine.pause(true, due + 1, true)
        engine.complete(t, complete(t), due + 600_001)
        assertEquals(RunStatus.SUCCESS, run.status)
    }
    @Test fun authenticationBlocksWithoutMechanicalRetries() {
        val t = start(); engine.complete(t, Evidence(Result.BLOCKED, summary = "需要人工身份验证"), due + 1)
        assertEquals(RunStatus.FAILED, run.status); assertEquals(1, run.attempts.size)
    }
    @Test fun crossMidnightRandomWindowsKeepConfiguredWorkDates() {
        val isolated = MemoryStore()
        val isolatedEngine = Engine(isolated, scheduleOffsetSeconds = { _, _, _ -> 0 })
        isolatedEngine.savePlanSettings("00:05", "23:55", true, true, at(day, "12:00"))
        val crossing = at(day, "23:55")

        isolatedEngine.tick(crossing, true)

        assertEquals(day.plusDays(1), isolated.state.runs.first { it.kind == Kind.IN }.date)
        assertEquals(day, isolated.state.runs.first { it.kind == Kind.OUT }.date)
    }
    @Test fun mailOfflineWaitsAndSuccessDoesNotChangeRun() {
        val t = start(); engine.complete(t, Evidence(Result.BLOCKED), due + 1)
        assertNull(engine.claimMail(due + 2, false, true)); assertEquals(0, state.alerts.single().attempts)
        val a = engine.claimMail(due + 2, true, true)!!
        assertTrue(a.alert.body.contains("截图不可用"))
        engine.finishMail(a.id, a.attempts, true, due + 3)
        assertEquals(MailStatus.ACCEPTED, state.alerts.single().status)
        assertEquals("服务商已接受", state.alerts.single().status.label)
        assertEquals(RunStatus.FAILED, run.status)
    }
    @Test fun mailFailureRetriesTwiceThenStopsAfterThirdAttempt() {
        val t = start(); engine.complete(t, Evidence(Result.BLOCKED), due + 1)
        var now = due + 2
        repeat(3) { index ->
            val a = requireNotNull(engine.claimMail(now, true, true))
            engine.finishMail(a.id, a.attempts, false, now + 1)
            if (index < 2) {
                assertEquals(MailStatus.PENDING, state.alerts.single().status)
                assertNull(engine.claimMail(now + engine.policy.retryMs, true, true))
                now += engine.policy.retryMs + 1
            }
        }
        assertEquals(3, state.alerts.single().attempts)
        assertEquals(MailStatus.FAILED, state.alerts.single().status)
        assertEquals("发送失败", state.alerts.single().status.label)
        assertNull(engine.claimMail(due + 500_000, true, true))
    }
    @Test fun mailWaitingForNetworkExpiresAfterTwentyFourHours() {
        val t = start(); engine.complete(t, Evidence(Result.BLOCKED), due + 1)
        engine.tick(due + 86_400_001, false)
        assertEquals(MailStatus.EXPIRED, state.alerts.first().status)
        assertEquals(0, state.alerts.first().attempts)
    }
    @Test fun unconfiguredMailTransportDoesNotScheduleWakeups() {
        val t = start(); engine.complete(t, Evidence(Result.BLOCKED), due + 1)
        assertNull(engine.nextMailWake(due + 2, transportReady = false))
        assertNotNull(engine.nextMailWake(due + 2, transportReady = true))
    }
    @Test fun manualPreflightFailureAlwaysCreatesOneMailNotification() {
        engine.recordManualFailure(due, "钉钉页面识别失败")
        engine.recordManualFailure(due, "钉钉页面识别失败")
        assertEquals(1, state.alerts.size)
        assertEquals(MailStatus.PENDING, state.alerts.single().status)
        assertTrue(state.alerts.single().subject.contains("手动签到失败"))
        assertTrue(state.alerts.single().body.contains("钉钉页面识别失败"))
    }
    @Test fun scheduledPreflightFailureIsDeduplicatedAndDoesNotConsumeRun() {
        engine.recordPreflightFailure(due, Kind.IN, due - 600_000, "签到辅助服务未开启")
        engine.recordPreflightFailure(due, Kind.IN, due - 599_000, "签到辅助服务未开启")
        assertEquals(1, state.alerts.size)
        assertTrue(state.alerts.single().body.contains("签到辅助服务未开启"))
        assertTrue(state.runs.isEmpty())
        assertEquals(due - 600_000, engine.nextMailWake(due - 700_000))
    }
    @Test fun recipientChangeInvalidatesMailButDoesNotStopCheckin() {
        val t = start(); engine.configureMail("other@example.com", "test-only-ref")
        assertFalse(state.settings.mailVerified)
        assertTrue(engine.permitSubmit(t, allowed(t), due + 1, true))
        try { engine.configureMail("wrong", "test-only-ref"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("other@example.com", state.settings.recipient)
    }
    @Test fun nextExecutionUsesCalendarAndBeijingAndHistory() {
        ready(at(LocalDate.of(2026, 9, 18), "08:00"))
        assertEquals(at(LocalDate.of(2026, 9, 20), "07:20"), engine.nextExecution(at(LocalDate.of(2026, 9, 18), "08:00"))!!.first)
    }
    @Test fun calendarReminderDeduplicatesAndMissingYearBlocks() {
        ready(); val december = at(LocalDate.of(2026, 12, 5), "06:00")
        engine.tick(december, true); engine.tick(december + 1, true)
        assertEquals(1, state.alerts.count { it.id == "calendar:2027" })
        val january = at(LocalDate.of(2027, 1, 1), "07:30")
        engine.tick(january, true)
        assertEquals(RunStatus.FAILED, state.runs.last().status)
    }
    @Test fun retentionFloorPreventsClockRollbackRecreatingPurgedRuns() {
        val t = start(); engine.complete(t, complete(t), due + 1)
        assertEquals(90, engine.policy.retentionDays)
        engine.tick(due + 100L * 86_400_000, true)
        engine.saveTimes("07:31", "18:30", due)
        engine.tick(due + 60_000, true)
        assertTrue(state.runs.none { it.id == t.runId })
    }
    @Test fun mailClaimFreezesConfigurationAndStaleRepliesAreIgnored() {
        val t = start(); engine.complete(t, Evidence(Result.BLOCKED), due + 1)
        val delivery = engine.claimMail(due + 2, true, true)!!
        engine.configureMail("new@example.com", "new-reference")
        assertEquals("me@example.com", delivery.recipient)
        assertEquals("test-only-ref", delivery.credentialRef)
        engine.finishMail(delivery.id, 99, true, due + 3)
        assertEquals(MailStatus.SENDING, state.alerts.single().status)
    }
    @Test fun masterEnableStartsDefaultPlansOnlyInFuture() {
        engine.configureMail("me@example.com", "test-only-ref")
        engine.acceptMailTest("me@example.com", "test-only-ref")
        assertFalse(state.automaticEnabled)
        engine.setAutomaticEnabled(true, due + 1, true)
        assertTrue(state.automaticEnabled)
        assertTrue(state.plans.all { it.enabled && it.nextDue!! > due + 1 })
    }
    @Test fun masterOffCancelsRetryAndPreservesSelectionOnResume() {
        val t = start(); engine.complete(t, Evidence(Result.UNKNOWN), due + 1)
        engine.setAutomaticEnabled(false, due + 2, false)
        assertFalse(state.automaticEnabled)
        assertEquals(RunStatus.CANCELLED, run.status)
        assertTrue(state.plans.all { it.nextDue == null })
        assertTrue(state.alerts.isEmpty())
        engine.setAutomaticEnabled(true, due + 3, true)
        assertTrue(state.automaticEnabled)
        assertTrue(state.plans.first { it.kind == Kind.IN }.enabled)
        assertFalse(state.plans.first { it.kind == Kind.OUT }.enabled)
    }
    @Test fun masterEnableFailureDoesNotChangeSettings() {
        val before = state.plans.map { it.copy() }
        try { engine.setAutomaticEnabled(true, due, false); fail() } catch (_: IllegalArgumentException) { }
        assertFalse(state.automaticEnabled); assertEquals(before, state.plans)
    }
    @Test fun masterOffPreventsSubmitButPreservesSubmittedVerification() {
        val t = start()
        assertTrue(engine.permitSubmit(t, allowed(t), due, true))
        engine.setAutomaticEnabled(false, due + 1, false)
        assertFalse(engine.permitSubmit(t, allowed(t), due + 2, true))
        engine.complete(t, complete(t), due + 3)
        assertEquals(RunStatus.SUCCESS, run.status)
        assertFalse(state.automaticEnabled)
    }
    @Test fun immediateWorksWhileAutomaticPausedAndUsesSameDeduplicationKey() {
        engine.configureMail("me@example.com", "test-only-ref")
        engine.acceptMailTest("me@example.com", "test-only-ref")
        engine.pause(true, due, false)
        val id = engine.requestImmediate(Kind.IN, due, true)
        assertEquals(key(day, Kind.IN), id)
        assertTrue(run.manual); assertFalse(state.automaticEnabled)
        val t = engine.begin(due, true)!!
        assertTrue(engine.permitSubmit(t, allowed(t), due, true))
        engine.complete(t, complete(t), due + 1)
        try { engine.requestImmediate(Kind.IN, due + 2, true); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, state.runs.size)
    }
    @Test fun immediateCannotBypassConfigurationOrExistingRetryBudget() {
        try { engine.requestImmediate(Kind.OUT, due, false); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(state.runs.isEmpty())
        val t = start(); engine.complete(t, Evidence(Result.UNKNOWN), due + 1)
        try { engine.requestImmediate(Kind.IN, due + 2, true); fail() } catch (_: IllegalArgumentException) { }
        try { engine.requestImmediate(Kind.OUT, due + 2, true); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, run.attempts.size)
    }
    @Test fun immediateRecoveryRetriesWithoutEnablingAutomaticPlans() {
        ready(); engine.pause(true, due, true)
        engine.requestImmediate(Kind.OUT, due, true)
        val t = engine.begin(due, true)!!
        engine.permitSubmit(t, allowed(t), due, true)
        engine = fixedEngine(); engine.recover(due + 1)
        val retry = requireNotNull(engine.begin(due + 120_001, true))
        assertEquals(2, retry.attempt)
        assertTrue(retry.previousSubmissionMinutes.contains("07:20"))
        assertEquals(RunStatus.RUNNING, run.status)
        assertFalse(state.automaticEnabled)
        assertEquals(2, run.attempts.size)
    }
    @Test fun latestManualUsesActualPageEvenOnRestDayWithoutMail() {
        val saturday = at(LocalDate.of(2026, 9, 5), "22:00")
        engine.requestLatest(Kind.OUT, saturday, true)
        val t = engine.begin(saturday, true)!!
        assertTrue(engine.permitSubmit(t, allowed(t), saturday, true))
        engine.complete(t, complete(t), saturday + 1)
        engine.requestLatest(Kind.OUT, saturday + 2, true)
        assertEquals(2, state.runs.size)
        assertFalse(state.automaticEnabled)
    }
    @Test fun finalResultCopyDistinguishesUnconfirmedFromFailure() {
        assertEquals("打卡成功", RunPresentation.completionMessage(RunStatus.SUCCESS))
        assertEquals("打卡成功 · 07:36", RunPresentation.completionMessage(RunStatus.SUCCESS,
            "钉钉已有上班打卡成功 · 07:36（已核验，未重复提交）"))
        assertEquals("打卡失败", RunPresentation.completionMessage(RunStatus.FAILED))
        assertEquals("结果待核查", RunPresentation.completionMessage(RunStatus.UNKNOWN))
        assertEquals("打卡失败", RunPresentation.completionMessage(RunStatus.MISSED))
        assertEquals("待核查", RunStatus.UNKNOWN.label)
        assertEquals("失败", RunStatus.MISSED.label)
        assertEquals("成功", RunPresentation.finalResultLabel(RunStatus.SUCCESS))
        listOf(RunStatus.FAILED, RunStatus.MISSED).forEach {
            assertEquals("失败", RunPresentation.finalResultLabel(it))
        }
        assertEquals("待核查", RunPresentation.finalResultLabel(RunStatus.UNKNOWN))
        listOf(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.RETRY, RunStatus.SKIPPED, RunStatus.CANCELLED).forEach {
            assertNull(RunPresentation.finalResultLabel(it))
        }
    }
    @Test fun explicitFailureStopsWithoutRetry() {
        engine.requestLatest(Kind.OUT, due, true)
        val ticket = requireNotNull(engine.begin(due, true))
        engine.complete(ticket, Evidence(Result.FAILURE, summary = "更新下班卡失败"), due + 1)
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(1, run.attempts.size)
        assertEquals("打卡失败", RunPresentation.completionMessage(run.status))
        assertNull(engine.begin(due + engine.policy.retryMs + 1, true))
    }
    @Test fun fourStepManualUnknownStopsAndQueuesReviewMailWithoutRetry() {
        engine.requestLatest(Kind.OUT, due, true)
        val ticket = requireNotNull(engine.begin(due, true))
        assertTrue(engine.permitSubmit(ticket, allowed(ticket), due, true))
        engine.completeManualUnknown(ticket, "结果页未能核验", due + 1)
        assertEquals(RunStatus.UNKNOWN, run.status)
        assertEquals(1, run.attempts.size)
        assertEquals(1, state.alerts.count { it.runId == run.id })
        assertNull(engine.begin(due + engine.policy.retryMs + 1, true))
    }
    @Test fun scheduledSubmittedUnknownNeverRepeatsThePunch() {
        val ticket = start()
        assertTrue(engine.permitSubmit(ticket, allowed(ticket), due, true))
        engine.completeSubmittedUnknown(ticket, "统计页未确认", due + 1)
        assertEquals(RunStatus.UNKNOWN, run.status)
        assertEquals(1, run.attempts.size)
        assertNull(engine.begin(due + engine.policy.retryMs + 1, true, runId = ticket.runId))
    }
    @Test fun submittedRetriesCarryAllEvidenceAndReturnOnlyAfterSuccess() {
        val first = start()
        assertTrue(engine.permitSubmit(first, allowed(first), due, true))
        engine.complete(first, Evidence(Result.UNKNOWN, summary = "回执未确认"), due + 1)
        assertEquals(RunStatus.RETRY, run.status)
        assertNull(RunPresentation.completionMessage(run.status))
        val second = requireNotNull(engine.begin(due + engine.policy.retryMs + 1, true))
        assertTrue("07:20" in second.previousSubmissionMinutes)
        assertFalse("07:21" in second.previousSubmissionMinutes)
        assertTrue(engine.permitSubmit(second, allowed(second), due + engine.policy.retryMs + 1, true))
        engine.complete(second, Evidence(Result.UNKNOWN, summary = "回执仍未确认"), due + engine.policy.retryMs + 2)
        assertNull(RunPresentation.completionMessage(run.status))
        val third = requireNotNull(engine.begin(due + engine.policy.retryMs * 2 + 2, true))
        assertTrue("07:20" in third.previousSubmissionMinutes)
        assertTrue("07:22" in third.previousSubmissionMinutes)
        engine.complete(third, complete(third), due + engine.policy.retryMs * 2 + 3)
        assertEquals("打卡成功", RunPresentation.completionMessage(run.status))
        assertNull(engine.begin(due + engine.policy.retryMs * 3, true))
    }
}
