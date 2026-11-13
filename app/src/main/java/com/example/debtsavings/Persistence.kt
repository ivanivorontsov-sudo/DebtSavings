package com.example.debtsavings

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Единая сериализация кредитов/операций/подписок.
 * Старые кредиты без новых полей: accruedInterest=0, accruedFees=0, graceMode=false и т.д.
 */
object Persistence {
    const val PREFS_NAME = "debt_savings_prefs"
    const val TRANSACTIONS_KEY = "transactions"
    const val CREDITS_KEY = "credits"
    const val SUBSCRIPTIONS_KEY = "subscriptions"
    const val DEFAULTS_SEEDED_KEY = "defaults_seeded_v1"

    fun loadTransactions(prefs: SharedPreferences, target: MutableList<Transaction>) {
        target.clear()
        val json = prefs.getString(TRANSACTIONS_KEY, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                target.add(
                    Transaction(
                        id = o.getString("id"),
                        amount = o.getDouble("amount"),
                        timestamp = o.getLong("timestamp"),
                        note = o.optString("note", ""),
                        creditId = if (o.has("creditId") && !o.isNull("creditId")) o.getString("creditId") else null
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    fun saveTransactions(prefs: SharedPreferences, transactions: List<Transaction>) {
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

    fun loadCredits(prefs: SharedPreferences, target: MutableList<Credit>) {
        target.clear()
        val json = prefs.getString(CREDITS_KEY, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                target.add(creditFromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
        }
    }

    fun saveCredits(prefs: SharedPreferences, credits: List<Credit>) {
        val arr = JSONArray()
        credits.forEach { arr.put(creditToJson(it)) }
        prefs.edit().putString(CREDITS_KEY, arr.toString()).apply()
    }

    fun creditToJson(c: Credit): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("rate", c.rate)
        put("termMonths", c.termMonths)
        put("monthlyPayment", c.monthlyPayment)
        put("principal", c.principal)
        put("lastInterestDate", c.lastInterestDate)
        put("accruedInterest", c.accruedInterest)
        put("accruedFees", c.accruedFees)
        put("graceMode", c.graceMode)
        put("principalSharePercent", c.principalSharePercent)
        put("minPrincipalPaymentFloor", c.minPrincipalPaymentFloor)
        put("paymentDay", c.paymentDay)
        put("nextMinPaymentDue", c.nextMinPaymentDue)
        put("autoApplyMinPayment", c.autoApplyMinPayment)
        put("contractStartDate", c.contractStartDate)
        put("lastFeeYearMonth", c.lastFeeYearMonth)
        put("lastMinPaymentPeriodKey", c.lastMinPaymentPeriodKey)
        put("endDate", c.endDate)
        put("recalcPaymentAfterPrepay", c.recalcPaymentAfterPrepay)
    }

    /** Миграция: отсутствующие поля → безопасные значения по умолчанию. */
    fun creditFromJson(o: JSONObject): Credit = Credit(
        id = o.getString("id"),
        name = o.getString("name"),
        rate = o.getDouble("rate"),
        termMonths = o.getInt("termMonths"),
        monthlyPayment = o.getDouble("monthlyPayment"),
        principal = o.getDouble("principal"),
        lastInterestDate = o.optLong("lastInterestDate", 0L),
        accruedInterest = o.optDouble("accruedInterest", 0.0),
        accruedFees = o.optDouble("accruedFees", 0.0),
        graceMode = o.optBoolean("graceMode", false),
        principalSharePercent = o.optDouble("principalSharePercent", 0.0),
        minPrincipalPaymentFloor = o.optDouble("minPrincipalPaymentFloor", WbCreditMath.DEFAULT_MIN_PRINCIPAL_FLOOR),
        paymentDay = o.optInt("paymentDay", WbCreditMath.DEFAULT_PAYMENT_DAY),
        nextMinPaymentDue = o.optLong("nextMinPaymentDue", 0L),
        autoApplyMinPayment = o.optBoolean("autoApplyMinPayment", false),
        contractStartDate = o.optLong("contractStartDate", 0L),
        lastFeeYearMonth = o.optInt("lastFeeYearMonth", 0),
        lastMinPaymentPeriodKey = o.optInt("lastMinPaymentPeriodKey", 0),
        // v1.1.7: аннуитет (нет в старом JSON → без графика, пересчёт по умолчанию вкл.)
        endDate = o.optLong("endDate", 0L),
        recalcPaymentAfterPrepay = o.optBoolean("recalcPaymentAfterPrepay", true)
    )

    fun loadSubscriptions(prefs: SharedPreferences, target: MutableList<Subscription>) {
        target.clear()
        val json = prefs.getString(SUBSCRIPTIONS_KEY, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                target.add(subscriptionFromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
        }
    }

    fun saveSubscriptions(prefs: SharedPreferences, items: List<Subscription>) {
        val arr = JSONArray()
        items.forEach { arr.put(subscriptionToJson(it)) }
        prefs.edit().putString(SUBSCRIPTIONS_KEY, arr.toString()).apply()
    }

    fun subscriptionToJson(s: Subscription): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("price", s.price)
        put("period", s.period.name)
        put("customDays", s.customDays)
        put("nextChargeDate", s.nextChargeDate)
        put("notes", s.notes)
        put("intent", s.intent.name)
        put("notifyDays", s.notifyDays)
        put("enabled", s.enabled)
        put("category", s.category.name)
    }

    fun subscriptionFromJson(o: JSONObject): Subscription = Subscription(
        id = o.getString("id"),
        name = o.getString("name"),
        price = o.getDouble("price"),
        period = try {
            BillingPeriod.valueOf(o.optString("period", BillingPeriod.MONTHLY.name))
        } catch (_: Exception) {
            BillingPeriod.MONTHLY
        },
        customDays = o.optInt("customDays", 30),
        nextChargeDate = o.optLong("nextChargeDate", 0L),
        notes = o.optString("notes", ""),
        intent = try {
            SubscriptionIntent.valueOf(o.optString("intent", SubscriptionIntent.NEED_PAY.name))
        } catch (_: Exception) {
            SubscriptionIntent.NEED_PAY
        },
        notifyDays = o.optString("notifyDays", "7,3,1"),
        enabled = o.optBoolean("enabled", true),
        category = try {
            SubscriptionCategory.valueOf(o.optString("category", SubscriptionCategory.THIRD_PARTY.name))
        } catch (_: Exception) {
            SubscriptionCategory.THIRD_PARTY
        }
    )

}
