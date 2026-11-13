package com.example.debtsavings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingsGoalMathTest {
    @Test
    fun alreadyReachedTargetRequiresNoMonths() {
        val result = SavingsGoalMath.project(15000.0, 10000.0, 1000.0, 10.0)
        assertEquals(0, result.monthsToTarget)
    }

    @Test
    fun noContributionAndZeroRateDoesNotInventTargetDate() {
        val result = SavingsGoalMath.project(1000.0, 5000.0, 0.0, 0.0)
        assertNull(result.monthsToTarget)
    }

    @Test
    fun regularContributionMovesSavingsTowardGoal() {
        val result = SavingsGoalMath.project(10000.0, 20000.0, 1000.0, 8.0)
        assertTrue(result.monthsToTarget != null && result.monthsToTarget!! in 1..12)
        assertTrue(result.balanceAfter12Months > 10000.0)
    }
}
