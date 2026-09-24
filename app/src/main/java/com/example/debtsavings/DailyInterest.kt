package com.example.debtsavings

import android.content.SharedPreferences
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Ежедневное начисление + авто-учёт мин. платежа.
 *
 * Grace (graceMode=true):
 *   — проценты на тело НЕ начисляются;
 *   — в конце каждого календарного месяца (МСК) начисляется комиссия
 *     [WbCreditMath.graceCommission] в accruedFees (не в тело).
 *
 * Вне грейса:
 *   — простые % actual/365: accruedInterest += principal * rate/100/365 * days
 *   — тело не увеличивается.
 *
 * Авто-мин. платёж (autoApplyMinPayment):
 *   если сегодня >= nextMinPaymentDue и период ещё не учтён —
 *   списываем calculateMinPayment() в порядке комиссии→%→тело,
 *   пишем транзакцию с разбивкой, двигаем срок.
 */
object DailyInterest {
    private const val MOSCOW_TZ = "Europe/Moscow"
    private const val SAVINGS_RATE_KEY = "savings_rate"
    private const val SAVINGS_LAST_DATE_KEY = "savings_last_interest_date"

    fun applyIfNeeded(
        prefs: SharedPreferences,
        transactions: MutableList<Transaction>,
        credits: MutableList<Credit>
    ): Boolean {
        val today = startOfMoscowDay(System.currentTimeMillis())
        var changed = false

        for (credit in credits) {
            if (credit.lastInterestDate == 0L) {
                credit.lastInterestDate = today
                if (credit.contractStartDate == 0L) {
                    credit.contractStartDate = today
                }
                ensureNextDue(credit, today)
                changed = true
                continue
            }

            val days = TimeUnit.MILLISECONDS.toDays(today - credit.lastInterestDate).toInt()
            if (days > 0) {
                if (accrueAcrossDays(credit, transactions, credit.lastInterestDate, today)) changed = true
                credit.lastInterestDate = today
                changed = true
            }

            if (autoApplyMinIfDue(credit, transactions, today)) {
                changed = true
            }
        }

        // Savings: daily compound on balance (unchanged behaviour).
        val savingsRate = prefs.getFloat(SAVINGS_RATE_KEY, 0f).toDouble()
        val savingsLastDate = prefs.getLong(SAVINGS_LAST_DATE_KEY, 0L)
        if (savingsLastDate == 0L) {
            prefs.edit().putLong(SAVINGS_LAST_DATE_KEY, today).apply()
        } else {
            val days = TimeUnit.MILLISECONDS.toDays(today - savingsLastDate).toInt()
            if (days > 0) {
                val savingsBalance = transactions.sumOf { it.amount }.coerceAtLeast(0.0)
                if (savingsRate > 0.0 && savingsBalance > 0.0) {
                    val interest = savingsBalance * (savingsRate / 100.0 / 365.0) * days
                    if (interest > 0.0) {
                        transactions.add(
                            0,
                            Transaction(
                                UUID.randomUUID().toString(),
                                interest,
                                today,
                                "Проценты накопительного счёта за $days дн.",
                                null
                            )
                        )
                        changed = true
                    }
                }
                prefs.edit().putLong(SAVINGS_LAST_DATE_KEY, today).apply()
            }
        }

        if (changed) {
            Persistence.saveTransactions(prefs, transactions)
            Persistence.saveCredits(prefs, credits)
        }
        return changed
    }

