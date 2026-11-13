package com.example.debtsavings

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Экспорт / импорт всех данных приложения в один JSON-файл.
 * exportVersion=2: credits, transactions, subscriptions + настройки накоплений и плана погашения.
 */
object DataBackup {
    const val EXPORT_VERSION = 2
    private val UTF8: Charset = Charsets.UTF_8

    data class Snapshot(
        val credits: List<Credit>,
        val transactions: List<Transaction>,
        val subscriptions: List<Subscription>,
        val savingsRate: Float,
        val savingsLastInterestDate: Long,
        val defaultsSeeded: Boolean,
        val savingsGoalTarget: Float = 0f,
        val savingsMonthlyContribution: Float = 0f,
        val monthlyEssentialExpenses: Float = 0f,
        val emergencyMonths: Int = 3,
        val debtPlanExtra: Float = 0f
    )

    fun buildJson(prefs: SharedPreferences, snapshot: Snapshot): String {
        val root = JSONObject()
        root.put("exportVersion", EXPORT_VERSION)
        root.put("appId", "com.example.debtsavings")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Europe/Moscow")
        }
        root.put("exportedAt", iso.format(Date()))
        root.put("exportedAtMillis", System.currentTimeMillis())

        val creditsArr = JSONArray()
        snapshot.credits.forEach { creditsArr.put(Persistence.creditToJson(it)) }
        root.put("credits", creditsArr)

        val txArr = JSONArray()
        snapshot.transactions.forEach { tx ->
            txArr.put(JSONObject().apply {
                put("id", tx.id)
                put("amount", tx.amount)
                put("timestamp", tx.timestamp)
                put("note", tx.note)
                if (tx.creditId != null) put("creditId", tx.creditId)
            })
        }
        root.put("transactions", txArr)

        val subArr = JSONArray()
        snapshot.subscriptions.forEach { subArr.put(Persistence.subscriptionToJson(it)) }
        root.put("subscriptions", subArr)

        root.put("prefs", JSONObject().apply {
            put("savings_rate", snapshot.savingsRate.toDouble())
            put("savings_last_interest_date", snapshot.savingsLastInterestDate)
            put("savings_goal_target", snapshot.savingsGoalTarget.toDouble())
            put("savings_monthly_contribution", snapshot.savingsMonthlyContribution.toDouble())
            put("monthly_essential_expenses", snapshot.monthlyEssentialExpenses.toDouble())
            put("emergency_months", snapshot.emergencyMonths)
            put("debt_plan_extra", snapshot.debtPlanExtra.toDouble())
            put(Persistence.DEFAULTS_SEEDED_KEY, snapshot.defaultsSeeded)
        })
        return root.toString(2)
    }

    fun writeToStream(prefs: SharedPreferences, snapshot: Snapshot, out: OutputStream) {
        out.write(buildJson(prefs, snapshot).toByteArray(UTF8))
        out.flush()
    }

    fun parse(input: InputStream): Snapshot {
        val text = input.bufferedReader(UTF8).use { it.readText() }
        return parseJson(text)
    }

    fun parseJson(text: String): Snapshot {
        val root = JSONObject(text)
        val ver = root.optInt("exportVersion", 0)
        if (ver < 1) throw IllegalArgumentException("Неизвестный формат (exportVersion=$ver)")

        val credits = mutableListOf<Credit>()
        val creditsArr = root.optJSONArray("credits") ?: JSONArray()
        for (i in 0 until creditsArr.length()) {
            credits.add(Persistence.creditFromJson(creditsArr.getJSONObject(i)))
        }

        val transactions = mutableListOf<Transaction>()
        val txArr = root.optJSONArray("transactions") ?: JSONArray()
        for (i in 0 until txArr.length()) {
            val o = txArr.getJSONObject(i)
            transactions.add(
                Transaction(
                    id = o.getString("id"),
                    amount = o.getDouble("amount"),
                    timestamp = o.getLong("timestamp"),
                    note = o.optString("note", ""),
                    creditId = if (o.has("creditId") && !o.isNull("creditId")) o.getString("creditId") else null
                )
            )
        }

        val subscriptions = mutableListOf<Subscription>()
        val subArr = root.optJSONArray("subscriptions") ?: JSONArray()
        for (i in 0 until subArr.length()) {
            subscriptions.add(Persistence.subscriptionFromJson(subArr.getJSONObject(i)))
        }

        val prefsObj = root.optJSONObject("prefs") ?: JSONObject()
        val savingsRate = prefsObj.optDouble("savings_rate", 0.0).toFloat()
        val savingsLast = prefsObj.optLong("savings_last_interest_date", 0L)
        val goalTarget = prefsObj.optDouble("savings_goal_target", 0.0).toFloat()
        val monthlyContribution = prefsObj.optDouble("savings_monthly_contribution", 0.0).toFloat()
        val essentialExpenses = prefsObj.optDouble("monthly_essential_expenses", 0.0).toFloat()
        val emergencyMonths = prefsObj.optInt("emergency_months", 3).coerceIn(1, 24)
        val debtPlanExtra = prefsObj.optDouble("debt_plan_extra", 0.0).toFloat()
        val seeded = prefsObj.optBoolean(Persistence.DEFAULTS_SEEDED_KEY, true)

        return Snapshot(
            credits = credits,
            transactions = transactions,
            subscriptions = subscriptions,
            savingsRate = savingsRate,
            savingsLastInterestDate = savingsLast,
            defaultsSeeded = seeded,
            savingsGoalTarget = goalTarget,
            savingsMonthlyContribution = monthlyContribution,
            monthlyEssentialExpenses = essentialExpenses,
            emergencyMonths = emergencyMonths,
            debtPlanExtra = debtPlanExtra
        )
    }

    /** Применяет снимок в SharedPreferences. Не трогает UI. */
    fun apply(prefs: SharedPreferences, snapshot: Snapshot) {
        Persistence.saveCredits(prefs, snapshot.credits)
        Persistence.saveTransactions(prefs, snapshot.transactions)
        Persistence.saveSubscriptions(prefs, snapshot.subscriptions)
        prefs.edit()
            .putFloat("savings_rate", snapshot.savingsRate)
            .putLong("savings_last_interest_date", snapshot.savingsLastInterestDate)
            .putFloat("savings_goal_target", snapshot.savingsGoalTarget)
            .putFloat("savings_monthly_contribution", snapshot.savingsMonthlyContribution)
            .putFloat("monthly_essential_expenses", snapshot.monthlyEssentialExpenses)
            .putInt("emergency_months", snapshot.emergencyMonths)
            .putFloat("debt_plan_extra", snapshot.debtPlanExtra)
            .putBoolean(Persistence.DEFAULTS_SEEDED_KEY, snapshot.defaultsSeeded)
            .apply()
    }

    fun defaultFileName(): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Europe/Moscow")
        }
        return "DebtSavings-${fmt.format(Date())}.json"
    }
}
