package com.niuma.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledWakeTest {
    @Test fun dueAlarmLightsScreenWhileDeviceIsAsleep() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val power = context.getSystemService(PowerManager::class.java)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 909,
            Intent(context, ScheduleReceiver::class.java).setAction(CheckinApp.ACTION_DUE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 223").close()
            waitUntil(5_000) { !power.isInteractive }
            assertFalse("测试开始前屏幕必须熄灭", power.isInteractive)
            alarm.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 3_000, pending), pending)
            waitUntil(12_000) { power.isInteractive }
            assertTrue("定时任务必须点亮屏幕", power.isInteractive)
        } finally {
            alarm.cancel(pending)
        }
    }

    private fun waitUntil(timeoutMs: Long, ready: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!ready() && System.currentTimeMillis() < until) Thread.sleep(100)
    }
}
