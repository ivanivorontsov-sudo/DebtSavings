package com.example.debtsavings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AnnuityTest {
    private val money: (Double) -> String = {
        String.format(java.util.Locale.US, "%.2f", it)
    }

    private fun moscowDate(year: Int, month: Int, day: Int): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
        calendar.clear()
        calendar.set(year, month - 1, day, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun scheduledCredit(
        principal: Double = 10_000.0,
        interest: Double = 50.0,
        fees: Double = 0.0,
        rate: Double = 12.0,
        payment: Double = 500.0,
        nextDue: Long = moscowDate(2027, 4, 25),
        endDate: Long = moscowDate(2028, 3, 25),
        paymentDay: Int = 25
    ) = Credit(
        id = "test-credit",
        name = "Тестовый кредит",
        rate = rate,
        termMonths = 12,
        monthlyPayment = payment,
        principal = principal,
        lastInterestDate = 0L,
        accruedInterest = interest,
        accruedFees = fees,
        paymentDay = paymentDay,
        nextMinPaymentDue = nextDue,
        autoApplyMinPayment = false,
        endDate = endDate,
        recalcPaymentAfterPrepay = true
    )

    @Test
    fun annuityPaymentIsPositiveAndRoundedToCents() {
        val payment = Annuity.payment(100_000.0, 12.0, 12)
        assertTrue(payment > 0.0)
        assertEquals(payment, Annuity.round2(payment), 0.0)
    }

    @Test
    fun remainingPaymentsIncludesBothFirstAndFinalDueDates() {
        val first = moscowDate(2027, 4, 15)
        val last = moscowDate(2028, 3, 15)
        assertEquals(12, Annuity.remainingPayments(first, last))
    }

    @Test
    fun recomputeUsesRemainingTermAndCurrentPrincipal() {
        val credit = scheduledCredit(principal = 10_000.0, payment = 1.0)
        val now = moscowDate(2027, 3, 1)

        assertTrue(Annuity.recompute(credit, now))
        assertEquals(Annuity.payment(10_000.0, 12.0, 12), credit.monthlyPayment, 0.0)
    }

    @Test
    fun earlyPaymentGoesToPrincipalAndLeavesAccruedInterestUntouched() {
        val credit = scheduledCredit()
        val now = moscowDate(2027, 3, 12) + 9 * 3_600_000L
        assertFalse(Annuity.isScheduledPaymentDay(credit, now))

        val result = Annuity.applyPayment(credit, 1_000.0, null, now, money)

        assertTrue(result.early)
        assertEquals(1_000.0, result.breakdown.principal, 0.0)
        assertEquals(0.0, result.breakdown.interest, 0.0)
        assertEquals(0.0, result.breakdown.fees, 0.0)
        assertEquals(9_000.0, credit.principal, 0.0)
        assertEquals(50.0, credit.accruedInterest, 0.0)
    }

    @Test
    fun scheduledPaymentAllocatesFeesThenInterestThenPrincipal() {
        val credit = scheduledCredit(principal = 1_000.0, interest = 40.0, fees = 10.0, payment = 100.0)
        val now = moscowDate(2027, 4, 25) + 10 * 3_600_000L

        assertTrue(Annuity.isScheduledPaymentDay(credit, now))
        val result = Annuity.applyPayment(credit, 100.0, null, now, money)

        assertFalse(result.early)
        assertEquals(10.0, result.breakdown.fees, 0.0)
        assertEquals(40.0, result.breakdown.interest, 0.0)
        assertEquals(50.0, result.breakdown.principal, 0.0)
    }

    @Test
    fun creditWithoutScheduleKeepsStandardAllocation() {
        val credit = Credit(
            "ordinary", "Обычный кредит", 18.0, 12, 300.0, 1_000.0, 0L,
            accruedInterest = 50.0
        )
        val result = Annuity.applyPayment(
            credit, 300.0, null, moscowDate(2027, 3, 1), money
        )

        assertFalse(result.early)
        assertEquals(50.0, result.breakdown.interest, 0.0)
        assertEquals(250.0, result.breakdown.principal, 0.0)
        assertEquals(300.0, credit.monthlyPayment, 0.0)
    }

    @Test
    fun dailyInterestAfterPrepaymentUsesReducedPrincipal() {
        val start = moscowDate(2027, 3, 25)
        val credit = scheduledCredit(principal = 20_000.0, interest = 50.0).apply {
            lastInterestDate = start
        }
        val credits = mutableListOf(credit)
        val transactions = mutableListOf<Transaction>()
        val prefs = FakeSharedPreferences()

        val early = Annuity.applyPayment(credit, 1_000.0, null, start + 2 * 3_600_000L, money)
        assertTrue(early.early)
        assertEquals(19_000.0, credit.principal, 0.0)

        val nextDay = moscowDate(2027, 3, 26) + 60_000L
        DailyInterest.applyIfNeeded(prefs, transactions, credits, nextDay)

        assertEquals(50.0 + 19_000.0 * 0.12 / 365.0, credit.accruedInterest, 1e-8)
        assertEquals(19_000.0, credit.principal, 0.0)
    }
}
