package com.example.debtsavings

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

private const val ACTION_SUB_CHECK = "com.example.debtsavings.SUBSCRIPTION_CHECK"
private const val REQUEST_CODE_SUB = 7101
private const val CHANNEL_ID = "subscriptions"
private const val PREF_FIRED = "sub_notify_fired"

object SubscriptionAlarmScheduler {
    fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, SubscriptionAlarmReceiver::class.java).setAction(ACTION_SUB_CHECK)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_SUB,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val next = DailyInterest.startOfMoscowDay(System.currentTimeMillis()) + 9L * 60L * 60L * 1000L
        val trigger = if (next <= System.currentTimeMillis()) next + 24L * 60L * 60L * 1000L else next

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }
}

class SubscriptionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SUB_CHECK) return
        SubscriptionNotifier.checkAndNotify(context)
        SubscriptionAlarmScheduler.scheduleNext(context)
    }
}

object SubscriptionNotifier {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Подписки", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Напоминания об оплате или отключении подписок" }
            )
        }
    }

    fun checkAndNotify(context: Context) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!ok) return
        }

        val prefs = context.getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        val items = mutableListOf<Subscription>()
        Persistence.loadSubscriptions(prefs, items)
        val today = DailyInterest.startOfMoscowDay(System.currentTimeMillis())
        val fired = prefs.getStringSet(PREF_FIRED, emptySet())?.toMutableSet() ?: mutableSetOf()
        var changed = false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        for (s in items) {
            if (!s.enabled || s.nextChargeDate <= 0L) continue
            val chargeDay = DailyInterest.startOfMoscowDay(s.nextChargeDate)
            val daysLeft = TimeUnit.MILLISECONDS.toDays(chargeDay - today).toInt()
            if (daysLeft < 0) continue
            // Days 0..3: PaymentReminders sends 3×/day; here only earlier thresholds (e.g. 7).
            if (daysLeft in 0..3) continue
            val thresholds = s.notifyDays.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it >= 0 }
                .ifEmpty { listOf(7, 3, 1) }
            if (daysLeft !in thresholds) continue

            val key = "${s.id}:$daysLeft:$chargeDay"
            if (key in fired) continue

            val catLabel = SubscriptionPresets.label(s.category)
            val title = when (s.intent) {
                SubscriptionIntent.NEED_PAY -> "Надо оплатить: ${s.name}"
                SubscriptionIntent.NEED_CANCEL ->
                    if (s.category == SubscriptionCategory.DEBT_INSURANCE)
                        "Отключите страховку: ${s.name}"
                    else "Надо отключить: ${s.name}"
            }
            val body = when (s.intent) {
                SubscriptionIntent.NEED_PAY -> when {
                    daysLeft == 0 -> "[$catLabel] Сегодня списание ${fmt(s.price)} ₽"
                    daysLeft == 1 -> "[$catLabel] Завтра списание ${fmt(s.price)} ₽"
                    else -> "[$catLabel] Через $daysLeft дн. списание ${fmt(s.price)} ₽"
                }
                SubscriptionIntent.NEED_CANCEL -> {
                    val stress = if (s.category == SubscriptionCategory.DEBT_INSURANCE)
                        "Отмените страховку до списания"
                    else "Отключите до продления"
                    when {
                        daysLeft == 0 -> "[$catLabel] Сегодня продление — $stress (${fmt(s.price)} ₽)"
                        daysLeft == 1 -> "[$catLabel] Завтра — $stress ${fmt(s.price)} ₽"
                        else -> "[$catLabel] Через $daysLeft дн. — $stress ${fmt(s.price)} ₽"
                    }
                }
            }

            val open = Intent(context, SubscriptionsActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, s.id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = body + if (s.notes.isNotBlank()) "\n${s.notes}" else ""
            nm.notify(
                s.id.hashCode() and 0x7FFFFFFF,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
            fired.add(key)
            changed = true
        }

        if (changed) {
            val keep = if (fired.size > 200) fired.toList().takeLast(100).toSet() else fired
            prefs.edit().putStringSet(PREF_FIRED, keep).apply()
        }
    }

    private fun fmt(v: Double): String =
        String.format(java.util.Locale("ru", "RU"), "%.0f", v)
}
