package com.example.debtsavings

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.util.Locale

class SavingsCalculatorActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("debt_savings_prefs", Context.MODE_PRIVATE) }
    private val money = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply { maximumFractionDigits = 2 }
    private lateinit var rateInput: EditText
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Накопительный счёт"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val titleView = TextView(this).apply {
            text = "Накопительный счёт"
            textSize = 26f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(titleView, LinearLayout.LayoutParams(-1, -2))

        val currentRate = prefs.getFloat("savings_rate", 0f)
        rateInput = EditText(this).apply {
            hint = "Ставка, % годовых"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (currentRate == 0f) "" else currentRate.toString())
        }
        root.addView(rateInput, LinearLayout.LayoutParams(-1, -2))

        val save = Button(this).apply {
            text = "Сохранить ставку"
            setOnClickListener { saveRate() }
        }
        root.addView(save, LinearLayout.LayoutParams(-1, -2))

        info = TextView(this).apply { textSize = 18f; setPadding(0, 28, 0, 0) }
        root.addView(info, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        updateInfo()
    }

    private fun saveRate() {
        val rate = rateInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (rate == null || rate < 0.0 || rate > 100.0) {
            rateInput.error = "Введите ставку от 0 до 100%"
            return
        }
        prefs.edit().putFloat("savings_rate", rate.toFloat())
            .putLong("savings_last_interest_date", DailyInterest.startOfMoscowDay(System.currentTimeMillis()))
            .apply()
        updateInfo()
    }

    private fun updateInfo() {
        val transactions = mutableListOf<Transaction>()
        Persistence.loadTransactions(prefs, transactions)
        val balance = transactions.sumOf { it.amount }
        val rate = prefs.getFloat("savings_rate", 0f).toDouble()
        val savings = balance.coerceAtLeast(0.0)
        val daily = savings * rate / 100.0 / 365.0
        info.text = "Баланс накоплений: ${money.format(savings)} ₽\n" +
                "Ставка: ${money.format(rate)}% годовых\n" +
                "≈ ${money.format(daily)} ₽ в день\n" +
                "≈ ${money.format(daily * 30)} ₽ за 30 дней\n" +
                "≈ ${money.format(daily * 365)} ₽ за год без учёта капитализации\n\n" +
                "Проценты начисляются ежедневно и добавляются в журнал как положительная операция. Следующий день рассчитывается уже с учётом начисленных процентов."
    }
}
