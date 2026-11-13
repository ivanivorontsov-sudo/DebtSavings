package com.example.debtsavings

import java.math.BigDecimal
import java.math.RoundingMode

enum class DebtStrategy {
    AVALANCHE,
    SNOWBALL
}

enum class DebtPlanStatus {
    PAID_OFF,
    NO_DEBTS,
    NO_PROGRESS,
    LIMIT_REACHED
}

data class DebtPayoffResult(
    val strategy: DebtStrategy,
    val months: Int,
    val startingDebt: Double,
    val totalInterestForecast: Double,
    val totalPaidForecast: Double,
    val monthlyMinimums: Double,
    val extraMonthly: Double,
    val priorityOrder: List<String>,
    val paidOffOrder: List<String>,
    val status: DebtPlanStatus
)

/**
 * Сценарный расчёт, который работает только с копиями данных.
 * Обязательные платежи учитываются первыми, остаток направляется на приоритетный долг,
 * а после его закрытия освободившийся минимум переносится на следующий.
 *
 * Это не банковский график и не отправка денег. Модель использует APR/12 на остаток
 * тела и не прогнозирует комиссии, штрафы, изменение ставки и индивидуальные условия договора.
 */
object DebtPayoffPlanner {
    private val ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
    private val CENT = BigDecimal("0.01")
    private val DIVISOR_1200 = BigDecimal("1200")

    private class SimDebt(
        val id: String,
        val name: String,
        val originalIndex: Int,
        val rate: BigDecimal,
        val graceMode: Boolean,
        val minimum: BigDecimal,
        var principal: BigDecimal,
        var interest: BigDecimal,
        var fees: BigDecimal
    ) {
        val outstanding: BigDecimal
            get() = principal.add(interest).add(fees).setScale(2, RoundingMode.HALF_UP)

        fun accrueMonthlyInterest(): BigDecimal {
            if (graceMode || principal <= ZERO || rate <= ZERO) return ZERO
            val accrued = principal.multiply(rate)
                .divide(DIVISOR_1200, 2, RoundingMode.HALF_UP)
            interest = interest.add(accrued).setScale(2, RoundingMode.HALF_UP)
            return accrued
        }

        /** Погашение в последовательности: комиссии → проценты → тело. Возвращает использованную сумму. */
        fun applyPayment(requested: BigDecimal): BigDecimal {
            var left = requested.max(ZERO)
            val fromFees = left.min(fees)
            fees = fees.subtract(fromFees)
            left = left.subtract(fromFees)

            val fromInterest = left.min(interest)
            interest = interest.subtract(fromInterest)
            left = left.subtract(fromInterest)

            val fromPrincipal = left.min(principal)
            principal = principal.subtract(fromPrincipal)
            left = left.subtract(fromPrincipal)

            return requested.max(ZERO).subtract(left).setScale(2, RoundingMode.HALF_UP)
        }
    }

