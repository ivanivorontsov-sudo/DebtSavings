package com.example.debtsavings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnuityTest {
    private val money: (Double) -> String = { String.format(java.util.Locale.US, "%.2f", it) }

    /** Значения из приложения банка 25.09.2026. */
    @Test
    fun bankValues_exact() {
        assertEquals(780.70, Annuity.payment(25859.48, 26.0, 59), 0.0)
        assertEquals(4377.59, Annuity.payment(145000.0, 26.0, 59), 0.0)
    }

    @Test
    fun remainingPayments_octToAug2031_is59() {
        val wb2Next = Migrations.moscowDate(2026, 10, 26)
        val wb2End = Migrations.moscowDate(2031, 8, 22)
        assertEquals(59, Annuity.remainingPayments(wb2Next, wb2End))
        val wb1Next = Migrations.moscowDate(2026, 10, 12)
        val wb1End = Migrations.moscowDate(2031, 8, 8)
        assertEquals(59, Annuity.remainingPayments(wb1Next, wb1End))
    }

    private fun wb2(principal: Double = 25859.48, interest: Double = 50.0) = Credit(
        id = "wb2", name = "Вб_2", rate = 26.0, termMonths = 58, monthlyPayment = 836.34,
        principal = principal, lastInterestDate = 0L, accruedInterest = interest, accruedFees = 0.0,
        paymentDay = 26, nextMinPaymentDue = Migrations.moscowDate(2026, 10, 26),
        endDate = Migrations.moscowDate(2031, 8, 22), recalcPaymentAfterPrepay = true
    )

    @Test
    fun recompute_givesBankPayment() {
        val c = wb2()
        val now = Migrations.moscowDate(2026, 9, 25) + 12 * 3600_000L
        assertTrue(Annuity.recompute(c, now))
        assertEquals(780.70, c.monthlyPayment, 0.0)
        // min payment equals monthlyPayment
        assertEquals(780.70, WbCreditMath.calculateMinPayment(c).total, 1e-9)
    }

    @Test
    fun earlyPayment_offSchedule_allToPrincipal_andRecalc() {
        val c = wb2(principal = 27702.36, interest = 19.73)
        val now = Migrations.moscowDate(2026, 9, 25) + 9 * 3600_000L
        assertFalse(Annuity.isScheduledPaymentDay(c, now))
        val res = Annuity.applyPayment(c, 1842.88, null, now, money)
        assertTrue(res.early)
        assertEquals(1842.88, res.breakdown.principal, 1e-9)
        assertEquals(0.0, res.breakdown.interest, 0.0)
        assertEquals(19.73, c.accruedInterest, 1e-9)
        assertEquals(25859.48, c.principal, 1e-6)
        assertEquals(780.70, c.monthlyPayment, 0.0)
        assertTrue(res.note.startsWith("Досрочный платёж: всё в тело"))
    }

    @Test
    fun scheduledDay_feesInterestPrincipal() {
        val c = wb2(interest = 400.0).apply { accruedFees = 10.0; autoApplyMinPayment = false }
        val now = Migrations.moscowDate(2026, 10, 26) + 10 * 3600_000L
        assertTrue(Annuity.isScheduledPaymentDay(c, now))
        val res = Annuity.applyPayment(c, 780.70, null, now, money)
        assertFalse(res.early)
        assertEquals(10.0, res.breakdown.fees, 1e-9)
        assertEquals(400.0, res.breakdown.interest, 1e-9)
        assertEquals(370.70, res.breakdown.principal, 1e-9)
        // срок сдвинут на 26.11 (авто-учёт выключен)
        assertEquals(Migrations.moscowDate(2026, 11, 26), c.nextMinPaymentDue)
    }

    @Test
    fun explicitChoice_overridesDate() {
        val c = wb2(interest = 100.0)
        val now = Migrations.moscowDate(2026, 9, 25)
        val res = Annuity.applyPayment(c, 500.0, false, now, money)
        assertFalse(res.early)
        assertEquals(100.0, res.breakdown.interest, 1e-9)
    }

    @Test
    fun creditWithoutEndDate_keepsOldRule() {
        val c = Credit("x", "Альфа", 70.0, 12, 300.0, 1000.0, 0L, accruedInterest = 50.0)
        val now = Migrations.moscowDate(2026, 9, 25)
        val res = Annuity.applyPayment(c, 300.0, null, now, money)
        assertFalse(res.early)
        assertEquals(50.0, res.breakdown.interest, 1e-9)
        assertEquals(250.0, res.breakdown.principal, 1e-9)
        assertEquals(300.0, c.monthlyPayment, 0.0) // не пересчитывается
    }

    @Test
    fun interestPartParsing_nbsp() {
        assertEquals(19.73, Migrations.interestPartOf("Платёж: Вб_2 · Из платежа: % 19,73 ₽ · тело 1\u00A0823,15 ₽")!!, 1e-9)
        assertEquals(19.73, Migrations.interestPartOf("Из платежа: %\u202F19,73\u00A0₽ · тело 1\u202F823,15 ₽")!!, 1e-9)
    }

    /**
     * Модель банка: досрочка 1 842,88 от 27 702,36 → тело 25 859,48, начисленные % не трогаются,
     * % следующего дня = 25 859,48 × 0,26 / 365 (actual/365 как в приложении), платёж 780,70;
     * авто-платёж — только в плановую дату 26.10.
     */
    @Test
    fun bankModel_prepayThenNextDayAccrual() {
        val day25 = Migrations.moscowDate(2026, 9, 25)
        val c = wb2(principal = 27702.36, interest = 123.45).apply {
            lastInterestDate = day25; autoApplyMinPayment = true; monthlyPayment = 836.34
        }
        val credits = mutableListOf(c)
        val txs = mutableListOf<Transaction>()
        val prefs = FakeSharedPreferences()

        val payTime = day25 + 2 * 3600_000L + 10 * 60_000L // 02:10 МСК = 09:10 Владивосток
        val res = Annuity.applyPayment(c, 1842.88, null, payTime, money)
        assertTrue(res.early)
        assertEquals(25859.48, c.principal, 1e-6)
        assertEquals(123.45, c.accruedInterest, 1e-9) // % не списаны
        assertEquals(780.70, c.monthlyPayment, 0.0)

        // Полночь МСК 26.09: % за 25.09 на уменьшенное тело, авто-платежа нет (срок 26.10)
        val day26 = Migrations.moscowDate(2026, 9, 26) + 60_000L
        DailyInterest.applyIfNeeded(prefs, txs, credits, day26)
        assertEquals(123.45 + 25859.48 * 0.26 / 365.0, c.accruedInterest, 1e-9)
        assertEquals(25859.48, c.principal, 1e-6)
        assertEquals(Migrations.moscowDate(2026, 10, 26), c.nextMinPaymentDue)
        assertTrue(txs.none { it.note.startsWith("Авто платёж") })

        // 26.10: обязательный платёж 780,70 → комиссии → % → тело, затем пересчёт по формуле
        val accruedBefore = c.accruedInterest + 25859.48 * 0.26 / 365.0 * 30 // 26.09..25.10
        val day2610 = Migrations.moscowDate(2026, 10, 26) + 60_000L
        DailyInterest.applyIfNeeded(prefs, txs, credits, day2610)
        val auto = txs.single { it.note.startsWith("Авто платёж") }
        assertEquals(780.70, auto.amount, 1e-9)
        assertEquals(0.0, c.accruedInterest, 1e-9)
        val expectedPrincipal = 25859.48 - (780.70 - accruedBefore)
        assertEquals(expectedPrincipal, c.principal, 1e-6)
        assertEquals(Migrations.moscowDate(2026, 11, 26), c.nextMinPaymentDue)
        assertEquals(Annuity.payment(c.principal, 26.0, 58), c.monthlyPayment, 0.0)
    }
}
