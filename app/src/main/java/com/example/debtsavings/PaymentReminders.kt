package com.example.debtsavings

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private const val ACTION_PAYMENT_REMINDER = "com.example.debtsavings.PAYMENT_REMINDER"
private const val REQUEST_CODE_PAY = 7201
private const val CHANNEL_ID = "payments"
private const val PREF_FIRED = "pay_remind_fired"
/** Local-wall-clock hours for the 3 daily reminders. */
private val SLOT_HOURS = intArrayOf(9, 14, 20)
/** Remind on local calendar days with daysLeft in [MIN_DAYS, MAX_DAYS] inclusive. */
private const val MIN_DAYS = 0
private const val MAX_DAYS = 3

/**
 * Напоминания о мин. платежах кредитов и подписках:
 * за 3 дня до срока (и в день оплаты) — 3 раза в день (09:00 / 14:00 / 20:00)
 * по **локальному** времени устройства.
 */
object PaymentReminderScheduler {
    fun scheduleNext(context: Context) {
        if (!NotificationPreferences.isEnabled(context)) {
            cancel(context)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val (trigger, hour) = nextSlot(now)
        val intent = Intent(context, PaymentReminderReceiver::class.java)
            .setAction(ACTION_PAYMENT_REMINDER)
            .putExtra("slotHour", hour)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_PAY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, PaymentReminderReceiver::class.java).setAction(ACTION_PAYMENT_REMINDER)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_PAY,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    /** Next 09/14/20 in device local timezone strictly after [now]. */
    fun nextSlot(now: Long): Pair<Long, Int> {
        val base = Calendar.getInstance(TimeZone.getDefault())
        base.timeInMillis = now
        for (dayOffset in 0..1) {
            val day = base.clone() as Calendar
            if (dayOffset > 0) day.add(Calendar.DAY_OF_MONTH, dayOffset)
            for (h in SLOT_HOURS) {
                val candidate = slotOn(day.clone() as Calendar, h)
                if (candidate > now) return candidate to h
            }
        }
        val tomorrow = base.clone() as Calendar
        tomorrow.add(Calendar.DAY_OF_MONTH, 1)
        return slotOn(tomorrow, SLOT_HOURS[0]) to SLOT_HOURS[0]
    }

    private fun slotOn(cal: Calendar, hour: Int): Long {
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun currentSlotHour(now: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = now
        val h = cal.get(Calendar.HOUR_OF_DAY)
        var best = SLOT_HOURS[0]
        for (s in SLOT_HOURS) {
            if (s <= h) best = s
        }
        return best
    }
}

class PaymentReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PAYMENT_REMINDER) return
        val slotHour = intent.getIntExtra("slotHour", PaymentReminderScheduler.currentSlotHour())
        if (!NotificationPreferences.isEnabled(context)) return
        PaymentReminders.checkAndNotify(context, slotHour)
        // Early-day threshold check for subscriptions (day 7 etc.) still useful once per morning
        if (slotHour == SLOT_HOURS[0]) {
            SubscriptionNotifier.checkAndNotify(context)
        }
        PaymentReminderScheduler.scheduleNext(context)
    }
}

