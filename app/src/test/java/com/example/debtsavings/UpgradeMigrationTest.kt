package com.example.debtsavings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обновление 1.1.6 → 1.1.7 поверх существующих данных: ничего не теряется,
 * добавляются только новые поля и корректирующие операции; повторный запуск — без изменений.
 */
class UpgradeMigrationTest {

    private fun msk(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        Migrations.moscowDate(y, m, d) + h * 3600_000L + min * 60_000L

    private val day24 = msk(2026, 9, 24)
    private val now = msk(2026, 9, 25, 16, 50) // 23:50 Владивосток

    /** Кредит ровно в формате creditToJson 1.1.6 (без endDate / recalcPaymentAfterPrepay). */
    private fun credit116(
        id: String, name: String, principal: Double, rate: Double, grace: Boolean,
        monthly: Double, interest: Double, term: Int, fees: Double = 0.0
    ) = JSONObject().apply {
        put("id", id); put("name", name); put("rate", rate); put("termMonths", term)
        put("monthlyPayment", monthly); put("principal", principal)
        put("lastInterestDate", day24 + 86_400_000L); put("accruedInterest", interest)
        put("accruedFees", fees); put("graceMode", grace); put("principalSharePercent", 0.0)
        put("minPrincipalPaymentFloor", 500.0); put("paymentDay", 25)
        put("nextMinPaymentDue", msk(2026, 10, 25)); put("autoApplyMinPayment", true)
        put("contractStartDate", day24); put("lastFeeYearMonth", 0); put("lastMinPaymentPeriodKey", 0)
    }

    private fun tx(id: String, amount: Double, ts: Long, note: String, creditId: String?) = JSONObject().apply {
        put("id", id); put("amount", amount); put("timestamp", ts); put("note", note)
        if (creditId != null) put("creditId", creditId)
    }

    private fun prefs116(wb2Principal: Double = 25879.21): FakeSharedPreferences {
        val credits = JSONArray().apply {
            put(credit116("seed-yandex", "Яндекс Супер Сплит", 4318.1, 0.0, true, 0.0, 0.0, 12))
            put(credit116("seed-wb", "WB Кэш", 60000.0, 59.8, true, 60000.0, 0.0, 58, fees = 3000.0))
            put(credit116("seed-alfa", "Альфа Карта", 15000.0, 70.0, false, 300.0, 28.77, 12))
            put(credit116("seed-tplat", "Т Платинум", 235689.0, 59.8, true, 133000.0, 0.0, 12))
            put(credit116("seed-wb2", "Вб_2", wb2Principal, 26.0, false, 836.34, 0.0, 58))
            put(credit116("seed-wb1", "Вб_1", 145000.0, 26.0, false, 4377.59, 1563.07, 58))
        }
        // newest first, как в приложении; разделитель тысяч — NBSP (ru-RU NumberFormat)
        val txs = JSONArray().apply {
            put(tx("t9", 100.0, msk(2026, 9, 25, 5), "Пополнение", null))
            put(tx("t8", 1842.88, msk(2026, 9, 25, 2, 10),
                "Платёж: Вб_2 · Из платежа: % 19,73 ₽ · тело 1\u00A0823,15 ₽", "seed-wb2"))
            put(tx("t7", -19.73, msk(2026, 9, 25), "Проценты: Вб_2 за 1 дн. (начислены отдельно от тела)", "seed-wb2"))
            put(tx("t6", 1822.15, day24 + 7000, "Корректировка тела: Вб_2 (-1\u00A0822,15 ₽)", "seed-wb2"))
            put(tx("t5", -1459.78, day24 + 6000, "Начисл. %% (старт): Вб_1", "seed-wb1"))
            put(tx("t4", -145000.0, day24 + 5000, "Получен кредит: Вб_1 (тело долга)", "seed-wb1"))
            put(tx("t3", -27702.36, day24 + 4000, "Получен кредит: Вб_2 (тело долга)", "seed-wb2"))
            put(tx("t2", -235689.0, day24 + 3000, "Получен кредит: Т Платинум (тело долга)", "seed-tplat"))
            put(tx("t1", -60000.0, day24 + 1000, "Получен кредит: WB Кэш (тело долга)", "seed-wb"))
            put(tx("t0", -4318.1, day24, "Получен кредит: Яндекс Супер Сплит (тело долга)", "seed-yandex"))
        }
        val subs = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "s1"); put("name", "Яндекс Плюс"); put("price", 299.0); put("period", "MONTHLY")
                put("customDays", 30); put("nextChargeDate", msk(2026, 10, 3)); put("notes", "")
                put("intent", "NEED_PAY"); put("notifyDays", "7,3,1"); put("enabled", true); put("category", "THIRD_PARTY")
            })
            put(JSONObject().apply {
                put("id", "s2"); put("name", "Страховка WB / рассрочка"); put("price", 199.0); put("period", "MONTHLY")
                put("customDays", 30); put("nextChargeDate", msk(2026, 10, 1)); put("notes", "отключить")
                put("intent", "NEED_CANCEL"); put("notifyDays", "7,3,1"); put("enabled", true); put("category", "DEBT_INSURANCE")
            })
        }
        return FakeSharedPreferences().apply {
            map[Persistence.CREDITS_KEY] = credits.toString()
            map[Persistence.TRANSACTIONS_KEY] = txs.toString()
            map[Persistence.SUBSCRIPTIONS_KEY] = subs.toString()
            map[Persistence.DEFAULTS_SEEDED_KEY] = true
            map["savings_rate"] = 16.5f
            map["savings_last_interest_date"] = day24
            map["pay_remind_fired"] = setOf("credit:seed-wb1:1:20260925:9")
        }
    }

    /** Имитация запуска приложения: load → ensureDefaultSeed → migrations. */
    private fun launch(prefs: FakeSharedPreferences) {
        val credits = mutableListOf<Credit>()
        val txs = mutableListOf<Transaction>()
        Persistence.loadTransactions(prefs, txs)
        Persistence.loadCredits(prefs, credits)
        assertFalse("seed must be no-op with existing credits", Persistence.ensureDefaultSeed(prefs, credits, txs))
        Migrations.runAll(prefs, credits, txs, now)
    }

    private fun loadCredits(p: FakeSharedPreferences) = mutableListOf<Credit>().also { Persistence.loadCredits(p, it) }
    private fun loadTx(p: FakeSharedPreferences) = mutableListOf<Transaction>().also { Persistence.loadTransactions(p, it) }

    @Test
    fun upgrade116_preservesEverything_andIsIdempotent() {
        val prefs = prefs116()
        val before = loadCredits(prefs)
        val txBefore = loadTx(prefs)
        val subsJsonBefore = prefs.map[Persistence.SUBSCRIPTIONS_KEY]
        assertEquals(6, before.size)
        assertEquals(10, txBefore.size)

        launch(prefs)
        val afterFirstCredits = prefs.map[Persistence.CREDITS_KEY]
        val afterFirstTx = prefs.map[Persistence.TRANSACTIONS_KEY]
        launch(prefs) // повторный запуск
        assertEquals("2nd run must not change credits", afterFirstCredits, prefs.map[Persistence.CREDITS_KEY])
        assertEquals("2nd run must not change journal", afterFirstTx, prefs.map[Persistence.TRANSACTIONS_KEY])

        val after = loadCredits(prefs)
        assertEquals(before.map { it.id }, after.map { it.id })
        for ((b, a) in before.zip(after)) {
            assertEquals(b.name, a.name)
            assertEquals(b.graceMode, a.graceMode)
            assertEquals(b.termMonths, a.termMonths)
            assertEquals(b.accruedFees, a.accruedFees, 0.0)
            assertEquals(b.lastInterestDate, a.lastInterestDate)
            assertEquals(b.contractStartDate, a.contractStartDate)
            assertEquals(b.autoApplyMinPayment, a.autoApplyMinPayment)
            assertEquals(b.principalSharePercent, a.principalSharePercent, 0.0)
            assertEquals(b.minPrincipalPaymentFloor, a.minPrincipalPaymentFloor, 0.0)
            assertEquals(b.lastFeeYearMonth, a.lastFeeYearMonth)
            assertEquals(b.lastMinPaymentPeriodKey, a.lastMinPaymentPeriodKey)
            assertEquals(b.rate, a.rate, 0.0)
            if (b.name != "Вб_1" && b.name != "Вб_2") {
                assertEquals(b, a) // полностью без изменений (endDate=0, recalc=true по умолчанию)
                assertEquals(0L, a.endDate)
            }
        }
        val wb1 = after.single { it.name == "Вб_1" }
        assertEquals(145000.0, wb1.principal, 0.0)
        assertEquals(1563.07, wb1.accruedInterest, 0.0)
        assertEquals(Migrations.moscowDate(2031, 8, 8), wb1.endDate)
        assertEquals(12, wb1.paymentDay)
        assertTrue(wb1.recalcPaymentAfterPrepay)
        assertEquals(Migrations.moscowDate(2026, 10, 12), wb1.nextMinPaymentDue)
        assertEquals(4377.59, wb1.monthlyPayment, 0.0)

        val wb2 = after.single { it.name == "Вб_2" }
        assertEquals(25859.48, wb2.principal, 1e-9)
        assertEquals(19.73, wb2.accruedInterest, 1e-9)
        assertEquals(Migrations.moscowDate(2031, 8, 22), wb2.endDate)
        assertEquals(26, wb2.paymentDay)
        assertEquals(Migrations.moscowDate(2026, 10, 26), wb2.nextMinPaymentDue)
        assertEquals(780.70, wb2.monthlyPayment, 0.0)
        assertEquals(780.70, WbCreditMath.calculateMinPayment(wb2).total, 1e-9)

        // Журнал: все старые записи на месте без изменений, + ровно 2 корректировки
        val txAfter = loadTx(prefs)
        assertEquals(12, txAfter.size)
        assertEquals(txBefore, txAfter.filter { t -> txBefore.any { it.id == t.id } })
        val added = txAfter.filter { t -> txBefore.none { it.id == t.id } }
        assertEquals(2, added.size)
        assertTrue(added.all { it.note.contains(Migrations.WB2_FIX_LABEL) && it.creditId == "seed-wb2" })
        val body = added.single { it.note.startsWith("Корректировка тела") }
        val pct = added.single { it.note.startsWith("Корректировка %") }
        assertEquals(19.73, body.amount, 1e-9)   // amount = −delta (тело −19,73)
        assertEquals(-19.73, pct.amount, 1e-9)   // amount = −delta (% +19,73)
        assertEquals(0.0, added.sumOf { it.amount }, 1e-9) // баланс журнала не меняется

        // Подписки и настройки не тронуты
        assertEquals(subsJsonBefore, prefs.map[Persistence.SUBSCRIPTIONS_KEY])
        val subs = mutableListOf<Subscription>().also { Persistence.loadSubscriptions(prefs, it) }
        assertEquals(listOf("s1", "s2"), subs.map { it.id })
        assertEquals(16.5f, prefs.map["savings_rate"])
        assertEquals(day24, prefs.map["savings_last_interest_date"])
        assertEquals(true, prefs.map[Persistence.DEFAULTS_SEEDED_KEY])
        assertEquals(setOf("credit:seed-wb1:1:20260925:9"), prefs.map["pay_remind_fired"])
        assertEquals(true, prefs.map[Migrations.ANNUITY_MIGRATION_KEY])
        assertEquals(true, prefs.map[Migrations.WB2_PREPAY_FIX_KEY])

        // Удаление корректировки тела из журнала откатывает её (тело обратно +19,73)
        val cs = loadCredits(prefs)
        Annuity.reverseEffect(cs, pct, now)
        Annuity.reverseEffect(cs, body, now)
        val w = cs.single { it.name == "Вб_2" }
        assertEquals(25879.21, w.principal, 1e-9)
        assertEquals(0.0, w.accruedInterest, 1e-9)
    }

    @Test
    fun wb2Fix_guardMismatch_doesNothing() {
        val prefs = prefs116(wb2Principal = 25879.22)
        val txBefore = loadTx(prefs)
        launch(prefs); launch(prefs)
        val wb2 = loadCredits(prefs).single { it.name == "Вб_2" }
        assertEquals(25879.22, wb2.principal, 0.0)
        assertEquals(0.0, wb2.accruedInterest, 0.0)
        assertEquals(txBefore, loadTx(prefs))
        // график проставлен, платёж пересчитан по текущему телу
        assertEquals(Migrations.moscowDate(2031, 8, 22), wb2.endDate)
        assertEquals(Annuity.payment(25879.22, 26.0, 59), wb2.monthlyPayment, 0.0)
        assertFalse(prefs.map.containsKey(Migrations.WB2_PREPAY_FIX_KEY))
    }

    @Test
    fun oldJson_parsesWithDefaults_andRoundTripsNewFields() {
        val o = credit116("x", "Кредит", 1000.0, 10.0, false, 100.0, 0.0, 12)
        val c = Persistence.creditFromJson(o)
        assertEquals(0L, c.endDate)
        assertTrue(c.recalcPaymentAfterPrepay)
        c.endDate = Migrations.moscowDate(2030, 1, 15); c.recalcPaymentAfterPrepay = false
        val back = Persistence.creditFromJson(Persistence.creditToJson(c))
        assertEquals(c, back)
    }

    @Test
    fun exportImport_keepsNewFields() {
        val prefs = prefs116()
        launch(prefs)
        val snap = DataBackup.Snapshot(
            credits = loadCredits(prefs), transactions = loadTx(prefs),
            subscriptions = mutableListOf<Subscription>().also { Persistence.loadSubscriptions(prefs, it) },
            savingsRate = 16.5f, savingsLastInterestDate = day24, defaultsSeeded = true
        )
        val parsed = DataBackup.parseJson(DataBackup.buildJson(prefs, snap))
        assertEquals(snap.credits, parsed.credits)
        assertEquals(snap.transactions, parsed.transactions)
        assertEquals(snap.subscriptions, parsed.subscriptions)
        assertNotNull(parsed.credits.single { it.name == "Вб_2" }.endDate.takeIf { it > 0 })
    }

    @Test
    fun freshInstall_seedHasAnnuityFields() {
        val prefs = FakeSharedPreferences()
        val credits = mutableListOf<Credit>()
        val txs = mutableListOf<Transaction>()
        assertTrue(Persistence.ensureDefaultSeed(prefs, credits, txs))
        val wb1 = credits.single { it.name == "Вб_1" }
        val wb2 = credits.single { it.name == "Вб_2" }
        assertEquals(12, wb1.paymentDay); assertEquals(Migrations.moscowDate(2031, 8, 8), wb1.endDate)
        assertEquals(26, wb2.paymentDay); assertEquals(Migrations.moscowDate(2031, 8, 22), wb2.endDate)
        assertTrue(wb1.monthlyPayment > 0 && wb2.monthlyPayment > 0)
        assertEquals(0L, credits.single { it.name == "WB Кэш" }.endDate)
        // повторный сид — no-op
        assertFalse(Persistence.ensureDefaultSeed(prefs, credits, txs))
    }
}
