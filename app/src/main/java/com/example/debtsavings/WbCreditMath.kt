package com.example.debtsavings

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Формулы модели WB Кэш (приближение для личного учёта, не выписка банка).
 *
 * Комиссия за беспроцентный период (конец календарного месяца):
 *   units = max(1, ceil(principal / 1000))
 *   fee   = units * 50 ₽
 *   (если тело < 1000 ₽ → всё равно 50 ₽)
 *   Пропуск: первый месяц договора, если день договора > 21.
 *
 * Проценты вне грейса (простые, actual/365):
 *   daily = principal * (annualRate/100) / 365
 *   accruedInterest += daily * days
 *
 * Мин. / обязательный платёж = [Credit.monthlyPayment] (сумму задаёт пользователь).
 * Разбивка текущей суммы: комиссии → проценты → тело (allocatePayment).
 * Если monthlyPayment не задан — к погашению только комиссии + начисл. % (тело 0).
 *
 * Порядок погашения суммы X: комиссии → проценты → тело.
 */
object WbCreditMath {
    const val DEFAULT_ANNUAL_RATE = 59.8
    const val GRACE_FEE_PER_1000 = 50.0
    const val DEFAULT_MIN_PRINCIPAL_FLOOR = 500.0
    const val DEFAULT_PAYMENT_DAY = 25
    private const val MOSCOW_TZ = "Europe/Moscow"

    /** Комиссия за беспроцентный период за месяц при данном теле. */
    fun graceCommission(principal: Double): Double {
        if (principal <= 0.0) return 0.0
        val units = max(1, ceil(principal / 1000.0).toInt())
        return units * GRACE_FEE_PER_1000
    }

    /**
     * Пропускаем комиссию в первом календарном месяце договора,
     * если договор оформлен после 21-го числа.
     */
    fun shouldSkipFirstMonthCommission(contractStartMillis: Long, yearMonth: Int): Boolean {
        if (contractStartMillis <= 0L) return false
        val cal = moscowCal(contractStartMillis)
        val contractYm = cal.get(Calendar.YEAR) * 100 + (cal.get(Calendar.MONTH) + 1)
        if (contractYm != yearMonth) return false
        return cal.get(Calendar.DAY_OF_MONTH) > 21
    }

    /** Доля тела в мин. платеже: floor до 100 ₽, не ниже floor, не выше тела. */
    fun principalShareAmount(
        principal: Double,
        sharePercent: Double,
        floorRub: Double = DEFAULT_MIN_PRINCIPAL_FLOOR
    ): Double {
        if (principal <= 0.0 || sharePercent <= 0.0) return 0.0
        val raw = principal * sharePercent / 100.0
        val roundedDown100 = floor(raw / 100.0) * 100.0
        val withFloor = max(roundedDown100, floorRub)
        return min(withFloor, principal)
    }

    /**
     * Обязательный платёж = [Credit.monthlyPayment] (задаёт пользователь).
     * Разбивка «как сейчас уйдёт сумма»: комиссии → % → тело.
     * Если сумма не задана — только накопленные комиссии и проценты (тело 0).
     */
    fun calculateMinPayment(credit: Credit): PaymentBreakdown {
        val amount = credit.monthlyPayment.coerceAtLeast(0.0)
        if (amount > 0.01) {
            return allocatePayment(
                amount,
                credit.accruedFees,
                credit.accruedInterest,
                credit.principal
            )
        }
        val fees = credit.accruedFees.coerceAtLeast(0.0)
        val interest = credit.accruedInterest.coerceAtLeast(0.0)
        return PaymentBreakdown(fees = fees, interest = interest, principal = 0.0)
    }

    /**
     * Распределение суммы платежа: комиссии → проценты → тело.
     * Возвращает фактически списанные части (не больше остатков).
     */
    fun allocatePayment(
        amount: Double,
        feesBalance: Double,
        interestBalance: Double,
        principalBalance: Double
    ): PaymentBreakdown {
        var left = amount.coerceAtLeast(0.0)
        val toFees = min(left, feesBalance.coerceAtLeast(0.0))
        left -= toFees
        val toInterest = min(left, interestBalance.coerceAtLeast(0.0))
        left -= toInterest
        val toPrincipal = min(left, principalBalance.coerceAtLeast(0.0))
        return PaymentBreakdown(fees = toFees, interest = toInterest, principal = toPrincipal)
    }

