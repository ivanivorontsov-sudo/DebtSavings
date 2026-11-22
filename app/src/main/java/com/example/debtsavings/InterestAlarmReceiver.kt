package com.example.debtsavings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

private const val ACTION_DAILY_INTEREST = "com.example.debtsavings.DAILY_INTEREST"
private const val REQUEST_CODE = 7001

/** Runs the accrual at the Moscow-day boundary even when the app is closed. */
class InterestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DAILY_INTEREST) return

        val prefs = context.getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        val transactions = mutableListOf<Transaction>()
        val credits = mutableListOf<Credit>()
        Persistence.loadTransactions(prefs, transactions)
        Persistence.loadCredits(prefs, credits)

        DailyInterest.applyIfNeeded(prefs, transactions, credits)
        SubscriptionNotifier.checkAndNotify(context)
        // Payment reminders fire only at local 09/14/20 via PaymentReminderReceiver
        InterestAlarmScheduler.scheduleNext(context)
        SubscriptionAlarmScheduler.scheduleNext(context)
        PaymentReminderScheduler.scheduleNext(context)
    }
}

/** Re-schedules the next Moscow midnight after reboot and after every run. */
class InterestBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                InterestAlarmScheduler.scheduleNext(context)
                SubscriptionAlarmScheduler.scheduleNext(context)
                PaymentReminderScheduler.scheduleNext(context)
            }
        }
    }
}

object InterestAlarmScheduler {
    fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, InterestAlarmReceiver::class.java).setAction(ACTION_DAILY_INTEREST)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextMidnight = DailyInterest.startOfMoscowDay(System.currentTimeMillis()) + 24L * 60L * 60L * 1000L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMidnight, pending)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMidnight, pending)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, nextMidnight, pending)
        }
    }
}
