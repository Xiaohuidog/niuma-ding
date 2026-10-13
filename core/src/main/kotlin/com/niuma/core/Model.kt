package com.niuma.core

import java.time.*

val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")
const val AUTOMATIC_WINDOW_MS = 10L * 60 * 1000
const val AUTOMATIC_WINDOW_SECONDS = 10 * 60
const val UNKNOWN_VERIFICATION_GRACE_MS = 5L * 60 * 1000
enum class Kind(val label: String) { IN("上班"), OUT("下班") }
/** Historical execution stages retained for stored event records. */
enum class AutomaticExecutionStage {
    SERVICE_STARTED, WAKE_LOCK_HELD, UNLOCK_CONFIRMED, WIFI_VALIDATED,
    ACCESSIBILITY_CONNECTED, PAGE_RECOGNIZED, FINISHED, READ_ONLY_VERIFICATION
}
enum class RunStatus(val label: String) {
    PENDING("待执行"), RUNNING("执行中"), RETRY("等待重试"), SUCCESS("成功"),
    FAILED("失败"), UNKNOWN("待核查"), MISSED("失败"), SKIPPED("已跳过"), CANCELLED("已取消");
    val terminal get() = this !in setOf(PENDING, RUNNING, RETRY)
    val attention get() = this in setOf(FAILED, UNKNOWN, MISSED)
}
enum class MailStatus(val label: String) {
    PENDING("待发送"), SENDING("发送中"), ACCEPTED("服务商已接受"), FAILED("发送失败"), EXPIRED("已失效")
}
enum class Result { COMPLETE, NOT_DONE, UPDATE_READY, FAILURE, UNKNOWN, BLOCKED }
data class Evidence(val result: Result, val date: LocalDate? = null, val kind: Kind? = null,
                    val summary: String = "", val allowsSubmit: Boolean = false)
data class Policy(val maxAttempts: Int = 3, val retryMs: Long = 120_000,
                  val timeoutMs: Long = 90_000, val windowMs: Long = 600_000,
                  val retentionDays: Long = 90) {
    init {
        require(maxAttempts in 1..3)
        require(retryMs > 0 && timeoutMs > 0 && windowMs > 0 && retentionDays > 0)
    }
}
data class Plan(val kind: Kind, var time: String, var enabled: Boolean = false, var nextDue: Long? = null)
data class Attempt(val number: Int, val started: Long, var finished: Long? = null,
                   var submitted: Boolean = false, var reason: String = "核验已有结果")
data class Run(val id: String, val kind: Kind, val scheduled: Long, val deadline: Long,
               var status: RunStatus = RunStatus.PENDING, var nextAttempt: Long? = null,
               var finished: Long? = null, var reason: String = "", var cancelRequested: Boolean = false,
               val attempts: MutableList<Attempt> = mutableListOf(), val manual: Boolean = false,
               val scheduleDate: LocalDate? = null) {
    val date: LocalDate get() = when {
        manual -> dateAt(scheduled)
        scheduleDate != null -> scheduleDate
        else -> runCatching { LocalDate.parse(id.substringBefore(':')) }.getOrElse { dateAt(scheduled) }
    }
}
data class Alert(val id: String, val runId: String?, val created: Long, val expires: Long,
                 var subject: String, var body: String, var status: MailStatus = MailStatus.PENDING,
                 var attempts: Int = 0, var nextAttempt: Long = created, var reason: String = "",
                 var sendingSince: Long? = null)
fun Run.reviewInProgress(alert: Alert?, now: Long): Boolean =
    status == RunStatus.UNKNOWN && attempts.any { it.submitted } && finished != null &&
        alert?.status == MailStatus.PENDING && alert.attempts == 0 &&
        alert.nextAttempt >= finished!! + UNKNOWN_VERIFICATION_GRACE_MS && now < alert.nextAttempt
enum class ExecutionEventResult { STARTED, PASSED, FAILED }
data class ExecutionEvent(val id: String, val runId: String, val created: Long,
                          val stage: AutomaticExecutionStage, val result: ExecutionEventResult,
                          val reason: String = "")
data class Settings(var paused: Boolean = false, var recipient: String = "",
                    var credentialRef: String = "", var mailVerified: Boolean = false,
                    var retentionFloor: Long = Long.MIN_VALUE,
                    var mailHistoryClearedAt: Long = Long.MIN_VALUE,
                    var scheduleVersion: Int = 0)
data class State(val settings: Settings = Settings(),
                 val plans: MutableList<Plan> = mutableListOf(Plan(Kind.IN, "07:30"), Plan(Kind.OUT, "17:30")),
                 val runs: MutableList<Run> = mutableListOf(), val alerts: MutableList<Alert> = mutableListOf(),
                 val remindedYears: MutableSet<Int> = mutableSetOf(),
                 val executionEvents: MutableList<ExecutionEvent> = mutableListOf()) {
    val automaticEnabled: Boolean get() = !settings.paused && plans.any { it.enabled }
}

fun Alert.requiresScreenshotAttachment(): Boolean = !runId.isNullOrBlank() &&
    (subject.contains("[需核查]") || subject.contains("[成功]"))

fun screenshotEvidenceFileName(runId: String): String =
    runId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png"

interface Store { fun <T> transaction(block: (State) -> T): T }
fun at(date: LocalDate, time: String): Long = date.atTime(LocalTime.parse(time)).atZone(BEIJING).toInstant().toEpochMilli()
fun key(date: LocalDate, kind: Kind) = "$date:${kind.name}"
fun dateAt(now: Long): LocalDate = Instant.ofEpochMilli(now).atZone(BEIJING).toLocalDate()
fun stamp(now: Long): String = Instant.ofEpochMilli(now).atZone(BEIJING).toLocalDateTime().toString().replace('T', ' ')
