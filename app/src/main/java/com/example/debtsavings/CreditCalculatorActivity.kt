package com.example.debtsavings

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class CreditListAdapter(
    private val items: MutableList<Credit>,
    private val onEdit: (Credit) -> Unit,
    private val onDelete: (Credit) -> Unit,
    private val onPay: (Credit) -> Unit
) : RecyclerView.Adapter<CreditListAdapter.VH>() {
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale("ru", "RU")).apply {
        timeZone = TimeZone.getTimeZone("Europe/Moscow")
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.creditName)
        val details: TextView = v.findViewById(R.id.creditDetails)
        val principal: TextView = v.findViewById(R.id.creditPrincipal)
        val minPayment: TextView = v.findViewById(R.id.creditMinPayment)
        val btnPay: MaterialButton = v.findViewById(R.id.btnPayCredit)
        val btnEdit: ImageButton = v.findViewById(R.id.btnEditCredit)
        val btnDelete: ImageButton = v.findViewById(R.id.btnDeleteCredit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_credit, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.name.text = c.name
        val grace = if (c.graceMode) "грейс" else "%% ${c.rate}"
        val schedule = if (Annuity.hasSchedule(c))
            " · до ${dateFormat.format(Date(c.endDate))}, платёж ${c.paymentDay}-го" +
                (if (c.recalcPaymentAfterPrepay) " (пересчёт)" else "")
        else ""
        holder.details.text =
            "$grace · авто-мин: ${if (c.autoApplyMinPayment) "да" else "нет"}$schedule"
        val balParts = mutableListOf<String>()
        if (c.principal > 0.01) balParts.add("Тело: ${moneyFormat.format(c.principal)} ₽")
        if (c.accruedInterest > 0.01) balParts.add("%: ${moneyFormat.format(c.accruedInterest)} ₽")
        if (c.accruedFees > 0.01) balParts.add("комис.: ${moneyFormat.format(c.accruedFees)} ₽")
        holder.principal.text = if (balParts.isEmpty()) "остаток 0 ₽" else balParts.joinToString(" · ")
        val min = WbCreditMath.calculateMinPayment(c)
        val due = if (c.nextMinPaymentDue > 0)
            "Срок: ${dateFormat.format(Date(c.nextMinPaymentDue))}"
        else "Срок: —"
        holder.minPayment.text =
            WbCreditMath.formatBreakdownRu(min) { moneyFormat.format(it) } + "\n$due"
        holder.btnPay.setOnClickListener { onPay(c) }
        holder.btnEdit.setOnClickListener { onEdit(c) }
        holder.btnDelete.setOnClickListener { onDelete(c) }
    }

    override fun getItemCount() = items.size
}

