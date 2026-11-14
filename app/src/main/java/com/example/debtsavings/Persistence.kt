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
        autoApplyMinPayment = o.optBoolean("autoApplyMinPayment", true),
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

    /**
     * Первый запуск: если кредитов нет и флаг [DEFAULTS_SEEDED_KEY] ещё не ставили —
     * заполняем живыми остатками пользователя. Существующие данные не трогаем.
     * Вызывать после load, до DailyInterest.
     * @return true, если сид применили и сохранили
     */
    fun ensureDefaultSeed(
        prefs: SharedPreferences,
        credits: MutableList<Credit>,
        transactions: MutableList<Transaction>
    ): Boolean {
        if (credits.isNotEmpty()) return false
        if (prefs.getBoolean(DEFAULTS_SEEDED_KEY, false)) return false

        val now = System.currentTimeMillis()
        val today = DailyInterest.startOfMoscowDay(now)
        // Стабильные id для сидов; termMonths: 12 общим, 58 для WB-подобных.
        // Вб_1 / Вб_2 — аннуитет: дата окончания / день платежа / пересчёт (Migrations.applyWbAnnuityDefaults)
        val seeds = listOf(
            SeedSpec("seed-yandex", "Яндекс Супер Сплит", 4318.1, 0.0, true, 0.0, 0.0, 12),
            SeedSpec("seed-wb", "WB Кэш", 60000.0, 59.8, true, 60000.0, 0.0, 58),
            SeedSpec("seed-alfa", "Альфа Карта", 15000.0, 70.0, false, 300.0, 0.0, 12),
            SeedSpec("seed-tplat", "Т Платинум", 235689.0, 59.8, true, 133000.0, 0.0, 12),
            SeedSpec("seed-wb2", "Вб_2", 27702.36, 26.0, false, 836.34, 0.0, 58),
            SeedSpec("seed-wb1", "Вб_1", 145000.0, 26.0, false, 4377.59, 1459.78, 58)
        )

        credits.clear()
        transactions.clear()

        // Журнал: oldest first в списке seedTx, затем reverse → newest first в UI
        val seedTx = mutableListOf<Transaction>()
        var ts = today
        for (s in seeds) {
            credits.add(
                Credit(
                    id = s.id,
                    name = s.name,
                    rate = s.rate,
                    termMonths = s.termMonths,
                    monthlyPayment = s.monthlyPayment,
                    principal = s.principal,
                    lastInterestDate = today,
                    accruedInterest = s.accruedInterest,
                    accruedFees = 0.0,
                    graceMode = s.grace,
                    paymentDay = 25,
                    autoApplyMinPayment = true,
                    contractStartDate = today
                ).also { Migrations.applyWbAnnuityDefaults(it, now) }
            )
            seedTx.add(
                Transaction(
                    id = UUID.randomUUID().toString(),
                    amount = -s.principal,
                    timestamp = ts,
                    note = "Получен кредит: ${s.name} (тело долга)",
                    creditId = s.id
                )
            )
            ts += 1000L
            if (s.accruedInterest > 0.01) {
                seedTx.add(
                    Transaction(
                        id = UUID.randomUUID().toString(),
                        amount = -s.accruedInterest,
                        timestamp = ts,
                        note = "Начисл. %% (старт): ${s.name}",
                        creditId = s.id
                    )
                )
                ts += 1000L
            }
        }
        // newest first
        transactions.addAll(seedTx.asReversed())

        prefs.edit().putBoolean(DEFAULTS_SEEDED_KEY, true).apply()
        saveCredits(prefs, credits)
        saveTransactions(prefs, transactions)
        return true
    }

    private data class SeedSpec(
        val id: String,
        val name: String,
        val principal: Double,
        val rate: Double,
        val grace: Boolean,
        val monthlyPayment: Double,
        val accruedInterest: Double,
        val termMonths: Int
    )
}
