package com.example.debtsavings

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Кредиты по графику (аннуитет), как WB Банк «Вб_1» / «Вб_2».
 *
 * Кредит «по графику», если задана [Credit.endDate] (дата окончания) и [Credit.paymentDay].
 *
 * Правило банка (проверено по выписке 25.09.2026):
 *  — платёж НЕ в плановый день (досрочный) целиком идёт в тело;
 *    начисленные % / комиссии остаются и списываются плановым платежом;
 *  — платёж в плановый день (или просроченный) — как раньше: комиссии → % → тело;
 *  — после изменения тела ежемесячный платёж пересчитывается:
 *      payment = P·r / (1 − (1+r)^−n),  r = ставка/12,
 *      n = число плановых дат от ближайшей даты платежа до даты окончания включительно;
 *    округление до копеек HALF_UP.
 *
 * Все даты — по Москве (как начисление %).
 */
object Annuity {
    private val MOSCOW: TimeZone = TimeZone.getTimeZone("Europe/Moscow")

    fun hasSchedule(c: Credit): Boolean = c.endDate > 0L

    fun recalcEnabled(c: Credit): Boolean = hasSchedule(c) && c.recalcPaymentAfterPrepay

    /** Аннуитетный платёж, округлённый до копеек (HALF_UP). */
    fun payment(principal: Double, annualRatePercent: Double, n: Int): Double {
        if (principal <= 0.0 || n <= 0) return 0.0
        val r = annualRatePercent / 100.0 / 12.0
        val raw = if (r <= 0.0) principal / n else principal * r / (1.0 - (1.0 + r).pow(-n))
        return round2(raw)
    }

    fun round2(v: Double): Double =
        BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()

    /** Число плановых платежей: месяцы от [nextDue] до [endDate] включительно (минимум 1). */
    fun remainingPayments(nextDue: Long, endDate: Long): Int {
        val a = cal(nextDue)
        val b = cal(endDate)
        val diff = (b.get(Calendar.YEAR) * 12 + b.get(Calendar.MONTH)) -
            (a.get(Calendar.YEAR) * 12 + a.get(Calendar.MONTH))
        return (diff + 1).coerceAtLeast(1)
    }

    /** Плановая дата в месяце [year]/[month0] (0-based): день платежа, не больше длины месяца. */
    fun scheduledDate(year: Int, month0: Int, paymentDay: Int): Long {
        val c = Calendar.getInstance(MOSCOW)
        c.clear()
        c.set(Calendar.YEAR, year)
        c.set(Calendar.MONTH, month0)
        c.set(Calendar.DAY_OF_MONTH, 1)
        val max = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        c.set(Calendar.DAY_OF_MONTH, paymentDay.coerceIn(1, 31).coerceAtMost(max))
        return c.timeInMillis
    }