class CreditCalculatorActivity : AppCompatActivity() {
    private val credits = mutableListOf<Credit>()
    private lateinit var adapter: CreditListAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private val prefs by lazy { getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE) }
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale("ru", "RU")).apply {
        timeZone = TimeZone.getTimeZone("Europe/Moscow")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credit)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        recycler = findViewById(R.id.recyclerCredits)
        emptyText = findViewById(R.id.emptyCredits)
        adapter = CreditListAdapter(credits, { showCreditDialog(it) }, { confirmDelete(it) }, { showPaymentDialog(it) })
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<FloatingActionButton>(R.id.fabAddCredit).setOnClickListener { showCreditDialog(null) }
        loadCredits()
        // Подтянуть начисления / авто-мин. при открытии
        val txs = mutableListOf<Transaction>()
        Persistence.loadTransactions(prefs, txs)
        DailyInterest.applyIfNeeded(prefs, txs, credits)
        adapter.notifyDataSetChanged()
        updateEmpty()
    }

    private fun showCreditDialog(existing: Credit?) {
        val view = layoutInflater.inflate(R.layout.dialog_credit, null)
        val editName = view.findViewById<EditText>(R.id.editCreditName)
        val editRate = view.findViewById<EditText>(R.id.editRate)
        val editTerm = view.findViewById<EditText>(R.id.editTerm)
        val editPayment = view.findViewById<EditText>(R.id.editPayment)
        val editPrincipal = view.findViewById<EditText>(R.id.editPrincipal)
        val editInterest = view.findViewById<EditText>(R.id.editAccruedInterest)
        val editFees = view.findViewById<EditText>(R.id.editAccruedFees)
        val switchGrace = view.findViewById<MaterialSwitch>(R.id.switchGrace)
        val editShare = view.findViewById<EditText>(R.id.editSharePercent)
        val editFloor = view.findViewById<EditText>(R.id.editMinFloor)
        val editPayDay = view.findViewById<EditText>(R.id.editPaymentDay)
        val editNextDue = view.findViewById<EditText>(R.id.editNextDue)
        val switchAuto = view.findViewById<MaterialSwitch>(R.id.switchAutoMin)
        val editEndDate = view.findViewById<EditText>(R.id.editEndDate)
        val switchRecalc = view.findViewById<MaterialSwitch>(R.id.switchRecalc)
        val textCalc = view.findViewById<TextView>(R.id.textCalcPreview)
        val btnWb = view.findViewById<MaterialButton>(R.id.btnWbPreset)

        var nextDueMillis = existing?.nextMinPaymentDue ?: 0L
        var endDateMillis = existing?.endDate ?: 0L

        fun fillFrom(c: Credit) {
            editName.setText(c.name)
            editRate.setText(c.rate.toString())
            editTerm.setText(c.termMonths.toString())
            editPayment.setText(c.monthlyPayment.toString())
            editPrincipal.setText(c.principal.toString())
            editInterest.setText(c.accruedInterest.toString())
            editFees.setText(c.accruedFees.toString())
            switchGrace.isChecked = c.graceMode
            editShare.setText(c.principalSharePercent.toString())
            editFloor.setText(c.minPrincipalPaymentFloor.toString())
            editPayDay.setText(c.paymentDay.toString())
            switchAuto.isChecked = c.autoApplyMinPayment
            nextDueMillis = c.nextMinPaymentDue
            editNextDue.setText(if (nextDueMillis > 0) dateFormat.format(Date(nextDueMillis)) else "")
            endDateMillis = c.endDate
            editEndDate.setText(if (endDateMillis > 0) dateFormat.format(Date(endDateMillis)) else "")
            switchRecalc.isChecked = c.recalcPaymentAfterPrepay
        }

        if (existing != null) fillFrom(existing) else {
            editRate.setText(WbCreditMath.DEFAULT_ANNUAL_RATE.toString())
            editTerm.setText("12")
            editShare.setText("0")
            editFloor.setText(WbCreditMath.DEFAULT_MIN_PRINCIPAL_FLOOR.toString())
            editPayDay.setText(WbCreditMath.DEFAULT_PAYMENT_DAY.toString())
            editInterest.setText("0")
            editFees.setText("0")
            switchAuto.isChecked = true
            switchRecalc.isChecked = true
        }

        btnWb.setOnClickListener {
            val tmp = Credit(
                id = existing?.id ?: "tmp",
                name = editName.text.toString().ifBlank { "WB Кэш" },
                rate = WbCreditMath.DEFAULT_ANNUAL_RATE,
                termMonths = editTerm.text.toString().toIntOrNull() ?: 12,
                monthlyPayment = 0.0,
                principal = editPrincipal.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0,
                lastInterestDate = existing?.lastInterestDate ?: 0L,
                endDate = endDateMillis,
                recalcPaymentAfterPrepay = switchRecalc.isChecked
            )
            WbCreditMath.applyWbPreset(tmp)
            fillFrom(tmp)
            Toast.makeText(this, "Шаблон WB Банк применён", Toast.LENGTH_SHORT).show()
        }

        editNextDue.setOnClickListener {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
            if (nextDueMillis > 0) cal.timeInMillis = nextDueMillis
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal.set(y, m, d, 0, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    nextDueMillis = cal.timeInMillis
                    editNextDue.setText(dateFormat.format(Date(nextDueMillis)))
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        fun preview() {
            val pay = editPayment.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val p = editPrincipal.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val ai = editInterest.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val af = editFees.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val r = editRate.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val pd = editPayDay.text.toString().toIntOrNull() ?: WbCreditMath.DEFAULT_PAYMENT_DAY
            val tmp = Credit("x", "x", r, 1, pay, p, 0L, ai, af, switchGrace.isChecked,
                paymentDay = pd, nextMinPaymentDue = nextDueMillis,
                endDate = endDateMillis, recalcPaymentAfterPrepay = switchRecalc.isChecked)
            val now = System.currentTimeMillis()
            val annuityLine = if (Annuity.hasSchedule(tmp)) {
                val nd = Annuity.effectiveNextDue(tmp, now)
                val n = Annuity.remainingPayments(nd, tmp.endDate)
                val ap = Annuity.payment(p, r, n)
                "\nАннуитет: ${moneyFormat.format(ap)} ₽ × $n (с ${dateFormat.format(Date(nd))} по ${dateFormat.format(Date(tmp.endDate))})" +
                    if (tmp.recalcPaymentAfterPrepay) " — будет записан при сохранении" else ""
            } else ""
            val min = WbCreditMath.calculateMinPayment(tmp)
            textCalc.text = WbCreditMath.formatBreakdownRu(min) { moneyFormat.format(it) } +
                "\nСумма платежа → комис. → % → тело" + annuityLine
        }
        listOf(editPayment, editPrincipal, editInterest, editFees, editRate, editPayDay).forEach {
            it.setOnFocusChangeListener { _, _ -> preview() }
        }
        switchRecalc.setOnCheckedChangeListener { _, _ -> preview() }

        editEndDate.setOnClickListener {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
            if (endDateMillis > 0) cal.timeInMillis = endDateMillis
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal.set(y, m, d, 0, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    endDateMillis = cal.timeInMillis
                    editEndDate.setText(dateFormat.format(Date(endDateMillis)))
                    preview()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).apply {
                setButton(DatePickerDialog.BUTTON_NEUTRAL, "Очистить") { _, _ ->
                    endDateMillis = 0L
                    editEndDate.setText("")
                    preview()
                }
            }.show()
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Новый кредит" else "Редактировать кредит")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = editName.text.toString().trim().ifEmpty { "Кредит" }
                val rate = editRate.text.toString().replace(',', '.').toDoubleOrNull()
                val term = editTerm.text.toString().toIntOrNull() ?: 12
                val payment = editPayment.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val principal = editPrincipal.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val interest = editInterest.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val fees = editFees.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val share = editShare.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val floor = editFloor.text.toString().replace(',', '.').toDoubleOrNull()
                    ?: WbCreditMath.DEFAULT_MIN_PRINCIPAL_FLOOR
                val payDay = (editPayDay.text.toString().toIntOrNull() ?: WbCreditMath.DEFAULT_PAYMENT_DAY)
                    .coerceIn(1, 31)
                val recalc = switchRecalc.isChecked
                if (rate == null || rate < 0) {
                    Toast.makeText(this, "Укажите ставку", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val today = DailyInterest.startOfMoscowDay(System.currentTimeMillis())
                if (existing == null) {
                    val c = Credit(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        rate = rate,
                        termMonths = term,
                        monthlyPayment = payment,
                        principal = principal,
                        lastInterestDate = today,
                        accruedInterest = interest,
                        accruedFees = fees,
                        graceMode = switchGrace.isChecked,
                        principalSharePercent = share,
                        minPrincipalPaymentFloor = floor,
                        paymentDay = payDay,
                        nextMinPaymentDue = nextDueMillis,
                        autoApplyMinPayment = switchAuto.isChecked,
                        contractStartDate = today,
                        endDate = endDateMillis,
                        recalcPaymentAfterPrepay = recalc
                    )
                    if (c.nextMinPaymentDue <= 0L) {
                        c.nextMinPaymentDue = if (Annuity.hasSchedule(c))
                            Annuity.nextScheduledOnOrAfter(System.currentTimeMillis(), c.paymentDay)
                        else WbCreditMath.dueDateAfterBillingMonth(
                            WbCreditMath.yearMonthOf(today), c.paymentDay
                        )
                    }
                    Annuity.recompute(c)
                    credits.add(0, c)
                    addTransaction(-principal, "Получен кредит: $name (тело долга)", c.id)
                    if (interest > 0) addTransaction(-interest, "Начисл. %% (старт): $name", c.id)
                    if (fees > 0) addTransaction(-fees, "Комиссии (старт): $name", c.id)
                    adapter.notifyItemInserted(0)
                } else {
                    val oldPrincipal = existing.principal
                    val oldInterest = existing.accruedInterest
                    val oldFees = existing.accruedFees
                    val scheduleChanged = kotlin.math.abs(existing.rate - rate) > 1e-9 ||
                        existing.paymentDay != payDay || existing.endDate != endDateMillis ||
                        existing.recalcPaymentAfterPrepay != recalc ||
                        existing.nextMinPaymentDue != nextDueMillis
                    existing.name = name
                    existing.rate = rate
                    existing.termMonths = term
                    existing.monthlyPayment = payment
                    existing.principal = principal
                    existing.accruedInterest = interest
                    existing.accruedFees = fees
                    existing.graceMode = switchGrace.isChecked
                    existing.principalSharePercent = share
                    existing.minPrincipalPaymentFloor = floor
                    existing.paymentDay = payDay
                    existing.nextMinPaymentDue = nextDueMillis
                    existing.autoApplyMinPayment = switchAuto.isChecked
                    existing.endDate = endDateMillis
                    existing.recalcPaymentAfterPrepay = recalc
                    // Аннуитет: после правки тела / графика платёж пересчитывается
                    if (kotlin.math.abs(principal - oldPrincipal) > 0.001 || scheduleChanged) {
                        Annuity.recompute(existing)
                    }
                    // Синхронизация журнала с правками тела / % / комиссий
                    val dPrincipal = principal - oldPrincipal
                    if (kotlin.math.abs(dPrincipal) > 0.01) {
                        val sign = if (dPrincipal > 0) "+" else ""
                        addTransaction(
                            -dPrincipal,
                            "Корректировка тела: $name ($sign${moneyFormat.format(dPrincipal)} ₽)",
                            existing.id
                        )
                    }
                    val dInterest = interest - oldInterest
                    if (kotlin.math.abs(dInterest) > 0.01) {
                        val sign = if (dInterest > 0) "+" else ""
                        addTransaction(
                            -dInterest,
                            "Корректировка %: $name ($sign${moneyFormat.format(dInterest)} ₽)",
                            existing.id
                        )
                    }
                    val dFees = fees - oldFees
                    if (kotlin.math.abs(dFees) > 0.01) {
                        val sign = if (dFees > 0) "+" else ""
                        addTransaction(
                            -dFees,
                            "Корректировка комиссий: $name ($sign${moneyFormat.format(dFees)} ₽)",
                            existing.id
                        )
                    }
                    val idx = credits.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) adapter.notifyItemChanged(idx)
                }
                saveCredits()
                updateEmpty()
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
        preview()
    }

    private fun showPaymentDialog(credit: Credit) {
        val input = EditText(this).apply {
            hint = "Сумма платежа, ₽"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            val min = WbCreditMath.calculateMinPayment(credit)
            setText(if (min.total > 0) String.format(Locale.US, "%.2f", min.total)
            else if (credit.monthlyPayment > 0) credit.monthlyPayment.toString() else "")
            setPadding(48, 32, 48, 32)
        }
        val preview = TextView(this).apply {
            setPadding(48, 8, 48, 8)
            textSize = 13f
        }
        // Аннуитет: выбор «Плановый» / «Досрочный в тело», по умолчанию — по дате
        val hasSchedule = Annuity.hasSchedule(credit)
        val defaultEarly = hasSchedule && !Annuity.isScheduledPaymentDay(credit, System.currentTimeMillis())
        val radioPlanned = android.widget.RadioButton(this).apply {
            id = View.generateViewId(); text = "Плановый (комис. → % → тело)"
        }
        val radioEarly = android.widget.RadioButton(this).apply {
            id = View.generateViewId(); text = "Досрочный в тело"
        }
        val radioGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            setPadding(40, 0, 40, 0)
            addView(radioPlanned)
            addView(radioEarly)
            check(if (defaultEarly) radioEarly.id else radioPlanned.id)
            visibility = if (hasSchedule) View.VISIBLE else View.GONE
        }
        fun isEarly(): Boolean = hasSchedule && radioGroup.checkedRadioButtonId == radioEarly.id
        fun updatePreview() {
            val amount = input.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val early = isEarly()
            val b = if (early) Annuity.allocateEarly(
                amount, credit.accruedFees, credit.accruedInterest, credit.principal
            ) else WbCreditMath.allocatePayment(
                amount, credit.accruedFees, credit.accruedInterest, credit.principal
            )
            var text = WbCreditMath.formatAllocationRu(b) { moneyFormat.format(it) }
            if (Annuity.recalcEnabled(credit) && b.principal > 0.0) {
                val tmp = credit.copy(principal = (credit.principal - b.principal).coerceAtLeast(0.0))
                val np = if (tmp.principal > 0.01) Annuity.computePayment(tmp, System.currentTimeMillis()) else 0.0
                text += "\nНовый ежемесячный платёж: ${moneyFormat.format(np)} ₽"
            }
            preview.text = text
        }
        input.addTextChangedListener(SimpleTextWatcher { updatePreview() })
        radioGroup.setOnCheckedChangeListener { _, _ -> updatePreview() }
        updatePreview()

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(input)
            addView(radioGroup)
            addView(preview)
        }
        val min = WbCreditMath.calculateMinPayment(credit)
        val balMsg = WbCreditMath.formatBalancesRu(
            credit.principal, credit.accruedInterest, credit.accruedFees,
            { moneyFormat.format(it) },
            principalLabel = "Тело", interestLabel = "%", feesLabel = "комис."
        )
        AlertDialog.Builder(this)
            .setTitle("Платёж: ${credit.name}")
            .setMessage(
                "$balMsg\n" +
                    WbCreditMath.formatBreakdownRu(min) { moneyFormat.format(it) }
            )
            .setView(box)
            .setPositiveButton("Внести") { _, _ ->
                val amount = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(this, "Введите сумму", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val res = Annuity.applyPayment(
                    credit, amount, if (hasSchedule) isEarly() else null, System.currentTimeMillis()
                ) { moneyFormat.format(it) }
                val allocated = res.breakdown
                addTransaction(allocated.total, res.note, credit.id)
                saveCredits()
                val idx = credits.indexOfFirst { it.id == credit.id }
                if (idx >= 0) adapter.notifyItemChanged(idx)
                Toast.makeText(this, "Платёж ${moneyFormat.format(allocated.total)} ₽ внесён", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Мин.") { _, _ ->
                input.setText(String.format(Locale.US, "%.2f", min.total))
                updatePreview()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDelete(credit: Credit) {
        AlertDialog.Builder(this)
            .setTitle("Удалить «${credit.name}»?")
            .setMessage("Операции в журнале останутся.")
            .setPositiveButton("Удалить") { _, _ ->
                val idx = credits.indexOfFirst { it.id == credit.id }
                if (idx >= 0) {
                    credits.removeAt(idx)
                    adapter.notifyItemRemoved(idx)
                    saveCredits()
                    updateEmpty()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun addTransaction(amount: Double, note: String, creditId: String) {
        val txs = mutableListOf<Transaction>()
        Persistence.loadTransactions(prefs, txs)
        txs.add(0, Transaction(UUID.randomUUID().toString(), amount, System.currentTimeMillis(), note, creditId))
        Persistence.saveTransactions(prefs, txs)
    }

    private fun saveCredits() {
        Persistence.saveCredits(prefs, credits)
        PaymentReminderScheduler.scheduleNext(this)
    }

    private fun loadCredits() {
        Persistence.loadCredits(prefs, credits)
        adapter.notifyDataSetChanged()
    }

    private fun updateEmpty() {
        if (credits.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }
}

/** Минимальный TextWatcher без boilerplate. */
class SimpleTextWatcher(private val onChange: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = onChange()
}
