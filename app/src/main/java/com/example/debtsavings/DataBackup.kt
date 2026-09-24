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
 * exportVersion=1: credits, transactions, subscriptions + ключевые prefs.
 */
object DataBackup {
    const val EXPORT_VERSION = 1
    private val UTF8: Charset = Charsets.UTF_8

    data class Snapshot(
        val credits: List<Credit>,
        val transactions: List<Transaction>,
        val subscriptions: List<Subscription>,
        val savingsRate: Float,
        val savingsLastInterestDate: Long,
        val defaultsSeeded: Boolean
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
        val seeded = prefsObj.optBoolean(Persistence.DEFAULTS_SEEDED_KEY, true)

        return Snapshot(
            credits = credits,
            transactions = transactions,
            subscriptions = subscriptions,
            savingsRate = savingsRate,
            savingsLastInterestDate = savingsLast,
            defaultsSeeded = seeded
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