    /**
     * Проходим по календарным дням от (lastExclusive, todayInclusive],
     * на границе месяца начисляем grace-комиссию; вне грейса — дневные %.
     */
    private fun accrueAcrossDays(
        credit: Credit,
        transactions: MutableList<Transaction>,
        fromDay: Long,
        today: Long
    ): Boolean {
        var changed = false
        var cursor = fromDay
        var interestDays = 0

        while (cursor < today) {
            val next = cursor + TimeUnit.DAYS.toMillis(1)
            // Комиссия грейса: в первый день нового месяца за прошедший месяц.
            if (isFirstDayOfMonth(next) && credit.graceMode && credit.principal > 0.0) {
                val billingYm = WbCreditMath.yearMonthOf(cursor) // месяц, который закончился
                if (billingYm > credit.lastFeeYearMonth) {
                    if (!WbCreditMath.shouldSkipFirstMonthCommission(credit.contractStartDate, billingYm)) {
                        val fee = WbCreditMath.graceCommission(credit.principal)
                        if (fee > 0.0) {
                            credit.accruedFees += fee
                            transactions.add(
                                0,
                                Transaction(
                                    UUID.randomUUID().toString(),
                                    -fee,
                                    next,
                                    "Комиссия грейса: ${credit.name} (${billingYm / 100}-${billingYm % 100})",
                                    credit.id
                                )
                            )
                            changed = true
                        }
                    }
                    credit.lastFeeYearMonth = billingYm
                    // Срок оплаты за расчётный месяц = конец месяца + paymentDay дней
                    credit.nextMinPaymentDue = WbCreditMath.dueDateAfterBillingMonth(
                        billingYm,
                        credit.paymentDay
                    )
                    changed = true
                }
            }
            if (!credit.graceMode && credit.principal > 0.0 && credit.rate > 0.0) {
                interestDays++
            }
            cursor = next
        }

        if (interestDays > 0) {
            val interest = credit.principal * (credit.rate / 100.0 / 365.0) * interestDays
            if (interest > 0.0) {
                credit.accruedInterest += interest
                transactions.add(
                    0,
                    Transaction(
                        UUID.randomUUID().toString(),
                        -interest,
                        today,
                        "Проценты: ${credit.name} за $interestDays дн. (начислены отдельно от тела)",
                        credit.id
                    )
                )
                changed = true
            }
        }
        return changed
    }

    private fun autoApplyMinIfDue(
        credit: Credit,
        transactions: MutableList<Transaction>,
        today: Long
    ): Boolean {
        if (!credit.autoApplyMinPayment) return false
        if (credit.nextMinPaymentDue <= 0L) {
            ensureNextDue(credit, today)
            return false
        }
        if (today < startOfMoscowDay(credit.nextMinPaymentDue)) return false

        // Период = расчётный месяц, за который наступил срок.
        val dueCal = Calendar.getInstance(TimeZone.getTimeZone(MOSCOW_TZ))
        dueCal.timeInMillis = credit.nextMinPaymentDue
        // Расчётный месяц ≈ месяц due минус paymentDay дней (конец billing month).
        dueCal.add(Calendar.DAY_OF_MONTH, -credit.paymentDay.coerceIn(1, 28))
        val periodKey = dueCal.get(Calendar.YEAR) * 100 + (dueCal.get(Calendar.MONTH) + 1)

        if (periodKey == credit.lastMinPaymentPeriodKey) return false

        val min = WbCreditMath.calculateMinPayment(credit)
        if (min.total <= 0.0) {
            credit.lastMinPaymentPeriodKey = periodKey
            advanceDue(credit, periodKey)
            return true
        }

        val allocated = WbCreditMath.allocatePayment(
            min.total,
            credit.accruedFees,
            credit.accruedInterest,
            credit.principal
        )
        WbCreditMath.applyBreakdown(credit, allocated)

        val note = "Авто мин. платёж: ${credit.name} · " +
            "комиссии ${fmt(allocated.fees)} · % ${fmt(allocated.interest)} · тело ${fmt(allocated.principal)}"
        transactions.add(
            0,
            Transaction(UUID.randomUUID().toString(), allocated.total, today, note, credit.id)
        )

        credit.lastMinPaymentPeriodKey = periodKey
        // monthlyPayment как ориентир обновляем
        credit.monthlyPayment = min.total
        advanceDue(credit, periodKey)
        return true
    }

    private fun advanceDue(credit: Credit, appliedPeriodKey: Int) {
        val nextPeriod = WbCreditMath.nextYearMonth(appliedPeriodKey)
        credit.nextMinPaymentDue = WbCreditMath.dueDateAfterBillingMonth(nextPeriod, credit.paymentDay)
    }

    private fun ensureNextDue(credit: Credit, today: Long) {
        if (credit.nextMinPaymentDue > 0L) return
        val ym = WbCreditMath.yearMonthOf(today)
        credit.nextMinPaymentDue = WbCreditMath.dueDateAfterBillingMonth(ym, credit.paymentDay)
    }

    private fun isFirstDayOfMonth(dayStartMillis: Long): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(MOSCOW_TZ))
        cal.timeInMillis = dayStartMillis
        return cal.get(Calendar.DAY_OF_MONTH) == 1
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale("ru", "RU"), "%.2f ₽", v)

    fun startOfMoscowDay(millis: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(MOSCOW_TZ))
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
