package com.example.debtsavings

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.util.Locale

class DebtPlanActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE) }
    private val money = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }
    private lateinit var extraInput: EditText
    private lateinit var results: TextView
    private val credits = mutableListOf<Credit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "План погашения долгов"

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        content.addView(TextView(this).apply {
            text = "Сравнение стратегий"
            textSize = 24f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        content.addView(TextView(this).apply {
            text = "Все обязательные платежи учитываются сначала. Дополнительная сумма идёт на один выбранный долг; после его закрытия освободившийся платёж переносится дальше."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(16))
        })
        extraInput = EditText(this).apply {
            hint = "Дополнительно в месяц, ₽"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(prefs.getFloat("debt_plan_extra", 0f).takeIf { it > 0f }?.toString() ?: "")
        }
        content.addView(extraInput, LinearLayout.LayoutParams(-1, -2))

        val calculate = Button(this).apply {
            text = "Рассчитать оба варианта"
            setOnClickListener { calculatePlans() }
        }
        content.addView(calculate, LinearLayout.LayoutParams(-1, -2))

        results = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(18), 0, 0)
            text = "Добавьте кредиты и укажите сумму, которую реально можете направлять сверх минимальных платежей."
        }
        content.addView(results, LinearLayout.LayoutParams(-1, -2))

        setContentView(ScrollView(this).apply { addView(content) })
        loadCredits()
        if (credits.isNotEmpty()) calculatePlans()
    }

    override fun onResume() {
        super.onResume()
        loadCredits()
        if (::results.isInitialized && credits.isNotEmpty()) calculatePlans()
    }

    private fun loadCredits() {
        Persistence.loadCredits(prefs, credits)
    }

    private fun calculatePlans() {
        val extra = extraInput.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (extra == null || extra < 0.0 || !extra.isFinite()) {
            extraInput.error = "Введите ноль или положительную сумму"
            return
        }
        prefs.edit().putFloat("debt_plan_extra", extra.toFloat()).apply()
        if (credits.none { it.principal + it.accruedInterest + it.accruedFees > 0.01 }) {
            results.text = "Открытых долгов нет. Отличный пункт в финансовом резюме."
            return
        }
        val avalanche = DebtPayoffPlanner.calculate(credits, extra, DebtStrategy.AVALANCHE)
        val snowball = DebtPayoffPlanner.calculate(credits, extra, DebtStrategy.SNOWBALL)
        results.text = buildString {
            append("Минимальные платежи по всем кредитам: \${money.format(avalanche.monthlyMinimums)} ₽/мес.\n")
            append("Дополнительно: \${money.format(extra)} ₽/мес.\n")
            append("Исходный остаток: \${money.format(avalanche.startingDebt)} ₽\n\n")
            append(formatResult("ЛАВИНА — сначала высокая ставка", avalanche))
            append("\n\n")
            append(formatResult("СНЕЖНЫЙ КОМ — сначала маленький долг", snowball))
            append("\n\n")
            append("«Лавина» обычно снижает проценты быстрее; «снежный ком» быстрее закрывает небольшие долги и может поддерживать мотивацию. Сравните сроки и стоимость, выберите план, который реально соблюдать.")
            append("\n\n")
            append("Это сценарная оценка, а не банковский график. Расчёт использует APR/12 на тело долга, не предсказывает будущие комиссии, штрафы, изменение ставок и индивидуальные правила кредитора. Приложение не отправляет платежи в банк и не меняет ваши записи.")
        }
    }

    private fun formatResult(title: String, r: DebtPayoffResult): String = buildString {
        append(title)
        append("\n")
        when (r.status) {
            DebtPlanStatus.PAID_OFF -> append("Ориентир до закрытия: \${r.months} мес. (\${r.months / 12} г. \${r.months % 12} мес.)")
            DebtPlanStatus.NO_DEBTS -> append("Нет открытых долгов.")
            DebtPlanStatus.NO_PROGRESS -> append("План не закрывает долг: текущих платежей может не хватать даже на проценты.")
            DebtPlanStatus.LIMIT_REACHED -> append("За 600 месяцев прогноз не закрывает все долги.")
        }
        append("\nОжидаемые будущие проценты: \${money.format(r.totalInterestForecast)} ₽")
        append("\nПрогноз суммы выплат: \${money.format(r.totalPaidForecast)} ₽")
        if (r.priorityOrder.isNotEmpty()) {
            append("\nПриоритет: \${r.priorityOrder.joinToString(" → ")}")
        }
        if (r.paidOffOrder.isNotEmpty()) {
            append("\nОриентировочно закроются: \${r.paidOffOrder.joinToString(" → ")}")
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
