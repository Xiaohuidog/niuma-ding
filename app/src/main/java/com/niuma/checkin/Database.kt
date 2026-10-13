package com.niuma.checkin

import androidx.room.*
import com.niuma.core.*
import java.util.concurrent.Callable

@Entity(tableName = "settings")
data class SettingsRow(@PrimaryKey val id: Int = 1, val paused: Boolean, val recipient: String,
    val credentialRef: String, val mailVerified: Boolean, val retentionFloor: Long, val remindedYears: String,
    @ColumnInfo(defaultValue = "-9223372036854775808") val mailHistoryClearedAt: Long,
    @ColumnInfo(defaultValue = "0") val scheduleVersion: Int)
@Entity(tableName = "plans")
data class PlanRow(@PrimaryKey val kind: String, val time: String, val enabled: Boolean, val nextDue: Long?)
@Entity(tableName = "runs")
data class RunRow(@PrimaryKey val id: String, val kind: String, val scheduled: Long, val deadline: Long,
    val status: String, val nextAttempt: Long?, val finished: Long?, val reason: String, val cancelRequested: Boolean,
    @ColumnInfo(defaultValue = "0") val manual: Boolean = false, val scheduleDate: String? = null)
@Entity(tableName = "attempts", primaryKeys = ["runId", "number"])
data class AttemptRow(val runId: String, val number: Int, val started: Long, val finished: Long?, val submitted: Boolean, val reason: String)
@Entity(tableName = "alerts")
data class AlertRow(@PrimaryKey val id: String, val runId: String?, val created: Long, val expires: Long,
    val subject: String, val body: String, val status: String, val attempts: Int, val nextAttempt: Long,
    val reason: String, val sendingSince: Long?)
@Entity(tableName = "execution_events")
data class ExecutionEventRow(@PrimaryKey val id: String, val runId: String, val created: Long,
    val stage: String, val result: String, val reason: String)

@Dao
interface StateDao {
    @Query("SELECT * FROM settings WHERE id = 1") fun settings(): SettingsRow?
    @Query("SELECT * FROM plans") fun plans(): List<PlanRow>
    @Query("SELECT * FROM runs ORDER BY scheduled") fun runs(): List<RunRow>
    @Query("SELECT * FROM attempts ORDER BY number") fun attempts(): List<AttemptRow>
    @Query("SELECT * FROM alerts ORDER BY created") fun alerts(): List<AlertRow>
    @Query("SELECT * FROM execution_events ORDER BY created, id") fun executionEvents(): List<ExecutionEventRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: SettingsRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun plans(rows: List<PlanRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun runs(rows: List<RunRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun attempts(rows: List<AttemptRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun alerts(rows: List<AlertRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun executionEvents(rows: List<ExecutionEventRow>)
    @Query("DELETE FROM attempts WHERE runId NOT IN (:ids)") fun pruneAttempts(ids: List<String>)
    @Query("DELETE FROM runs WHERE id NOT IN (:ids)") fun pruneRuns(ids: List<String>)
    @Query("DELETE FROM alerts WHERE id NOT IN (:ids)") fun pruneAlerts(ids: List<String>)
    @Query("DELETE FROM execution_events WHERE id NOT IN (:ids)") fun pruneExecutionEvents(ids: List<String>)
}
@Database(entities = [SettingsRow::class, PlanRow::class, RunRow::class, AttemptRow::class, AlertRow::class,
    ExecutionEventRow::class], version = 8, exportSchema = true)
abstract class CheckinDatabase : RoomDatabase() {
    abstract fun state(): StateDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM alerts")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM alerts")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `execution_events` (`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `created` INTEGER NOT NULL, `stage` TEXT NOT NULL, `result` TEXT NOT NULL, `reason` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `settings` ADD COLUMN `mailHistoryClearedAt` INTEGER NOT NULL DEFAULT -9223372036854775808")
            }
        }
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `settings` ADD COLUMN `scheduleVersion` INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `runs` ADD COLUMN `scheduleDate` TEXT")
            }
        }
    }
}

class RoomStore(private val db: CheckinDatabase) : Store {
    override fun <T> transaction(block: (State) -> T): T = db.runInTransaction(Callable {
        val dao = db.state()
        val row = dao.settings()
        val s = if (row == null) State() else {
            val attempts = dao.attempts().groupBy { it.runId }
            State(Settings(paused = row.paused, recipient = row.recipient, credentialRef = row.credentialRef,
                mailVerified = row.mailVerified, retentionFloor = row.retentionFloor,
                mailHistoryClearedAt = row.mailHistoryClearedAt, scheduleVersion = row.scheduleVersion),
                dao.plans().map { Plan(Kind.valueOf(it.kind), it.time, it.enabled, it.nextDue) }.toMutableList(),
                dao.runs().map { r -> Run(r.id, Kind.valueOf(r.kind), r.scheduled, r.deadline, RunStatus.valueOf(r.status),
                    r.nextAttempt, r.finished, r.reason, r.cancelRequested, attempts[r.id].orEmpty().map {
                        Attempt(it.number, it.started, it.finished, it.submitted, it.reason)
                    }.toMutableList(), r.manual, r.scheduleDate?.let(java.time.LocalDate::parse)) }.toMutableList(),
                dao.alerts().map { Alert(it.id, it.runId, it.created, it.expires, it.subject, it.body,
                    MailStatus.valueOf(it.status), it.attempts, it.nextAttempt, it.reason, it.sendingSince) }.toMutableList(),
                row.remindedYears.split(',').filter { it.isNotEmpty() }.map { it.toInt() }.toMutableSet(),
                dao.executionEvents().map {
                    ExecutionEvent(it.id, it.runId, it.created, AutomaticExecutionStage.valueOf(it.stage),
                        ExecutionEventResult.valueOf(it.result), it.reason)
                }.toMutableList())
        }
        val result = block(s)
        dao.put(SettingsRow(paused = s.settings.paused, recipient = s.settings.recipient,
            credentialRef = s.settings.credentialRef, mailVerified = s.settings.mailVerified,
            retentionFloor = s.settings.retentionFloor, remindedYears = s.remindedYears.sorted().joinToString(","),
            mailHistoryClearedAt = s.settings.mailHistoryClearedAt, scheduleVersion = s.settings.scheduleVersion))
        dao.plans(s.plans.map { PlanRow(it.kind.name, it.time, it.enabled, it.nextDue) })
        dao.runs(s.runs.map { RunRow(it.id, it.kind.name, it.scheduled, it.deadline, it.status.name,
            it.nextAttempt, it.finished, it.reason, it.cancelRequested, it.manual, it.scheduleDate?.toString()) })
        dao.attempts(s.runs.flatMap { r -> r.attempts.map { AttemptRow(r.id, it.number, it.started, it.finished, it.submitted, it.reason) } })
        dao.alerts(s.alerts.map { AlertRow(it.id, it.runId, it.created, it.expires, it.subject, it.body,
            it.status.name, it.attempts, it.nextAttempt, it.reason, it.sendingSince) })
        dao.executionEvents(s.executionEvents.map {
            ExecutionEventRow(it.id, it.runId, it.created, it.stage.name, it.result.name, it.reason)
        })
        dao.pruneAttempts(s.runs.map { it.id }); dao.pruneRuns(s.runs.map { it.id }); dao.pruneAlerts(s.alerts.map { it.id })
        dao.pruneExecutionEvents(s.executionEvents.map { it.id })
        result
    })
}
