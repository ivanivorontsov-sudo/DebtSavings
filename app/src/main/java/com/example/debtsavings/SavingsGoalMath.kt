package com.example.debtsavings

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

data class SavingsProjection(
    val monthsToTarget: Int?,
    val balanceAfter12Months: Double
)

/**
 * Оценка накоплений. Использует месячный эквивалент ежедневной капитализации APR/365
 * и предполагает взнос в конце каждого месяца.
 */
object SavingsGoalMath {
    fun project(
        currentBalance: Double,
        targetBalance: Double,
        monthlyContribution: Double,
        annualRatePercent: Double
    ): SavingsProjection {
        val current = money(currentBalance.coerceAtLeast(0.0))
        val target = money(targetBalance.coerceAtLeast(0.0))
        val contribution = money(monthlyContribution.coerceAtLeast(0.0))
        val annualRate = annualRatePercent.coerceIn(0.0, 100.0)
        val dailyFactor = 1.0 + annualRate / 100.0 / 365.0
        val monthlyRate = dailyFactor.pow(365.0 / 12.0) - 1.0

        var afterYear = current
        repeat(12) {
            afterYear = money(afterYear * (1.0 + monthlyRate) + contribution)
        }

        if (target <= current) return SavingsProjection(0, afterYear)
        if (contribution <= 0.0 && monthlyRate <= 0.0) {
            return SavingsProjection(null, afterYear)
        }

        var balance = current
        for (month in 1..1200) {
            balance = money(balance * (1.0 + monthlyRate) + contribution)
            if (balance >= target) return SavingsProjection(month, afterYear)
        }
        return SavingsProjection(null, afterYear)
    }

    private fun money(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()
}