    /** Первая плановая дата >= начала дня [fromMillis] (МСК). */
    fun nextScheduledOnOrAfter(fromMillis: Long, paymentDay: Int): Long {
        val today = DailyInterest.startOfMoscowDay(fromMillis)
        val c = cal(today)
        var d = scheduledDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH), paymentDay)
        if (d < today) {
            c.add(Calendar.MONTH, 1)
            d = scheduledDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH), paymentDay)
        }
        return d
    }

    /** Плановая дата в следующем месяце после [dueMillis]. */
    fun nextMonthScheduled(dueMillis: Long, paymentDay: Int): Long {
        val c = cal(dueMillis)
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, 1)
        return scheduledDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH), paymentDay)
    }

    /** Расчётный период (YYYYMM) для срока [due] — месяц перед месяцем срока (как у WB-кредитов). */
    fun periodKeyForDue(due: Long): Int {
        val c = cal(due)
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, -1)
        return c.get(Calendar.YEAR) * 100 + (c.get(Calendar.MONTH) + 1)
    }

    /** Следующий плановый срок после [due]; последний платёж — в дату окончания. */
    fun advanceDue(c: Credit, due: Long): Long {
        val next = nextMonthScheduled(due, c.paymentDay)
        val end = DailyInterest.startOfMoscowDay(c.endDate)
        return if (c.endDate > 0L && end > DailyInterest.startOfMoscowDay(due) && next > end) end else next
    }

    /**
     * Ближайшая дата планового платежа для расчёта n:
     * сохранённый [Credit.nextMinPaymentDue], если он сегодня или позже, иначе — ближайшая по дню платежа.
     */
    fun effectiveNextDue(c: Credit, now: Long): Long {
        val today = DailyInterest.startOfMoscowDay(now)
        if (c.nextMinPaymentDue > 0L && DailyInterest.startOfMoscowDay(c.nextMinPaymentDue) >= today) {
            return DailyInterest.startOfMoscowDay(c.nextMinPaymentDue)
        }
        return nextScheduledOnOrAfter(now, c.paymentDay)
    }

    /** Будущий срок платежа выравниваем на день платежа (в том же месяце). Прошедший не трогаем (авто-платёж). */
    fun alignNextDue(c: Credit, now: Long) {
        if (!hasSchedule(c)) return
        val today = DailyInterest.startOfMoscowDay(now)
        if (c.nextMinPaymentDue <= 0L) {
            c.nextMinPaymentDue = nextScheduledOnOrAfter(now, c.paymentDay)
            return
        }
        val due = DailyInterest.startOfMoscowDay(c.nextMinPaymentDue)
        if (due < today) return
        val dc = cal(due)
        val aligned = scheduledDate(dc.get(Calendar.YEAR), dc.get(Calendar.MONTH), c.paymentDay)
        c.nextMinPaymentDue = if (aligned >= today) aligned else nextScheduledOnOrAfter(now, c.paymentDay)
    }

    /** Платёж, который получится при текущем теле (без изменения кредита). 0 — если не аннуитет. */
    fun computePayment(c: Credit, now: Long): Double {
        if (!hasSchedule(c)) return 0.0
        val n = remainingPayments(effectiveNextDue(c, now), c.endDate)
        return payment(c.principal, c.rate, n)
    }

    /**
     * Пересчёт [Credit.monthlyPayment] для аннуитета (если включён пересчёт).
     * @return true, если платёж изменился
     */
    fun recompute(c: Credit, now: Long = System.currentTimeMillis()): Boolean {
        if (!recalcEnabled(c)) return false
        alignNextDue(c, now)
        val newPay = if (c.principal > 0.01) computePayment(c, now) else 0.0
        if (abs(newPay - c.monthlyPayment) < 0.005) return false
        c.monthlyPayment = newPay
        return true
    }

    /**
     * Плановый ли сегодня платёж: сегодня (МСК) наступил срок [Credit.nextMinPaymentDue] (или просрочен).
     * Если срок не задан — сегодня день платежа / дата окончания.
     * Для кредитов без графика — всегда true (старое правило комиссии → % → тело).
     */
    fun isScheduledPaymentDay(c: Credit, now: Long): Boolean {
        if (!hasSchedule(c)) return true
        val today = DailyInterest.startOfMoscowDay(now)
        if (c.nextMinPaymentDue > 0L) {
            return today >= DailyInterest.startOfMoscowDay(c.nextMinPaymentDue)
        }
        if (today == DailyInterest.startOfMoscowDay(c.endDate)) return true
        val tc = cal(today)
        return today == scheduledDate(tc.get(Calendar.YEAR), tc.get(Calendar.MONTH), c.paymentDay)
    }

    /** Досрочный платёж: сначала тело, остаток (если тело закрыто) — комиссии → %. */
    fun allocateEarly(amount: Double, fees: Double, interest: Double, principal: Double): PaymentBreakdown {
        var left = amount.coerceAtLeast(0.0)
        val toPrincipal = min(left, principal.coerceAtLeast(0.0))
        left -= toPrincipal
        val toFees = min(left, fees.coerceAtLeast(0.0))
        left -= toFees
        val toInterest = min(left, interest.coerceAtLeast(0.0))
        return PaymentBreakdown(fees = toFees, interest = toInterest, principal = toPrincipal)
    }

    data class PaymentResult(
        val breakdown: PaymentBreakdown,
        val early: Boolean,
        val note: String,
        val newMonthlyPayment: Double?
    )

    /**
     * Внести платёж по кредиту (мутирует кредит). [early]: null — по дате автоматически.
     * Не трогает грейс (грейс — только вручную).
     */
    fun applyPayment(
        c: Credit,
        amount: Double,
        early: Boolean?,
        now: Long,
        money: (Double) -> String
    ): PaymentResult {
        val isEarly = hasSchedule(c) && (early ?: !isScheduledPaymentDay(c, now))
        val b = if (isEarly) allocateEarly(amount, c.accruedFees, c.accruedInterest, c.principal)
        else WbCreditMath.allocatePayment(amount, c.accruedFees, c.accruedInterest, c.principal)
        WbCreditMath.applyBreakdown(c, b)
        // Плановый платёж вручную без авто-учёта: закрываем период и двигаем срок
        if (!isEarly && hasSchedule(c) && !c.autoApplyMinPayment && c.nextMinPaymentDue > 0L &&
            DailyInterest.startOfMoscowDay(now) >= DailyInterest.startOfMoscowDay(c.nextMinPaymentDue)
        ) {
            c.lastMinPaymentPeriodKey = periodKeyForDue(c.nextMinPaymentDue)
            c.nextMinPaymentDue = advanceDue(c, c.nextMinPaymentDue)
        }
        val changed = b.principal > 0.0 && recompute(c, now)
        val alloc = WbCreditMath.formatAllocationRu(b, money)
        val head = if (isEarly) "Досрочный платёж: всё в тело · ${c.name}" else "Платёж: ${c.name}"
        val tail = if (changed) " · новый платёж ${money(c.monthlyPayment)} ₽" else ""
        return PaymentResult(b, isEarly, "$head · $alloc$tail", if (changed) c.monthlyPayment else null)
    }

    /**
     * Откат операции журнала для кредита (удаление / «Отменить»).
     * Корректировки: amount = −delta → откат = поле + amount.
     */
    fun reverseEffect(credits: List<Credit>, tx: Transaction, now: Long = System.currentTimeMillis()) {
        val credit = tx.creditId?.let { id -> credits.find { it.id == id } } ?: return
        val note = tx.note
        val oldPrincipal = credit.principal
        when {
            note.startsWith("Проценты:") || note.contains("начислены отдельно") -> {
                credit.accruedInterest = (credit.accruedInterest + tx.amount).coerceAtLeast(0.0)
            }
            note.startsWith("Комиссия грейса") -> {
                credit.accruedFees = (credit.accruedFees + tx.amount).coerceAtLeast(0.0)
            }
            note.startsWith("Корректировка %") || note.startsWith("Начисл. %") -> {
                credit.accruedInterest = (credit.accruedInterest + tx.amount).coerceAtLeast(0.0)
            }
            note.startsWith("Корректировка комиссий") || note.startsWith("Комиссии (старт)") -> {
                credit.accruedFees = (credit.accruedFees + tx.amount).coerceAtLeast(0.0)
            }
            tx.amount > 0 -> credit.principal += tx.amount
            else -> {
                if (!note.startsWith("Проценты") && !note.startsWith("Комиссия")) {
                    credit.principal = (credit.principal + tx.amount).coerceAtLeast(0.0)
                }
            }
        }
        if (abs(credit.principal - oldPrincipal) > 0.001) recompute(credit, now)
    }

    private fun cal(millis: Long): Calendar {
        val c = Calendar.getInstance(MOSCOW)
        c.timeInMillis = millis
        return c
    }
}
