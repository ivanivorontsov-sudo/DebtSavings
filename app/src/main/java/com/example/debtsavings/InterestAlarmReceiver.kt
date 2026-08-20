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

        val prefs = context.getSharedPreferences("debt_savings_prefs", Context.MODE_PRIVATE)
        val transactions = mutableListOf<Transaction>()
        val credits = mutableListOf<Credit>()
        Persistence.loadTransactions(prefs, transactions)
        Persistence.loadCredits(prefs, credits)

        DailyInterest.applyIfNeeded(prefs, transactions, credits)
        InterestAlarmScheduler.scheduleNext(context)
    }
}

/** Re-schedules the next Moscow midnight after reboot and after every run. */
class InterestBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> InterestAlarmScheduler.scheduleNext(context)
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
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextMidnight,
                pending
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Fallback when the user has not granted exact-alarm access. The app still
            // catches up correctly from the stored Moscow date on its next execution.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextMidnight,
                pending
            )
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, nextMidnight, pending)
        }
    }
}

/** Shared JSON loading used by the background receiver. */
object Persistence {
    fun loadTransactions(prefs: android.content.SharedPreferences, target: MutableList<Transaction>) {
        target.clear()
        val json = prefs.getString("transactions", "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                target.add(
                    Transaction(
                        id = o.getString("id"),
                        amount = o.getDouble("amount"),
                        timestamp = o.getLong("timestamp"),
                        note = o.optString("note", ""),
                        creditId = if (o.has("creditId")) o.getString("creditId") else null
                    )
                )
            }
        } catch (_: Exception) {
            // Keep an empty journal if old/corrupt data cannot be parsed.
        }
    }

    fun loadCredits(prefs: android.content.SharedPreferences, target: MutableList<Credit>) {
        target.clear()
        val json = prefs.getString("credits", "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                target.add(
                    Credit(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        rate = o.getDouble("rate"),
                        termMonths = o.getInt("termMonths"),
                        monthlyPayment = o.getDouble("monthlyPayment"),
                        principal = o.getDouble("principal"),
                        lastInterestDate = o.optLong("lastInterestDate", 0L)
                    )
                )
            }
        } catch (_: Exception) {
            // Keep an empty credit list if old/corrupt data cannot be parsed.
        }
    }
}
