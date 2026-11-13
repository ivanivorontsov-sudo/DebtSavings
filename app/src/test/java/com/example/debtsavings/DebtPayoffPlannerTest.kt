package com.example.debtsavings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtPayoffPlannerTest {
    private fun credit(id: String, principal: Double, rate: Double, payment: Double) = Credit(
        id = id,
        name = id,
        rate = rate,
        termMonths = 24,
        monthlyPayment = payment,
        principal = principal,
        lastInterestDate = 0L,
        autoApplyMinPayment = false
    )

    @Test
    fun avalanchePrioritizesHighestRateAndSnowballLowestBalance() {
        val debts = listOf(
            credit("High APR", 1200.0, 29.9, 60.0),
            credit("Small balance", 600.0, 8.0, 30.0)
        )
        val avalanche = DebtPayoffPlanner.calculate(debts, 100.0, DebtStrategy.AVALANCHE)
        val snowball = DebtPayoffPlanner.calculate(debts, 100.0, DebtStrategy.SNOWBALL)

        assertEquals("High APR", avalanche.priorityOrder.first())
        assertEquals("Small balance", snowball.priorityOrder.first())
    }

    @Test
    fun plannerDoesNotMutateStoredCreditObjects() {
        val debts = listOf(credit("A", 1000.0, 18.0, 75.0))
        val before = debts.first().copy()

        DebtPayoffPlanner.calculate(debts, 25.0, DebtStrategy.AVALANCHE)

        assertEquals(before.principal, debts.first().principal, 0.0)
        assertEquals(before.accruedInterest, debts.first().accruedInterest, 0.0)
    }

    @Test
    fun tooSmallPaymentIsReportedInsteadOfInventingPayoffDate() {
        val debts = listOf(credit("High APR", 10000.0, 60.0, 1.0))
        val result = DebtPayoffPlanner.calculate(debts, 0.0, DebtStrategy.AVALANCHE)

        assertEquals(DebtPlanStatus.NO_PROGRESS, result.status)
        assertTrue(result.totalInterestForecast >= 0.0)
    }

    @Test
    fun zeroDebtsReturnsEmptyState() {
        val result = DebtPayoffPlanner.calculate(emptyList(), 0.0, DebtStrategy.SNOWBALL)
        assertEquals(DebtPlanStatus.NO_DEBTS, result.status)
        assertEquals(0, result.months)
    }
}