object PaymentReminders {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Платежи", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Напоминания о мин. платежах и подписках (3 раза в день за 3 дня)" }
            )
        }
    }

    fun checkAndNotify(context: Context, slotHour: Int = PaymentReminderScheduler.currentSlotHour()) {
        ensureChannel(context)
        if (!NotificationPreferences.isEnabled(context)) return

        val prefs = context.getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        val credits = mutableListOf<Credit>()
        val subs = mutableListOf<Subscription>()
        Persistence.loadCredits(prefs, credits)
        Persistence.loadSubscriptions(prefs, subs)

        val now = System.currentTimeMillis()
        val today = startOfLocalDay(now)
        val ymd = localYmd(today)
        val fired = prefs.getStringSet(PREF_FIRED, emptySet())?.toMutableSet() ?: mutableSetOf()
        var changed = false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        for (c in credits) {
            val due = resolveCreditDue(c) ?: continue
            val dueDay = startOfLocalDay(due)
            val daysLeft = TimeUnit.MILLISECONDS.toDays(dueDay - today).toInt()
            if (daysLeft !in MIN_DAYS..MAX_DAYS) continue
            val amount = WbCreditMath.calculateMinPayment(c).total
            if (amount < 0.01 && c.principal < 0.01 && c.accruedInterest < 0.01 && c.accruedFees < 0.01) continue

            val key = "credit:${c.id}:$dueDay:$ymd:$slotHour"
            if (key in fired) continue

            val title = when (daysLeft) {
                0 -> "Сегодня мин. платёж: ${c.name}"
                1 -> "Завтра мин. платёж: ${c.name}"
                else -> "Через $daysLeft дн. мин. платёж: ${c.name}"
            }
            val body = buildString {
                append(fmt(amount))
                append(" ₽")
                if (daysLeft == 0) append(" — срок сегодня")
                else append(" · осталось $daysLeft дн.")
                append(" · ${slotLabel(slotHour)}")
            }
            val open = Intent(context, CreditCalculatorActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, ("c:" + c.id).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notifId = ("pay-c-${c.id}-$ymd-$slotHour").hashCode() and 0x7FFFFFFF
            nm.notify(
                notifId,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
            fired.add(key)
            changed = true
        }

        for (s in subs) {
            if (!s.enabled || s.nextChargeDate <= 0L) continue
            val dueDay = startOfLocalDay(s.nextChargeDate)
            val daysLeft = TimeUnit.MILLISECONDS.toDays(dueDay - today).toInt()
            if (daysLeft !in MIN_DAYS..MAX_DAYS) continue

            val key = "sub:${s.id}:$dueDay:$ymd:$slotHour"
            if (key in fired) continue

            val catLabel = SubscriptionPresets.label(s.category)
            val title = when (s.intent) {
                SubscriptionIntent.NEED_PAY -> when (daysLeft) {
                    0 -> "Сегодня оплатить: ${s.name}"
                    1 -> "Завтра оплатить: ${s.name}"
                    else -> "Через $daysLeft дн. оплатить: ${s.name}"
                }
                SubscriptionIntent.NEED_CANCEL -> when (daysLeft) {
                    0 -> "Сегодня отключить: ${s.name}"
                    1 -> "Завтра отключить: ${s.name}"
                    else -> "Через $daysLeft дн. отключить: ${s.name}"
                }
            }
            val body = when (s.intent) {
                SubscriptionIntent.NEED_PAY ->
                    "[$catLabel] ${fmt(s.price)} ₽ · осталось $daysLeft дн. · ${slotLabel(slotHour)}"
                SubscriptionIntent.NEED_CANCEL ->
                    "[$catLabel] Отключите до списания ${fmt(s.price)} ₽ · осталось $daysLeft дн. · ${slotLabel(slotHour)}"
            }
            val open = Intent(context, SubscriptionsActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, ("s:" + s.id).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notifId = ("pay-s-${s.id}-$ymd-$slotHour").hashCode() and 0x7FFFFFFF
            nm.notify(
                notifId,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body + if (s.notes.isNotBlank()) "\n${s.notes}" else ""))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
            fired.add(key)
            changed = true
        }

        if (changed) {
            val keep = if (fired.size > 400) fired.toList().takeLast(200).toSet() else fired
            prefs.edit().putStringSet(PREF_FIRED, keep).apply()
        }
    }

    /** Prefer stored nextMinPaymentDue; fall back to paymentDay this/next local month. */
    private fun resolveCreditDue(c: Credit): Long? {
        if (c.nextMinPaymentDue > 0L) return c.nextMinPaymentDue
        if (c.monthlyPayment <= 0.01 && !c.autoApplyMinPayment && c.principal <= 0.01) return null
        val cal = Calendar.getInstance(TimeZone.getDefault())
        val day = c.paymentDay.coerceIn(1, 28)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(maxDay))
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (startOfLocalDay(cal.timeInMillis) < startOfLocalDay(System.currentTimeMillis())) {
            cal.add(Calendar.MONTH, 1)
            val md = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(md))
        }
        return cal.timeInMillis
    }

    fun startOfLocalDay(millis: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun localYmd(dayStart: Long): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = dayStart
        return "%04d%02d%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun slotLabel(hour: Int): String = when (hour) {
        9 -> "утро"
        14 -> "день"
        20 -> "вечер"
        else -> "%02d:00".format(hour)
    }

    private fun fmt(v: Double): String =
        String.format(java.util.Locale("ru", "RU"), "%.0f", v)
}
