package com.example.debtsavings

import android.content.SharedPreferences
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs

/**
 * Разовые миграции данных (v1.1.7). Только ДОБАВЛЯЮТ поля / корректирующие операции,
 * ничего не удаляют и не заменяют. Защищены флагами в тех же SharedPreferences → идемпотентны.
 */
object Migrations {
    const val ANNUITY_MIGRATION_KEY = "annuity_migration_v1"
    const val WB2_PREPAY_FIX_KEY = "wb2_prepay_fix_v1"
    const val WB2_FIX_LABEL = "Корректировка: досрочный платёж целиком в тело (как в банке)"

    private val MOSCOW: TimeZone = TimeZone.getTimeZone("Europe/Moscow")

    /** Параметры графика из приложения банка (25.09.2026). */
    private data class WbSpec(
        val endY: Int, val endM: Int, val endD: Int,
        val paymentDay: Int,
        val bankNextY: Int, val bankNextM: Int, val bankNextD: Int
    )

    private val WB_SPECS = mapOf(
        // Вб_1: до 08.08.2031, платёж 12-го, ближайший — 12.10.2026
        "Вб_1" to WbSpec(2031, 8, 8, 12, 2026, 10, 12),
        // Вб_2: до 22.08.2031, платёж 26-го, ближайший — 26.10.2026
        "Вб_2" to WbSpec(2031, 8, 22, 26, 2026, 10, 26)
    )
    const val WB_RATE = 26.0

    /**
     * Проставить график Вб_1 / Вб_2 (если у кредита ещё нет даты окончания) и пересчитать платёж.
     * @return true, если кредит изменён
     */
    fun applyWbAnnuityDefaults(c: Credit, now: Long): Boolean {
        val spec = WB_SPECS[c.name] ?: return false
        if (c.endDate > 0L) return false
        c.endDate = moscowDate(spec.endY, spec.endM, spec.endD)
        c.paymentDay = spec.paymentDay
        c.rate = WB_RATE
        c.recalcPaymentAfterPrepay = true
        // Ближайший плановый срок: как в банке, если он ещё впереди; иначе — ближайший по дню платежа
        val bankNext = moscowDate(spec.bankNextY, spec.bankNextM, spec.bankNextD)
        val today = DailyInterest.startOfMoscowDay(now)
        c.nextMinPaymentDue = if (bankNext >= today) bankNext
        else Annuity.nextScheduledOnOrAfter(now, spec.paymentDay)
        Annuity.recompute(c, now)
        return true
    }

    /**
     * Все миграции. Вызывать после load + ensureDefaultSeed, до DailyInterest.
     * [force] — прогнать аннуитетную часть даже при стоящем флаге (после импорта старого бэкапа);
     * она всё равно трогает только Вб_1/Вб_2 без даты окончания.
     * @return true, если что-то изменили и сохранили
     */
    fun runAll(
        prefs: SharedPreferences,
        credits: MutableList<Credit>,
        transactions: MutableList<Transaction>,
        now: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): Boolean {
        var creditsChanged = false
        var txChanged = false

        if (force || !prefs.getBoolean(ANNUITY_MIGRATION_KEY, false)) {
            for (c in credits) {
                if (applyWbAnnuityDefaults(c, now)) creditsChanged = true
            }
            prefs.edit().putBoolean(ANNUITY_MIGRATION_KEY, true).apply()
        }

        if (!prefs.getBoolean(WB2_PREPAY_FIX_KEY, false)) {
            if (applyWb2PrepayFix(credits, transactions, now)) {
                creditsChanged = true
                txChanged = true
                prefs.edit().putBoolean(WB2_PREPAY_FIX_KEY, true).apply()
            }
        }

        if (creditsChanged) Persistence.saveCredits(prefs, credits)
        if (txChanged) Persistence.saveTransactions(prefs, transactions)
        return creditsChanged || txChanged
    }

    /**
     * Разовая корректировка Вб_2: платёж 1 842,88 ₽ от 25.09.2026 приложение разбило «% 19,73 · тело 1 823,15»,
     * а банк зачёл его целиком в тело. Строгая защита:
     *  — тело Вб_2 ровно 25 879,21;
     *  — в журнале есть платёж 1 842,88 ₽ по Вб_2 от 25.09.2026 с 19,73 ₽ в %;
     *  — корректировки ещё нет в журнале.
     * Иначе ничего не делаем.
     */
    fun applyWb2PrepayFix(
        credits: MutableList<Credit>,
        transactions: MutableList<Transaction>,
        now: Long
    ): Boolean {
        val c = credits.firstOrNull { it.name == "Вб_2" } ?: return false
        if (abs(c.principal - 25879.21) > 0.005) return false
        if (transactions.any { it.note.contains(WB2_FIX_LABEL) }) return false
        val payTx = transactions.firstOrNull { tx ->
            tx.creditId == c.id &&
                abs(tx.amount - 1842.88) < 0.005 &&
                isDate20260925(tx.timestamp) &&
                interestPartOf(tx.note)?.let { abs(it - 19.73) < 0.005 } == true
        } ?: return false

        val delta = 19.73
        val money = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
            maximumFractionDigits = 2; minimumFractionDigits = 0
        }
        c.principal = Annuity.round2(c.principal - delta)          // 25 859,48
        c.accruedInterest = Annuity.round2(c.accruedInterest + delta)
        // Конвенция журнала: amount = −delta
        val tBody = maxOf(now, payTx.timestamp + 1)
        transactions.add(
            0,
            Transaction(
                UUID.randomUUID().toString(), delta, tBody,
                "Корректировка тела: ${c.name} (-${money.format(delta)} ₽) · $WB2_FIX_LABEL", c.id
            )
        )
        transactions.add(
            0,
            Transaction(
                UUID.randomUUID().toString(), -delta, tBody + 1,
                "Корректировка %: ${c.name} (+${money.format(delta)} ₽) · $WB2_FIX_LABEL", c.id
            )
        )
        Annuity.recompute(c, now)
        return true
    }

    /** «Из платежа: % 19,73 ₽ · тело …» → 19.73 (терпимо к разным пробелам/NBSP). */
    fun interestPartOf(note: String): Double? {
        val compact = note.filterNot { it.isWhitespace() || it == '\u00A0' || it == '\u202F' || it == '\u2007' }
        val m = Regex("""%(\d+(?:[.,]\d{1,2})?)₽""").find(compact) ?: return null
        return m.groupValues[1].replace(',', '.').toDoubleOrNull()
    }

    private fun isDate20260925(ts: Long): Boolean {
        for (tz in listOf(MOSCOW, TimeZone.getDefault())) {
            val cal = Calendar.getInstance(tz)
            cal.timeInMillis = ts
            if (cal.get(Calendar.YEAR) == 2026 && cal.get(Calendar.MONTH) == Calendar.SEPTEMBER &&
                cal.get(Calendar.DAY_OF_MONTH) == 25
            ) return true
        }
        return false
    }

    fun moscowDate(y: Int, m: Int, d: Int): Long {
        val cal = Calendar.getInstance(MOSCOW)
        cal.clear()
        cal.set(y, m - 1, d, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
