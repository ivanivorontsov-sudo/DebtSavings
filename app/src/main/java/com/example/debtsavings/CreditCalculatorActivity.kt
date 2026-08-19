package com.example.debtsavings

import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.pow

class CreditCalculatorActivity : AppCompatActivity() {

    private lateinit var editRate: EditText
    private lateinit var editTerm: EditText
    private lateinit var editPayment: EditText
    private lateinit var textResult: TextView

    private val prefs by lazy { getSharedPreferences("debt_savings_prefs", Context.MODE_PRIVATE) }
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credit)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        editRate = findViewById(R.id.editRate)
        editTerm = findViewById(R.id.editTerm)
        editPayment = findViewById(R.id.editPayment)
        textResult = findViewById(R.id.textResult)

        // Pre-fill from saved settings
        val rate = prefs.getFloat("credit_rate", 0f)
        val term = prefs.getInt("credit_term", 0)
        val payment = prefs.getFloat("credit_payment", 0f)
        if (rate > 0) editRate.setText(rate.toString())
        if (term > 0) editTerm.setText(term.toString())
        if (payment > 0) editPayment.setText(payment.toString())

        findViewById<MaterialButton>(R.id.btnCalculate).setOnClickListener { calculate() }
        findViewById<MaterialButton>(R.id.btnApply).setOnClickListener { applyCredit() }
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { clearCredit() }
        findViewById<MaterialButton>(R.id.btnMakePayment).setOnClickListener { makePayment() }

        // Show current status
        updateStatus()
    }

    private fun calculate() {
        val rate = editRate.text.toString().replace(',', '.').toDoubleOrNull()
        val term = editTerm.text.toString().toIntOrNull()
        val payment = editPayment.text.toString().replace(',', '.').toDoubleOrNull()

        if (rate == null || rate <= 0 || term == null || term <= 0 || payment == null || payment <= 0) {
            Toast.makeText(this, "Заполните все поля корректно", Toast.LENGTH_SHORT).show()
            return
        }

        // Approximate principal from annuity payment formula (reversed)
        // PMT = P * r * (1+r)^n / ((1+r)^n - 1)
        // => P = PMT * ((1+r)^n - 1) / (r * (1+r)^n)
        val monthlyRate = rate / 100.0 / 12.0
        val n = term.toDouble()
        val factor = (1 + monthlyRate).pow(n)
        val principal = if (monthlyRate > 0) {
            payment * (factor - 1) / (monthlyRate * factor)
        } else {
            payment * n
        }

        val totalPaid = payment * n
        val overpayment = totalPaid - principal
        val dailyRate = rate / 100.0 / 365.0
        val dailyInterest = principal * dailyRate

        val sb = StringBuilder()
        sb.append("Ориентировочная сумма кредита (тело):\n")
        sb.append("${moneyFormat.format(principal)} ₽\n\n")
        sb.append("Всего выплат за $term мес.:\n")
        sb.append("${moneyFormat.format(totalPaid)} ₽\n\n")
        sb.append("Общая переплата:\n")
        sb.append("${moneyFormat.format(overpayment)} ₽\n\n")
        sb.append("Проценты в день (на текущее тело):\n")
        sb.append("≈ ${moneyFormat.format(dailyInterest)} ₽")

        textResult.text = sb.toString()
    }

    private fun applyCredit() {
        val rate = editRate.text.toString().replace(',', '.').toDoubleOrNull()
        val term = editTerm.text.toString().toIntOrNull()
        val payment = editPayment.text.toString().replace(',', '.').toDoubleOrNull()

        if (rate == null || rate <= 0 || term == null || term <= 0 || payment == null || payment <= 0) {
            Toast.makeText(this, "Заполните все поля корректно", Toast.LENGTH_SHORT).show()
            return
        }

        // Current debt as starting principal (if negative balance)
        val txJson = prefs.getString("transactions", "[]") ?: "[]"
        var balance = 0.0
        try {
            val arr = org.json.JSONArray(txJson)
            for (i in 0 until arr.length()) {
                balance += arr.getJSONObject(i).getDouble("amount")
            }
        } catch (_: Exception) {}

        val principal = if (balance < 0) -balance else 0.0

        prefs.edit()
            .putBoolean("credit_active", true)
            .putFloat("credit_rate", rate.toFloat())
            .putInt("credit_term", term)
            .putFloat("credit_payment", payment.toFloat())
            .putFloat("credit_principal", principal.toFloat())
            .putLong("last_interest_date", System.currentTimeMillis())
            .apply()

        Toast.makeText(this, "Кредит применён. Тело = ${moneyFormat.format(principal)} ₽", Toast.LENGTH_LONG).show()
        updateStatus()
        calculate()
    }

    private fun clearCredit() {
        prefs.edit()
            .putBoolean("credit_active", false)
            .putFloat("credit_rate", 0f)
            .putInt("credit_term", 0)
            .putFloat("credit_payment", 0f)
            .putFloat("credit_principal", 0f)
            .putLong("last_interest_date", 0L)
            .apply()
        Toast.makeText(this, "Настройки кредита сброшены", Toast.LENGTH_SHORT).show()
        updateStatus()
        textResult.text = ""
    }

    private fun makePayment() {
        val payment = editPayment.text.toString().replace(',', '.').toDoubleOrNull()
        if (payment == null || payment <= 0) {
            Toast.makeText(this, "Укажите сумму платежа", Toast.LENGTH_SHORT).show()
            return
        }

        val active = prefs.getBoolean("credit_active", false)
        if (!active) {
            Toast.makeText(this, "Сначала примените настройки кредита", Toast.LENGTH_SHORT).show()
            return
        }

        var principal = prefs.getFloat("credit_principal", 0f).toDouble()
        val reduce = minOf(payment, principal)
        principal = (principal - reduce).coerceAtLeast(0.0)

        // Also add positive transaction to main balance
        val txJson = prefs.getString("transactions", "[]") ?: "[]"
        val arr = try { org.json.JSONArray(txJson) } catch (_: Exception) { org.json.JSONArray() }
        val o = org.json.JSONObject()
        o.put("id", java.util.UUID.randomUUID().toString())
        o.put("amount", payment)   // positive = reduces debt
        o.put("timestamp", System.currentTimeMillis())
        o.put("note", "Платёж по кредиту")
        // Insert at beginning
        val newArr = org.json.JSONArray()
        newArr.put(o)
        for (i in 0 until arr.length()) newArr.put(arr.get(i))

        prefs.edit()
            .putString("transactions", newArr.toString())
            .putFloat("credit_principal", principal.toFloat())
            .apply()

        Toast.makeText(this, "Платёж ${moneyFormat.format(payment)} ₽ внесён. Тело = ${moneyFormat.format(principal)} ₽", Toast.LENGTH_LONG).show()
        updateStatus()
    }

    private fun updateStatus() {
        val active = prefs.getBoolean("credit_active", false)
        val principal = prefs.getFloat("credit_principal", 0f)
        val rate = prefs.getFloat("credit_rate", 0f)
        val term = prefs.getInt("credit_term", 0)

        if (active) {
            textResult.text = "Кредит активен\n" +
                    "Тело долга: ${moneyFormat.format(principal)} ₽\n" +
                    "Ставка: $rate % · Срок: $term мес."
        } else {
            textResult.text = "Кредит не настроен"
        }
    }
}
