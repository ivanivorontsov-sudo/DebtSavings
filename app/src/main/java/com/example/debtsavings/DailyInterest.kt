package com.example.debtsavings

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for daily interest accrual.
 * Credit interest is a separate negative transaction and never increases
 * the credit principal. Savings interest is a positive transaction and is
 * added to the general balance, therefore it compounds day by day.
 */
object DailyInterest {
    private const val MOSCOW_TZ = "Europe/Moscow"
    private const val TRANSACTIONS_KEY = "transactions"
    private const val CREDITS_KEY = "credits"
    private const val SAVINGS_RATE_KEY = "savings_rate"
    private const val SAVINGS_LAST_DATE_KEY = "savings_last_interest_date"

    fun applyIfNeeded(
        prefs: SharedPreferences,
        transactions: MutableList<Transaction>,
        credits: MutableList<Credit>
    ): Boolean {
        val today = startOfMoscowDay(System.currentTimeMillis())
        var changed = false

        for (credit in credits) {
            if (credit.lastInterestDate == 0L) {
                credit.lastInterestDate = today
                changed = true
                continue
            }
            val days = TimeUnit.MILLISECONDS.toDays(today - credit.lastInterestDate).toInt()
            if (days > 0) {
                if (credit.principal > 0.0 && credit.rate > 0.0) {
                    val interest = credit.principal * (credit.rate / 100.0 / 365.0) * days
                    if (interest > 0.0) {
                        transactions.add(0, Transaction(UUID.randomUUID().toString(), -interest, today, "Проценты: ${credit.name} за $days дн.", credit.id))
                        changed = true
                    }
                }
                credit.lastInterestDate = today
                changed = true
            }
        }

        // Savings interest is positive and is added to the balance. The next day
        // is calculated from the increased balance, so capitalization is daily.
        val savingsRate = prefs.getFloat(SAVINGS_RATE_KEY, 0f).toDouble()
        val savingsLastDate = prefs.getLong(SAVINGS_LAST_DATE_KEY, 0L)
        if (savingsLastDate == 0L) {
            prefs.edit().putLong(SAVINGS_LAST_DATE_KEY, today).apply()
        } else {
            val days = TimeUnit.MILLISECONDS.toDays(today - savingsLastDate).toInt()
            if (days > 0) {
                val savingsBalance = transactions.sumOf { it.amount }.coerceAtLeast(0.0)
                if (savingsRate > 0.0 && savingsBalance > 0.0) {
                    val interest = savingsBalance * (savingsRate / 100.0 / 365.0) * days
                    if (interest > 0.0) {
                        transactions.add(0, Transaction(UUID.randomUUID().toString(), interest, today, "Проценты накопительного счёта за $days дн.", null))
                        changed = true
                    }
                }
                prefs.edit().putLong(SAVINGS_LAST_DATE_KEY, today).apply()
            }
        }

        if (changed) {
            saveTransactions(prefs, transactions)
            saveCredits(prefs, credits)
        }
        return changed
    }

    fun startOfMoscowDay(millis: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(MOSCOW_TZ))
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun saveTransactions(prefs: SharedPreferences, transactions: List<Transaction>) {
        val arr = JSONArray()
        transactions.forEach { tx ->
            JSONObject().apply {
                put("id", tx.id)
                put("amount", tx.amount)
                put("timestamp", tx.timestamp)
                put("note", tx.note)
                if (tx.creditId != null) put("creditId", tx.creditId)
                arr.put(this)
            }
        }
        prefs.edit().putString(TRANSACTIONS_KEY, arr.toString()).apply()
    }

    private fun saveCredits(prefs: SharedPreferences, credits: List<Credit>) {
        val arr = JSONArray()
        credits.forEach { c ->
            JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("rate", c.rate)
                put("termMonths", c.termMonths)
                put("monthlyPayment", c.monthlyPayment)
                put("principal", c.principal)
                put("lastInterestDate", c.lastInterestDate)
                arr.put(this)
            }
        }
        prefs.edit().putString(CREDITS_KEY, arr.toString()).apply()
    }
}