    /** Применить разбивку к кредиту (мутирует поля остатков). */
    fun applyBreakdown(credit: Credit, breakdown: PaymentBreakdown) {
        credit.accruedFees = (credit.accruedFees - breakdown.fees).coerceAtLeast(0.0)
        credit.accruedInterest = (credit.accruedInterest - breakdown.interest).coerceAtLeast(0.0)
        credit.principal = (credit.principal - breakdown.principal).coerceAtLeast(0.0)
    }

    /**
     * Срыв грейса после неполного погашения.
     * Мин. платёж ≠ полное погашение: если после платежа остался долг
     * (тело + % + комиссии) > 0.01 — [graceMode] снимается, дальше начисляются %%.
     * @return true, если грейс был снят этим вызовом
     */
    fun afterPartialPayment(credit: Credit): Boolean {
        if (!credit.graceMode) return false
        val remaining = credit.principal + credit.accruedInterest + credit.accruedFees
        if (remaining > 0.01) {
            credit.graceMode = false
            return true
        }
        return false
    }

    fun yearMonthOf(millis: Long): Int {
        val cal = moscowCal(millis)
        return cal.get(Calendar.YEAR) * 100 + (cal.get(Calendar.MONTH) + 1)
    }

    /** Конец календарного месяца (МСК) + [paymentDay] дней → срок оплаты. */
    fun dueDateAfterBillingMonth(billingYearMonth: Int, paymentDay: Int = DEFAULT_PAYMENT_DAY): Long {
        val year = billingYearMonth / 100
        val month = billingYearMonth % 100 // 1..12
        val cal = Calendar.getInstance(TimeZone.getTimeZone(MOSCOW_TZ))
        cal.clear()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_MONTH, paymentDay.coerceIn(1, 28))
        return cal.timeInMillis
    }

    /** Следующий расчётный YYYYMM после данного. */
    fun nextYearMonth(yearMonth: Int): Int {
        val y = yearMonth / 100
        val m = yearMonth % 100
        return if (m >= 12) (y + 1) * 100 + 1 else y * 100 + (m + 1)
    }

    fun formatBreakdownRu(b: PaymentBreakdown, money: (Double) -> String): String {
        // Только когда нет суммы платежа и нет комиссий/% .
        if (b.total <= 0.01) return "нет обязательного платежа"
        val lines = mutableListOf("Мин. платёж: ${money(b.total)} ₽")
        if (b.fees > 0.01) lines.add("  · комиссии: ${money(b.fees)} ₽")
        if (b.interest > 0.01) lines.add("  · проценты: ${money(b.interest)} ₽")
        if (b.principal > 0.01) lines.add("  · тело: ${money(b.principal)} ₽")
        return lines.joinToString("\n")
    }

    fun formatAllocationRu(b: PaymentBreakdown, money: (Double) -> String): String {
        val parts = mutableListOf<String>()
        if (b.fees > 0.01) parts.add("комиссии ${money(b.fees)} ₽")
        if (b.interest > 0.01) parts.add("% ${money(b.interest)} ₽")
        if (b.principal > 0.01) parts.add("тело ${money(b.principal)} ₽")
        val body = if (parts.isEmpty()) "0 ₽" else parts.joinToString(" · ")
        return "Из платежа: $body"
    }

    /** Остатки: только ненулевые части (тело / % / комис.). */
    fun formatBalancesRu(
        principal: Double,
        interest: Double,
        fees: Double,
        money: (Double) -> String,
        principalLabel: String = "Тело",
        interestLabel: String = "%",
        feesLabel: String = "комис."
    ): String {
        val parts = mutableListOf<String>()
        if (principal > 0.01) parts.add("$principalLabel ${money(principal)} ₽")
        if (interest > 0.01) parts.add("$interestLabel ${money(interest)} ₽")
        if (fees > 0.01) parts.add("$feesLabel ${money(fees)} ₽")
        return if (parts.isEmpty()) "остаток 0 ₽" else parts.joinToString(" · ")
    }

    /** Пресет WB Банк / WB Кэш. */
    fun applyWbPreset(credit: Credit) {
        credit.rate = DEFAULT_ANNUAL_RATE
        credit.graceMode = true
        credit.principalSharePercent = 0.0
        credit.minPrincipalPaymentFloor = DEFAULT_MIN_PRINCIPAL_FLOOR
        credit.paymentDay = DEFAULT_PAYMENT_DAY
        credit.autoApplyMinPayment = true
        if (credit.name.isBlank() || credit.name == "Кредит") {
            credit.name = "WB Кэш"
        }
    }

    private fun moscowCal(millis: Long): Calendar {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(MOSCOW_TZ))
        cal.timeInMillis = millis
        return cal
    }
}
