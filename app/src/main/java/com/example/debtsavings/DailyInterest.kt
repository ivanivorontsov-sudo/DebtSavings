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
 * Авто-платёж (autoApplyMinPayment):
 *   если сегодня >= nextMinPaymentDue и период ещё не учтён —
 *   списываем monthlyPayment (разбивка комиссии→%→тело),
 *   пишем транзакцию, двигаем срок. Сумму задаёт пользователь;
 *   для аннуитета (endDate) после платежа monthlyPayment пересчитывается ([Annuity]).
 *
 * Аннуитет (endDate): досрочный платёж сразу уменьшает тело, поэтому % за следующие дни
 * начисляются уже на новое тело; начисленные % копятся отдельно и списываются только
 * обязательным платежом в плановую дату (nextMinPaymentDue), затем платёж пересчитывается.
 *
 * Грейс (graceMode) — только ручной переключатель в диалоге кредита;
 * платежи (в т.ч. авто-мин.) грейс НЕ срывают.
 */
object DailyInterest {
    private const val MOSCOW_TZ = "Europe/Moscow"
    private const val SAVINGS_RATE_KEY = "savings_rate"
    private const val SAVINGS_LAST_DATE_KEY = "savings_last_interest_date"

    fun applyIfNeeded(
        prefs: SharedPreferences,
        transactions: MutableList<Transaction>,
        credits: MutableList<Credit>
    ): Boolean = applyIfNeeded(prefs, transactions, credits, System.currentTimeMillis())

    /** [now] — для тестов; в приложении — текущее время. */
    fun applyIfNeeded(
        prefs: SharedPreferences,
        transactions: MutableList<Transaction>,
        credits: MutableList<Credit>,
        now: Long
    ): Boolean {
        val today = startOfMoscowDay(now)
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
                var from = credit.lastInterestDate
                // Аннуитет: пропущенные плановые даты — начисляем % до даты платежа, списываем
                // обязательный платёж в эту дату, дальше % идут уже на уменьшенное тело.
                if (Annuity.hasSchedule(credit) && credit.autoApplyMinPayment) {
                    var guard = 0
                    while (guard++ < 240 && credit.nextMinPaymentDue > 0L) {
                        val due = startOfMoscowDay(credit.nextMinPaymentDue)
                        if (due < from || due > today) break
                        if (accrueAcrossDays(credit, transactions, from, due)) changed = true
                        from = due
                        credit.lastInterestDate = due
                        if (!autoApplyMinIfDue(credit, transactions, due)) break
                        changed = true
                    }
                }
                // % за дни — на текущее тело (после досрочек уже уменьшенное)
                if (accrueAcrossDays(credit, transactions, from, today)) changed = true
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
                val savingsBalance = transactions.filter { it.creditId == null }.sumOf { it.amount }.coerceAtLeast(0.0)
                if (savingsRate > 0.0 && savingsBalance > 0.0) {
                    val dailyFactor = 1.0 + savingsRate / 100.0 / 365.0
                    val interest = savingsBalance * (Math.pow(dailyFactor, days.toDouble()) - 1.0)
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
                    if (!Annuity.hasSchedule(credit)) {
                        credit.nextMinPaymentDue = WbCreditMath.dueDateAfterBillingMonth(
                            billingYm,
                            credit.paymentDay
                        )
                    }
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
        val periodKey = if (Annuity.hasSchedule(credit)) {
            Annuity.periodKeyForDue(credit.nextMinPaymentDue)
        } else {
            val dueCal = Calendar.getInstance(TimeZone.getTimeZone(MOSCOW_TZ))
            dueCal.timeInMillis = credit.nextMinPaymentDue
            // Расчётный месяц ≈ месяц due минус paymentDay дней (конец billing month).
            dueCal.add(Calendar.DAY_OF_MONTH, -credit.paymentDay.coerceIn(1, 28))
            dueCal.get(Calendar.YEAR) * 100 + (dueCal.get(Calendar.MONTH) + 1)
        }

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

        credit.lastMinPaymentPeriodKey = periodKey
        // monthlyPayment — сумма пользователя; для аннуитета (endDate) пересчитывается по новому телу
        advanceDue(credit, periodKey)
        val recalculated = allocated.principal > 0.0 && Annuity.recompute(credit, today)
        val money: (Double) -> String = { String.format(java.util.Locale("ru", "RU"), "%.2f", it) }
        val note = "Авто-отметка в журнале (не платёж банку): ${credit.name} · " +
            WbCreditMath.formatAllocationRu(allocated, money) +
            (if (recalculated) " · новый платёж ${money(credit.monthlyPayment)} ₽" else "")
        transactions.add(
            0,
            Transaction(UUID.randomUUID().toString(), allocated.total, today, note, credit.id)
        )
        return true
    }

    private fun advanceDue(credit: Credit, appliedPeriodKey: Int) {
        if (Annuity.hasSchedule(credit)) {
            credit.nextMinPaymentDue = Annuity.advanceDue(credit, credit.nextMinPaymentDue)
            return
        }
        val nextPeriod = WbCreditMath.nextYearMonth(appliedPeriodKey)
        credit.nextMinPaymentDue = WbCreditMath.dueDateAfterBillingMonth(nextPeriod, credit.paymentDay)
    }

    private fun ensureNextDue(credit: Credit, today: Long) {
        if (credit.nextMinPaymentDue > 0L) return
        if (Annuity.hasSchedule(credit)) {
            credit.nextMinPaymentDue = Annuity.nextScheduledOnOrAfter(today, credit.paymentDay)
            return
        }
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
