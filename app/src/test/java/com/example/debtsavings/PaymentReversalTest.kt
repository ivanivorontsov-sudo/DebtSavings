package com.example.debtsavings

import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentReversalTest {
    @Test
    fun reversingPaymentRestoresEachBalanceBucket() {
        val credit = Credit(
            id = "credit-1",
            name = "Карта",
            rate = 20.0,
            termMonths = 12,
            monthlyPayment = 50.0,
            principal = 85.0,
            lastInterestDate = 0L,
            accruedInterest = 15.0,
            accruedFees = 0.0
        )
        val tx = Transaction(
            id = "payment-1",
            amount = 30.0,
            timestamp = 1L,
            note = "Платёж: Карта · Из платежа: комиссии 10 ₽ · % 5 ₽ · тело 15 ₽",
            creditId = credit.id
        )

        Annuity.reverseEffect(listOf(credit), tx)

        assertEquals(100.0, credit.principal, 0.0)
        assertEquals(20.0, credit.accruedInterest, 0.0)
        assertEquals(10.0, credit.accruedFees, 0.0)
    }
}
