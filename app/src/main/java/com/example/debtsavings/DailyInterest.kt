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
 * All dates are calculated in Moscow time, regardless of the phone's timezone.
 */
object DailyInterest {
    private const val MOSCOW_TZ = "Europe/Moscow"
    private const val TRANSACTIONS_KEY = "transactions"
    private const val CREDITS_KEY = "credits"

    /** Applies every missed Moscow calendar day exactly once and persists the result. */
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
            if (days <= 0) continue

            if (credit.principal > 0.0 && credit.rate > 0.0) {
                // Existing app logic uses simple daily accrual: annual rate / 365.
                val dailyRate = credit.rate / 100.0 / 365.0
                val interest = credit.principal * dailyRate * days

                if (interest > 0.0) {
                    transactions.add(
                        0,
                        Transaction(
                            id = UUID.randomUUID().toString(),
                            amount = -interest,
                            timestamp = today,
                            note = "Проценты: ${credit.name} за $days дн.",
                            creditId = credit.id
                        )
                    )
                    credit.principal += interest
                    changed = true
                }
            }

            credit.lastInterestDate = today
            changed = true
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
            val o = JSONObject()
            o.put("id", tx.id)
            o.put("amount", tx.amount)
            o.put("timestamp", tx.timestamp)
            o.put("note", tx.note)
            if (tx.creditId != null) o.put("creditId", tx.creditId)
            arr.put(o)
        }
        prefs.edit().putString(TRANSACTIONS_KEY, arr.toString()).apply()
    }

    private fun saveCredits(prefs: SharedPreferences, credits: List<Credit>) {
        val arr = JSONArray()
        credits.forEach { c ->
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("rate", c.rate)
            o.put("termMonths", c.termMonths)
            o.put("monthlyPayment", c.monthlyPayment)
            o.put("principal", c.principal)
            o.put("lastInterestDate", c.lastInterestDate)
            arr.put(o)
        }
        prefs.edit().putString(CREDITS_KEY, arr.toString()).apply()
    }
}
