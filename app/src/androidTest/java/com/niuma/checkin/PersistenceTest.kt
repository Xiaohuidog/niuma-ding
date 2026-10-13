package com.niuma.checkin

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.niuma.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun database(name: String) = Room.databaseBuilder(context, CheckinDatabase::class.java, name).build()
    @Test fun roomReopenKeepsTheSameRandomizedInputTime() {
        val name = "test-${UUID.randomUUID()}.db"
        var db = database(name)
        try {
            val scheduleDate = LocalDate.of(2026, 9, 7)
            val boundary = at(scheduleDate, "07:30")
            var engine = Engine(RoomStore(db))
            engine.savePlanSettings("07:30", "18:00", true, false, at(scheduleDate, "00:00"))
            val randomizedDue = engine.store.transaction { it.plans.first { p -> p.kind == Kind.IN }.nextDue!! }
            assertTrue(randomizedDue >= boundary - 600_000 && randomizedDue < boundary)
            engine.tick(randomizedDue, true)
            val nextAfterTick = engine.store.transaction { it.plans.first { p -> p.kind == Kind.IN }.nextDue!! }

            db.close(); db = database(name); engine = Engine(RoomStore(db))

            assertEquals(nextAfterTick, engine.store.transaction { it.plans.first { p -> p.kind == Kind.IN }.nextDue })
            val restoredRun = engine.store.transaction { it.runs.single() }
            assertEquals(scheduleDate, restoredRun.scheduleDate)
            assertEquals(scheduleDate, restoredRun.date)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun roomReopenRetainsSettingsAndSubmittedAttempt() {
        val name = "test-${UUID.randomUUID()}.db"
        var db = database(name)
        try {
            val due = at(LocalDate.of(2026, 9, 7), "07:30")
            var engine = Engine(RoomStore(db))
            engine.configureMail("test@example.com", "synthetic-reference")
            engine.acceptMailTest("test@example.com", "synthetic-reference")
            engine.setEnabled(Kind.IN, true, due - 1, true)
            engine.tick(due, true)
            val ticket = engine.begin(due, true)!!
            assertTrue(engine.permitSubmit(ticket, Evidence(Result.NOT_DONE, ticket.date, ticket.kind, allowsSubmit = true), due, true))
            db.close(); db = database(name); engine = Engine(RoomStore(db))
            engine.recover(due + 1)
            val restored = engine.store.transaction { it }
            assertEquals("test@example.com", restored.settings.recipient)
            assertEquals(1, restored.runs.single().attempts.size)
            assertTrue(restored.runs.single().attempts.single().submitted)
            assertEquals(RunStatus.RETRY, restored.runs.single().status)
            engine.tick(due + 600_000, true)
            db.close(); db = database(name)
            assertEquals(1, RoomStore(db).transaction { it.alerts.size })
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun rollbackPreservesPreviouslySavedState() {
        val name = "test-${UUID.randomUUID()}.db"; val db = database(name)
        try {
            val store = RoomStore(db)
            store.transaction { it.settings.recipient = "saved@example.com" }
            try { store.transaction { it.settings.recipient = "changed@example.com"; error("abort") }; fail() } catch (_: IllegalStateException) { }
            assertEquals("saved@example.com", store.transaction { it.settings.recipient })
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun roomReopenRetainsExecutionEvents() {
        val name = "test-${UUID.randomUUID()}.db"
        var db = database(name)
        try {
            val due = at(LocalDate.of(2026, 9, 7), "07:30")
            var engine = Engine(RoomStore(db))
            engine.setEnabled(Kind.IN, true, due - 1, true)
            engine.tick(due, true)
            val runId = requireNotNull(engine.currentAutomaticRunId(due))
            engine.recordExecutionEvent(runId, AutomaticExecutionStage.WAKE_LOCK_HELD,
                ExecutionEventResult.PASSED, due, "已持有唤醒锁")
            engine.recordExecutionEvent(runId, AutomaticExecutionStage.UNLOCK_CONFIRMED,
                ExecutionEventResult.FAILED, due + 15_000, "普通锁屏未能解除")

            db.close(); db = database(name); engine = Engine(RoomStore(db))
            val restored = engine.store.transaction { it.executionEvents }
            assertEquals(2, restored.size)
            assertEquals(ExecutionEventResult.FAILED, restored.last().result)
            assertEquals("普通锁屏未能解除", restored.last().reason)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun roomReopenRetainsMailHistoryClearWithoutDeletingAlerts() {
        val name = "test-${UUID.randomUUID()}.db"
        var db = database(name)
        try {
            val clearedAt = at(LocalDate.of(2026, 9, 17), "09:17")
            var engine = Engine(RoomStore(db))
            engine.store.transaction { s ->
                s.alerts.add(Alert("kept", null, clearedAt - 1, clearedAt + 60_000, "kept", "kept"))
            }
            engine.clearMailHistory(clearedAt)

            db.close(); db = database(name); engine = Engine(RoomStore(db))
            val restored = engine.store.transaction { it }
            assertEquals(clearedAt, restored.settings.mailHistoryClearedAt)
            assertEquals(listOf("kept"), restored.alerts.map { it.id })
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun concurrentTriggersHaveSingleAttempt() {
        val name = "test-${UUID.randomUUID()}.db"; val db = database(name)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val store = RoomStore(db); val engine = Engine(store); val due = at(LocalDate.of(2026, 9, 7), "07:30")
            engine.configureMail("test@example.com", "synthetic-reference"); engine.acceptMailTest("test@example.com", "synthetic-reference")
            engine.setEnabled(Kind.IN, true, due - 1, true)
            val tasks = (1..2).map { pool.submit<Engine.Ticket?> { engine.tick(due, true); engine.begin(due, true) } }
            assertEquals(1, tasks.map { it.get() }.count { it != null })
            assertEquals(1, store.transaction { it.runs.single().attempts.size })
        } finally { pool.shutdownNow(); db.close(); context.deleteDatabase(name) }
    }
    @Test fun keyStoreRoundTripAndDeletion() {
        val vault = CredentialVault(context)
        val secret = "synthetic-test-value".toByteArray()
        val ref = vault.put(secret)
        try { assertArrayEquals(secret, CredentialVault(context).read(ref)) }
        finally { vault.remove(ref); secret.fill(0) }
        try { vault.read(ref); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun manualRunReopensWithPausedPlans() {
        val name = "test-${UUID.randomUUID()}.db"; var db = database(name)
        try {
            val now = at(LocalDate.of(2026, 9, 7), "08:00")
            var engine = Engine(RoomStore(db))
            engine.configureMail("test@example.com", "synthetic-reference")
            engine.acceptMailTest("test@example.com", "synthetic-reference")
            engine.pause(true, now, true)
            engine.requestImmediate(Kind.OUT, now, true)
            db.close(); db = database(name); engine = Engine(RoomStore(db))
            assertTrue(engine.store.transaction { it.runs.single().manual })
            assertFalse(engine.store.transaction { it.automaticEnabled })
            assertNotNull(engine.begin(now, true))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