    fun calculate(
        credits: List<Credit>,
        extraMonthly: Double,
        strategy: DebtStrategy,
        maxMonths: Int = 600
    ): DebtPayoffResult {
        val rows = credits.mapIndexedNotNull { index, c ->
            val p = money(c.principal)
            val i = money(c.accruedInterest)
            val f = money(c.accruedFees)
            if (p.add(i).add(f) <= ZERO) return@mapIndexedNotNull null
            SimDebt(
                id = c.id,
                name = c.name,
                originalIndex = index,
                rate = money(c.rate.coerceAtLeast(0.0)),
                graceMode = c.graceMode,
                minimum = money(c.monthlyPayment.coerceAtLeast(0.0)),
                principal = p,
                interest = i,
                fees = f
            )
        }

        val startingDebt = rows.fold(ZERO) { acc, row -> acc.add(row.outstanding) }
        val monthlyMinimums = rows.fold(ZERO) { acc, row -> acc.add(row.minimum) }
        val extra = money(extraMonthly.coerceAtLeast(0.0))
        val order = priority(rows, strategy)

        if (rows.isEmpty()) {
            return DebtPayoffResult(
                strategy, 0, 0.0, 0.0, 0.0, 0.0, extra.toDouble(),
                emptyList(), emptyList(), DebtPlanStatus.NO_DEBTS
            )
        }

        var rollover = ZERO
        var totalInterest = ZERO
        var totalPaid = ZERO
        var months = 0
        var stagnantMonths = 0
        val paidOff = mutableListOf<String>()
        val closedIds = mutableSetOf<String>()
        val maxIterations = maxMonths.coerceIn(1, 1200)

        while (months < maxIterations && rows.any { it.outstanding > ZERO }) {
            months++
            val debtBeforeMonth = rows.fold(ZERO) { acc, row -> acc.add(row.outstanding) }

            rows.forEach { totalInterest = totalInterest.add(it.accrueMonthlyInterest()) }

            // Доплата и ранее высвободившиеся минимумы доступны для приоритетного долга.
            var extraPool = extra.add(rollover)
            rollover = ZERO

            // Минимальный платёж вносится по каждому открытому долгу.
            rows.forEach { row ->
                if (row.outstanding <= ZERO || row.minimum <= ZERO) return@forEach
                val used = row.applyPayment(row.minimum)
                totalPaid = totalPaid.add(used)
                // Неиспользованная часть минимума не пропадает, а переходит в extra pool.
                extraPool = extraPool.add(row.minimum.subtract(used))
            }

            for (row in order) {
                if (extraPool <= ZERO) break
                if (row.outstanding <= ZERO) continue
                val used = row.applyPayment(extraPool)
                totalPaid = totalPaid.add(used)
                extraPool = extraPool.subtract(used)
            }

            // Минимумы закрытых долгов высвобождаются со следующего месяца.
            rows.filter { it.outstanding <= ZERO && closedIds.add(it.id) }
                .forEach { row ->
                    paidOff.add(row.name)
                    rollover = rollover.add(row.minimum)
                }

            val debtAfterMonth = rows.fold(ZERO) { acc, row -> acc.add(row.outstanding) }
            if (debtAfterMonth >= debtBeforeMonth.subtract(CENT)) stagnantMonths++ else stagnantMonths = 0
            if (stagnantMonths >= 2 && debtAfterMonth > ZERO) {
                return result(
                    strategy, months, startingDebt, totalInterest, totalPaid,
                    monthlyMinimums, extra, order, paidOff, DebtPlanStatus.NO_PROGRESS
                )
            }
        }

        val status = when {
            rows.none { it.outstanding > ZERO } -> DebtPlanStatus.PAID_OFF
            months >= maxIterations -> DebtPlanStatus.LIMIT_REACHED
            else -> DebtPlanStatus.NO_PROGRESS
        }
        return result(
            strategy, months, startingDebt, totalInterest, totalPaid,
            monthlyMinimums, extra, order, paidOff, status
        )
    }

    private fun priority(rows: List<SimDebt>, strategy: DebtStrategy): List<SimDebt> =
        when (strategy) {
            DebtStrategy.AVALANCHE -> rows.sortedWith(
                compareByDescending<SimDebt> { if (it.graceMode) ZERO else it.rate }
                    .thenBy { it.outstanding }
                    .thenBy { it.originalIndex }
            )
            DebtStrategy.SNOWBALL -> rows.sortedWith(
                compareBy<SimDebt> { it.outstanding }
                    .thenByDescending { it.rate }
                    .thenBy { it.originalIndex }
            )
        }

    private fun result(
        strategy: DebtStrategy,
        months: Int,
        startingDebt: BigDecimal,
        totalInterest: BigDecimal,
        totalPaid: BigDecimal,
        monthlyMinimums: BigDecimal,
        extra: BigDecimal,
        order: List<SimDebt>,
        paidOff: List<String>,
        status: DebtPlanStatus
    ) = DebtPayoffResult(
        strategy = strategy,
        months = months,
        startingDebt = startingDebt.toDouble(),
        totalInterestForecast = totalInterest.toDouble(),
        totalPaidForecast = totalPaid.toDouble(),
        monthlyMinimums = monthlyMinimums.toDouble(),
        extraMonthly = extra.toDouble(),
        priorityOrder = order.map { it.name },
        paidOffOrder = paidOff.toList(),
        status = status
    )

    private fun money(value: Double): BigDecimal =
        if (value.isFinite()) BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP) else ZERO
}
