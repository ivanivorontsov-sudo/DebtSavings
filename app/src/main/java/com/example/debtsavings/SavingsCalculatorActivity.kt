package com.example.debtsavings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.util.Locale

class SavingsCalculatorActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE) }
    private val money = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }
    private lateinit var rateInput: EditText
    private lateinit var goalInput: EditText
    private lateinit var contributionInput: EditText
    private lateinit var expenseInput: EditText
    private lateinit var reserveMonthsInput: EditText
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Накопления и цели"

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        content.addView(TextView(this).apply {
            text = "Накопления и финансовый резерв"
            textSize = 24f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        content.addView(TextView(this).apply {
            text = "Заполните только то, что знаете. Расчёт показывает сценарий, а не обещанный доход банка."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        })

        rateInput = addField(content, "Ставка накоплений, % годовых", prefs.getFloat("savings_rate", 0f).toString())
        goalInput = addField(content, "Целевая сумма, ₽ (необязательно)", prefs.getFloat("savings_goal_target", 0f).takeIf { it > 0f }?.toString().orEmpty())
        contributionInput = addField(content, "Плановый взнос в месяц, ₽", prefs.getFloat("savings_monthly_contribution", 0f).takeIf { it > 0f }?.toString().orEmpty())
        expenseInput = addField(content, "Необходимые расходы в месяц, ₽", prefs.getFloat("monthly_essential_expenses", 0f).takeIf { it > 0f }?.toString().orEmpty())
        reserveMonthsInput = addField(content, "Сколько месяцев расходов покрыть (1–24)", prefs.getInt("emergency_months", 3).toString(), decimal = false)

        content.addView(Button(this).apply {
            text = "Сохранить и пересчитать"
            setOnClickListener { saveSettings() }
        }, LinearLayout.LayoutParams(-1, -2))

        info = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(18), 0, dp(12))
        }
        content.addView(info, LinearLayout.LayoutParams(-1, -2))

        content.addView(Button(this).apply {
            text = "Материалы по финансовому резерву"
            setOnClickListener { openUrl("https://www.consumerfinance.gov/an-essential-guide-to-building-an-emergency-fund/") }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(Button(this).apply {
            text = "Финансовая культура Банка России"
            setOnClickListener { openUrl("https://fincult.info/") }
        }, LinearLayout.LayoutParams(-1, -2))

        setContentView(ScrollView(this).apply { addView(content) })
        updateInfo()
    }

    private fun addField(parent: LinearLayout, hintText: String, value: String, decimal: Boolean = true): EditText {
        val input = EditText(this).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            setSingleLine(true)
            setText(value)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        parent.addView(input, LinearLayout.LayoutParams(-1, -2))
        return input
    }

    private fun saveSettings() {
        val rate = parse(rateInput)
        val goal = parse(goalInput, blankAsZero = true)
        val contribution = parse(contributionInput, blankAsZero = true)
        val expenses = parse(expenseInput, blankAsZero = true)
        val reserveMonths = reserveMonthsInput.text.toString().trim().toIntOrNull()

        if (rate == null || rate !in 0.0..100.0) {
            rateInput.error = "Введите ставку от 0 до 100%"
            return
        }
        if (goal == null || goal < 0.0 || contribution == null || contribution < 0.0 ||
            expenses == null || expenses < 0.0
        ) {
            Toast.makeText(this, "Суммы должны быть нулём или больше", Toast.LENGTH_SHORT).show()
            return
        }
        if (reserveMonths == null || reserveMonths !in 1..24) {
            reserveMonthsInput.error = "Введите число от 1 до 24"
            return
        }

        // Начисляем прошедшие дни по старой ставке до её изменения.
        val transactions = mutableListOf<Transaction>()
        val credits = mutableListOf<Credit>()
        Persistence.loadTransactions(prefs, transactions)
        Persistence.loadCredits(prefs, credits)
        DailyInterest.applyIfNeeded(prefs, transactions, credits)

        prefs.edit()
            .putFloat("savings_rate", rate.toFloat())
            .putFloat("savings_goal_target", goal.toFloat())
            .putFloat("savings_monthly_contribution", contribution.toFloat())
            .putFloat("monthly_essential_expenses", expenses.toFloat())
            .putInt("emergency_months", reserveMonths)
            .apply()
        updateInfo()
        Toast.makeText(this, "Настройки накоплений сохранены", Toast.LENGTH_SHORT).show()
    }

    private fun updateInfo() {
        val transactions = mutableListOf<Transaction>()
        Persistence.loadTransactions(prefs, transactions)
        val savings = transactions.filter { it.creditId == null }.sumOf { it.amount }.coerceAtLeast(0.0)
        val rate = prefs.getFloat("savings_rate", 0f).toDouble()
        val goal = prefs.getFloat("savings_goal_target", 0f).toDouble()
        val contribution = prefs.getFloat("savings_monthly_contribution", 0f).toDouble()
        val expenses = prefs.getFloat("monthly_essential_expenses", 0f).toDouble()
        val reserveMonths = prefs.getInt("emergency_months", 3)
        val projection = SavingsGoalMath.project(savings, goal, contribution, rate)

        info.text = buildString {
            append("Текущие свободные накопления: \${money.format(savings)} ₽\n")
            append("Ставка: \${money.format(rate)}% годовых\n")
            append("Оценка процентов сейчас: ≈ \${money.format(savings * rate / 36500.0)} ₽ в день\n")
            append("При взносе \${money.format(contribution)} ₽/мес. через год может быть ≈ \${money.format(projection.balanceAfter12Months)} ₽.\n\n")

            if (goal > 0.0) {
                append("Личная цель: \${money.format(goal)} ₽\n")
                append(goalProgress(savings, goal))
                append("\n")
                append(monthsText(projection.monthsToTarget))
                append("\n\n")
            } else {
                append("Личная цель пока не задана.\n\n")
            }

            if (expenses > 0.0) {
                val reserveTarget = expenses * reserveMonths
                val reserveProjection = SavingsGoalMath.project(savings, reserveTarget, contribution, rate)
                append("Резерв на \${reserveMonths} мес. расходов: \${money.format(reserveTarget)} ₽\n")
                append(goalProgress(savings, reserveTarget))
                append("\n")
                append(monthsText(reserveProjection.monthsToTarget))
                append("\n\n")
            } else {
                append("Для расчёта резервного фонда укажите необходимые ежемесячные расходы.\n\n")
            }

            append("Прогноз предполагает регулярный взнос в конце месяца и приблизительную капитализацию. Реальный доход зависит от тарифа и условий банка. Размер резерва выбирайте по своей ситуации; приложение не переводит деньги автоматически.")
        }
    }

    private fun goalProgress(current: Double, target: Double): String {
        val percent = (current / target * 100.0).coerceIn(0.0, 100.0)
        val remaining = (target - current).coerceAtLeast(0.0)
        return "Готово: \${money.format(percent)}% · осталось \${money.format(remaining)} ₽"
    }

    private fun monthsText(months: Int?): String = when (months) {
        0 -> "Цель уже достигнута."
        null -> "С указанными взносами и ставкой срок достижения пока не определяется."
        else -> "Ориентир до цели: около $months мес."
    }

    private fun parse(input: EditText, blankAsZero: Boolean = false): Double? {
        val raw = input.text.toString().trim().replace(',', '.')
        if (raw.isEmpty() && blankAsZero) return 0.0
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
