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
 * Мин. платёж:
 *   feesPart      = accruedFees
 *   interestPart  ≈ accruedInterest / 12   (1/12 накопленных % после срыва грейса)
 *   principalPart = 0, либо roundDown100(principal * share%/100) с полом floor (500 ₽)
 *   total = fees + interest + principal
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
     * Обязательный (мин.) платёж по текущим остаткам.
     * interestPart ≈ 1/12 накопленных процентов (приближение тарифа WB).
     */
    fun calculateMinPayment(credit: Credit): PaymentBreakdown {
        val fees = credit.accruedFees.coerceAtLeast(0.0)
        val interest = (credit.accruedInterest.coerceAtLeast(0.0) / 12.0)
        val principal = principalShareAmount(
            credit.principal,
            credit.principalSharePercent,
            credit.minPrincipalPaymentFloor
        )
        return PaymentBreakdown(fees = fees, interest = interest, principal = principal)
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

    fun formatBreakdownRu(b: PaymentBreakdown, money: (Double) -> String): String =
        "Мин. платёж: ${money(b.total)} ₽\n" +
            "  · комиссии: ${money(b.fees)} ₽\n" +
            "  · проценты (≈1/12): ${money(b.interest)} ₽\n" +
            "  · тело: ${money(b.principal)} ₽"

    fun formatAllocationRu(b: PaymentBreakdown, money: (Double) -> String): String =
        "Из платежа: комиссии ${money(b.fees)} ₽ · % ${money(b.interest)} ₽ · тело ${money(b.principal)} ₽"

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
