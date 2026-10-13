package com.niuma.checkin

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.app.KeyguardManager
import android.view.accessibility.AccessibilityManager
import androidx.room.Room
import androidx.work.*
import com.niuma.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CheckinApp : Application() {
    val io = Executors.newSingleThreadExecutor()
    private val initialized = java.util.concurrent.CountDownLatch(1)
    lateinit var store: RoomStore
    lateinit var engine: Engine
    val mail by lazy { QqMailTransport(this) }
    val screenCapture = ScreenCaptureSession(this)
    val mailCalls = Executors.newSingleThreadExecutor()
    @Volatile var runtimeError: String? = null

    override fun onCreate() {
        super.onCreate()
        store = RoomStore(Room.databaseBuilder(this, CheckinDatabase::class.java, "niuma.db")
            .addMigrations(CheckinDatabase.MIGRATION_1_2, CheckinDatabase.MIGRATION_2_3,
                CheckinDatabase.MIGRATION_3_4, CheckinDatabase.MIGRATION_4_5,
                CheckinDatabase.MIGRATION_5_6, CheckinDatabase.MIGRATION_6_7,
                CheckinDatabase.MIGRATION_7_8).build())
        engine = Engine(store)
        io.execute {
            try {
                val now = System.currentTimeMillis()
                engine.upgradeRandomSchedule(now)
                engine.recover(now)
                mail.ensureBundledConfiguration(engine)
                reconcile()
            } catch (error: Exception) {
                android.util.Log.e("CheckinApp", "应用初始化失败", error)
                runtimeError = "本地数据读取失败；打卡脚本已移除"
            } finally { initialized.countDown() }
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("maintenance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS).build())
    }

    fun awaitInitialization() { check(initialized.await(30, TimeUnit.SECONDS)) { "初始化超时" } }
    fun backgroundAllowed(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    fun accessibilityEnabled(): Boolean = getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.let { service ->
            service.packageName == packageName && service.name == DingTalkWorkbenchNavigator::class.java.name
        } }
    fun automaticConfigured(): Boolean = automaticBlocker() == null
    fun automaticBlocker(): String? = when {
        !accessibilityEnabled() -> "钉钉工作台导航辅助服务未启用"
        DingTalkWorkbenchNavigator.connected == null -> "钉钉工作台导航辅助服务已启用，等待连接"
        !screenCapture.authorized -> "截图会话未授权或已失效"
        !exactAllowed() -> "未授权精确定时闹钟"
        !online() -> "Wi-Fi 网络未连接"
        getSystemService(KeyguardManager::class.java).isKeyguardLocked -> "手机仍处于锁屏状态"
        else -> null
    }
    fun online(): Boolean = DeviceNetwork.isValidatedWifi(this)
    fun captureDingTalkFailure(runId: String, navigator: DingTalkWorkbenchNavigator) {
        if (navigator.isDingTalkVisible() || navigator.isSystemOverlayVisible())
            screenCapture.capture(runId)
    }
    fun exactAllowed() = Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    data class Check(val label: String, val passed: Boolean, val detail: String)
    fun checks(): List<Check> {
        val accessibilityEnabled = accessibilityEnabled()
        val notification = getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        return listOf(
            Check("四步打卡", DingTalkWorkbenchNavigator.connected != null && screenCapture.authorized,
                if (!accessibilityEnabled) "请启用钉钉工作台导航辅助服务"
                else if (DingTalkWorkbenchNavigator.connected == null) "辅助服务已启用，等待连接"
                else if (!screenCapture.authorized) "请授权本机截图会话" else "手动流程可读取并操作钉钉"),
            Check("本地数据", runtimeError == null, runtimeError ?: "排班、记录和邮件数据可读取"),
            Check("网络", online(), if (online()) "网络可用于发送历史通知邮件" else "当前网络不可用"),
            Check("运行提示", notification, if (notification) "允许显示运行提示" else "系统未允许运行提示")
        )
    }

    fun reconcile() {
        val now = System.currentTimeMillis()
        val blocker = automaticBlocker()
        engine.tick(now, blocker == null, blocker ?: "")
        scheduleExecutionWake(now)
        enqueueMail()
        scheduleMailWake(now)
    }

    fun scheduleExecutionWake(now: Long = System.currentTimeMillis()) {
        val alarm = getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(this, 1,
            Intent(this, ScheduleReceiver::class.java).setAction(ACTION_DUE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        val next = engine.nextWake(now) ?: return
        if (exactAllowed()) {
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.setAlarmClock(AlarmManager.AlarmClockInfo(next, open), pending)
        }
    }

    fun runDue() {
        awaitInitialization()
        val now = System.currentTimeMillis()
        val blocker = automaticBlocker()
        engine.tick(now, blocker == null, blocker ?: "")
        engine.pendingVerification(now)?.let { engine.completeVerification(it, null, now) }
        if (blocker == null) {
            val runId = engine.currentAutomaticRunId(now)
            if (runId != null) {
                val ticket = engine.begin(now, true, runId = runId)
                if (ticket != null) DingTalkCheckinFlow(this).execute(ticket)
            }
        }
        reconcile()
    }

    fun enqueueMail() {
        WorkManager.getInstance(this).enqueueUniqueWork("mail-direct-v2", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MailWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun scheduleMailWake(now: Long = System.currentTimeMillis()) {
        val alarm = getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(this, 6,
            Intent(this, ScheduleReceiver::class.java).setAction(ACTION_MAIL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        engine.nextMailWake(now, mail.configured)?.let { next ->
            if (exactAllowed()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
    }
    companion object {
        const val ACTION_DUE = "com.niuma.checkin.DUE"
        const val ACTION_MAIL = "com.niuma.checkin.MAIL"
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CheckinApp
        when (intent.action) {
            CheckinApp.ACTION_MAIL -> app.enqueueMail()
            CheckinApp.ACTION_DUE -> context.startForegroundService(Intent(context, ExecutionService::class.java)
                .setAction(ExecutionService.ACTION_DUE))
            else -> {
                val pending = goAsync()
                app.io.execute { try { app.awaitInitialization(); app.reconcile() } finally { pending.finish() } }
            }
        }
    }
}

class ExecutionService : Service() {
    private val app get() = application as CheckinApp
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CAPTURE_SESSION && intent?.action != ACTION_DUE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("capture-session", "本机截图会话", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "capture-session")
            .setSmallIcon(R.drawable.ic_status).setContentTitle("牛马签到")
            .setContentText("本机截图会话").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notification)
        if (intent.action == ACTION_CAPTURE_SESSION) {
            val resultCode = intent.getIntExtra(ScreenCaptureSession.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>(ScreenCaptureSession.EXTRA_DATA)
            if (!app.screenCapture.acceptPermission(resultCode, data)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        } else {
            app.io.execute {
                val wake = getSystemService(PowerManager::class.java).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "niuma:scheduled-checkin")
                @Suppress("DEPRECATION")
                val screenWake = getSystemService(PowerManager::class.java).newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "niuma:scheduled-screen")
                wake.acquire(180_000L)
                screenWake.acquire(180_000L)
                @Suppress("DEPRECATION")
                val keyguard = getSystemService(KeyguardManager::class.java).takeIf { !it.isDeviceSecure }
                    ?.newKeyguardLock("niuma:scheduled-checkin")
                try {
                    try {
                        @Suppress("DEPRECATION")
                        keyguard?.disableKeyguard()
                    } catch (error: RuntimeException) {
                        android.util.Log.w("CheckinApp", "无法解除非安全锁屏", error)
                    }
                    app.runDue()
                }
                finally {
                    try {
                        @Suppress("DEPRECATION")
                        keyguard?.reenableKeyguard()
                    } catch (error: RuntimeException) {
                        android.util.Log.w("CheckinApp", "恢复非安全锁屏失败", error)
                    }
                    if (screenWake.isHeld) screenWake.release()
                    if (wake.isHeld) wake.release()
                    if (!app.screenCapture.authorized) stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }
    companion object {
        const val ACTION_CAPTURE_SESSION = "com.niuma.checkin.CAPTURE_SESSION"
        const val ACTION_DUE = "com.niuma.checkin.EXECUTE_DUE"
    }
}

class MaintenanceWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val app = applicationContext as CheckinApp
        app.awaitInitialization(); app.reconcile(); Result.success()
    } catch (_: Exception) { Result.retry() }
}
class MailWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as CheckinApp
        return try {
            app.awaitInitialization()
            while (true) {
                val alert = app.engine.claimMail(System.currentTimeMillis(), app.online(), app.mail.configured) ?: break
                try {
                    val call = app.mailCalls.submit<Boolean> { app.mail.send(alert.alert, alert.recipient, alert.credentialRef) }
                    val sent = try { call.get(app.engine.policy.timeoutMs, TimeUnit.MILLISECONDS) }
                    catch (e: Exception) { call.cancel(true); throw e }
                    app.engine.finishMail(alert.id, alert.attempts, sent, System.currentTimeMillis())
                } catch (_: Exception) {
                    app.engine.finishMail(alert.id, alert.attempts, false, System.currentTimeMillis())
                }
            }
            app.scheduleMailWake()
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}
