package com.example.debtsavings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class TransactionAdapter(
    private val items: MutableList<Transaction>,
    private val credits: List<Credit>,
    private val onDelete: (Transaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.VH>() {
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2; minimumFractionDigits = 0
    }
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val amount: TextView = view.findViewById(R.id.txAmount)
        val date: TextView = view.findViewById(R.id.txDate)
        val note: TextView = view.findViewById(R.id.txNote)
        val delete: ImageButton = view.findViewById(R.id.btnDeleteTx)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val tx = items[position]
        holder.amount.text = "${if (tx.amount >= 0) "+" else ""}${moneyFormat.format(tx.amount)} ₽"
        holder.amount.setTextColor(
            if (tx.amount >= 0) holder.itemView.context.getColor(R.color.savings_green)
            else holder.itemView.context.getColor(R.color.debt_red)
        )
        holder.date.text = dateFormat.format(Date(tx.timestamp))
        val creditName = tx.creditId?.let { id -> credits.find { it.id == id }?.name }
        val notes = mutableListOf<String>()
        if (tx.note.isNotBlank()) notes.add(tx.note)
        if (creditName != null) notes.add("Кредит: $creditName")
        holder.note.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        holder.note.text = notes.joinToString(" · ")
        holder.delete.setOnClickListener { onDelete(tx) }
    }
    override fun getItemCount() = items.size
}

class MainActivity : AppCompatActivity() {
    private lateinit var editAmount: EditText
    private lateinit var spinnerCredit: Spinner
    private lateinit var textBalance: TextView
    private lateinit var labelBalance: TextView
    private lateinit var textCreditInfo: TextView
    private lateinit var warningBanner: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyJournal: TextView
    private lateinit var btnUndo: MaterialButton
    private val transactions = mutableListOf<Transaction>()
    private val credits = mutableListOf<Credit>()
    private lateinit var adapter: TransactionAdapter
    private var selectedCreditId: String? = null
    private val prefs by lazy { getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE) }
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2; minimumFractionDigits = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        editAmount = findViewById(R.id.editAmount)
        spinnerCredit = findViewById(R.id.spinnerCredit)
        textBalance = findViewById(R.id.textBalance)
        labelBalance = findViewById(R.id.labelBalance)
        textCreditInfo = findViewById(R.id.textCreditInfo)
        warningBanner = findViewById(R.id.warningBanner)
        recycler = findViewById(R.id.recyclerJournal)
        emptyJournal = findViewById(R.id.emptyJournal)
        btnUndo = findViewById(R.id.btnUndo)
        adapter = TransactionAdapter(transactions, credits) { deleteTransaction(it) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<MaterialButton>(R.id.btnPlus).setOnClickListener { applyAmount(true) }
        findViewById<MaterialButton>(R.id.btnMinus).setOnClickListener { applyAmount(false) }
        btnUndo.setOnClickListener { undoLast() }
        spinnerCredit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedCreditId = if (pos == 0) null else credits.getOrNull(pos - 1)?.id
            }
            override fun onNothingSelected(p: AdapterView<*>?) { selectedCreditId = null }
        }
        loadAll()
        Persistence.ensureDefaultSeed(prefs, credits, transactions)
        applyDailyInterestIfNeeded()
        setupCreditSpinner()
        updateUI()
        InterestAlarmScheduler.scheduleNext(this)
        SubscriptionAlarmScheduler.scheduleNext(this)
        SubscriptionNotifier.checkAndNotify(this)
    }

    override fun onResume() {
        super.onResume()
        loadTransactions()
        loadCredits()
        applyDailyInterestIfNeeded()
        setupCreditSpinner()
        adapter.notifyDataSetChanged()
        updateUI()
        InterestAlarmScheduler.scheduleNext(this)
        SubscriptionAlarmScheduler.scheduleNext(this)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_credit -> {
                startActivity(Intent(this, CreditCalculatorActivity::class.java)); return true
            }
            R.id.menu_savings -> {
                startActivity(Intent(this, SavingsCalculatorActivity::class.java)); return true
            }
            R.id.menu_subscriptions -> {
                startActivity(Intent(this, SubscriptionsActivity::class.java)); return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupCreditSpinner() {
        val labels = mutableListOf("Без кредита (общий баланс)")
        credits.forEach { labels.add(it.name) }
        spinnerCredit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = if (selectedCreditId == null) 0
        else credits.indexOfFirst { it.id == selectedCreditId }.let { if (it >= 0) it + 1 else 0 }
        spinnerCredit.setSelection(idx.coerceIn(0, labels.size - 1))
    }

    private fun applyAmount(positive: Boolean) {
        val value = editAmount.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (value == null || value <= 0) {
            Toast.makeText(this, "Введите корректную сумму", Toast.LENGTH_SHORT).show()
            return
        }
        val creditId = selectedCreditId
        val credit = creditId?.let { id -> credits.find { it.id == id } }
        if (credit != null && positive) {
            val allocated = WbCreditMath.allocatePayment(
                value, credit.accruedFees, credit.accruedInterest, credit.principal
            )
            WbCreditMath.applyBreakdown(credit, allocated)
            val note = "Платёж: ${credit.name} · " +
                WbCreditMath.formatAllocationRu(allocated) { moneyFormat.format(it) }
            transactions.add(0, Transaction(UUID.randomUUID().toString(), allocated.total, System.currentTimeMillis(), note, creditId))
            saveCredits()
        } else if (credit != null && !positive) {
            credit.principal += value
            transactions.add(
                0,
                Transaction(UUID.randomUUID().toString(), -value, System.currentTimeMillis(), "Долг (тело): ${credit.name}", creditId)
            )
            saveCredits()
        } else {
            val signed = if (positive) value else -value
            val note = if (positive) "Пополнение" else "Списание"
            transactions.add(0, Transaction(UUID.randomUUID().toString(), signed, System.currentTimeMillis(), note, null))
        }
        adapter.notifyItemInserted(0)
        recycler.scrollToPosition(0)
        editAmount.text?.clear()
        saveTransactions()
        updateUI()
        checkBankruptcyWarning()
    }

    private fun undoLast() {
        if (transactions.isEmpty()) {
            Toast.makeText(this, "Нечего отменять", Toast.LENGTH_SHORT).show()
            return
        }
        val last = transactions.removeAt(0)
        reverseEffect(last)
        adapter.notifyItemRemoved(0)
        saveTransactions()
        saveCredits()
        updateUI()
    }

    private fun deleteTransaction(tx: Transaction) {
        AlertDialog.Builder(this)
            .setTitle("Удалить операцию?")
            .setMessage("Сумма и остатки кредита будут пересчитаны по возможности")
            .setPositiveButton("Удалить") { _, _ ->
                val index = transactions.indexOfFirst { it.id == tx.id }
                if (index >= 0) {
                    val removed = transactions.removeAt(index)
                    reverseEffect(removed)
                    adapter.notifyItemRemoved(index)
                    saveTransactions()
                    saveCredits()
                    updateUI()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Откат: для платежей с разбивкой в note пытаемся вернуть в %%/комис./тело;
     * для «Проценты:» / «Комиссия грейса» — уменьшаем соответствующие поля;
     * иначе — как раньше только тело.
     */
    private fun reverseEffect(tx: Transaction) {
        val credit = tx.creditId?.let { id -> credits.find { it.id == id } } ?: return
        val note = tx.note
        when {
            note.startsWith("Проценты:") || note.contains("начислены отдельно") -> {
                credit.accruedInterest = (credit.accruedInterest + tx.amount).coerceAtLeast(0.0) // tx.amount отрицательный
                // tx.amount is negative for interest accrual; adding it reduces accruedInterest
                // Wait: accruedInterest was increased by |amount|, so reverse: accruedInterest -= |amount|
                // tx.amount is -interest, so accruedInterest += tx.amount  (= subtract interest) — correct if we stored positive in field
            }
            note.startsWith("Комиссия грейса") -> {
                credit.accruedFees = (credit.accruedFees + tx.amount).coerceAtLeast(0.0)
            }
            note.contains("комиссии") && note.contains("тело") && tx.amount > 0 -> {
                // Best-effort: put whole payment back onto principal (manual edit can fix split)
                credit.principal += tx.amount
            }
            tx.amount > 0 -> credit.principal += tx.amount
            else -> {
                if (!note.startsWith("Проценты") && !note.startsWith("Комиссия")) {
                    credit.principal = (credit.principal + tx.amount).coerceAtLeast(0.0)
                }
            }
        }
    }

    private fun getBalance(): Double = transactions.sumOf { it.amount }

    private fun updateUI() {
        val balance = getBalance()
        val absStr = moneyFormat.format(abs(balance))
        if (balance >= 0) {
            labelBalance.text = getString(R.string.savings)
            textBalance.text = "$absStr ₽"
            textBalance.setTextColor(getColor(R.color.savings_green))
        } else {
            labelBalance.text = getString(R.string.debt)
            textBalance.text = "$absStr ₽"
            textBalance.setTextColor(getColor(R.color.debt_red))
        }
        val savingsRate = prefs.getFloat("savings_rate", 0f).toDouble()
        val savingsBalance = balance.coerceAtLeast(0.0)
        val savingsDaily = savingsBalance * savingsRate / 100.0 / 365.0
        if (credits.isNotEmpty()) {
            textCreditInfo.visibility = View.VISIBLE
            val totalP = credits.sumOf { it.principal }
            val totalI = credits.sumOf { it.accruedInterest }
            val totalF = credits.sumOf { it.accruedFees }
            val daily = credits.sumOf { c ->
                if (!c.graceMode && c.principal > 0 && c.rate > 0)
                    c.principal * c.rate / 100.0 / 365.0 else 0.0
            }
            val lines = credits.joinToString("\n") { c ->
                val mode = if (c.graceMode) "грейс" else "${c.rate}%"
                val min = WbCreditMath.calculateMinPayment(c)
                val bal = WbCreditMath.formatBalancesRu(
                    c.principal, c.accruedInterest, c.accruedFees,
                    { moneyFormat.format(it) },
                    principalLabel = "тело", interestLabel = "%", feesLabel = "комис."
                )
                val dailyHint = if (!c.graceMode && c.principal > 0.01 && c.rate > 0) {
                    val d = c.principal * c.rate / 100.0 / 365.0
                    if (d > 0) " · ≈${moneyFormat.format(d)} ₽/день" else ""
                } else ""
                "${c.name}: $bal ($mode)$dailyHint · мин. ${moneyFormat.format(min.total)}"
            }
            val totalParts = mutableListOf<String>()
            if (totalP > 0.01) totalParts.add("Тело: ${moneyFormat.format(totalP)}")
            if (totalI > 0.01) totalParts.add("начисл.%: ${moneyFormat.format(totalI)}")
            if (totalF > 0.01) totalParts.add("комис.: ${moneyFormat.format(totalF)}")
            val totalsLine = if (totalParts.isEmpty()) "остаток 0" else totalParts.joinToString(" · ")
            val dailyLine = if (daily > 0.0)
                "%% вне грейса: ≈${moneyFormat.format(daily)} ₽/день\n"
            else ""
            textCreditInfo.text =
                "Накопительный счёт: ${moneyFormat.format(savingsBalance)} ₽ · ${moneyFormat.format(savingsRate)}% · +≈${moneyFormat.format(savingsDaily)} ₽/день\n" +
                    "Кредиты\n$totalsLine\n" +
                    dailyLine + lines
        } else {
            textCreditInfo.visibility = if (savingsRate > 0) View.VISIBLE else View.GONE
            if (savingsRate > 0) {
                textCreditInfo.text =
                    "Накопительный счёт: ${moneyFormat.format(savingsBalance)} ₽ · ${moneyFormat.format(savingsRate)}% годовых\n" +
                        "Проценты: +≈${moneyFormat.format(savingsDaily)} ₽/день · +≈${moneyFormat.format(savingsDaily * 30)} ₽/30 дней"
            }
        }
        emptyJournal.visibility = if (transactions.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (transactions.isEmpty()) View.GONE else View.VISIBLE
        warningBanner.visibility = if (balance <= -500000.0) View.VISIBLE else View.GONE
    }

    private fun checkBankruptcyWarning() {
        if (getBalance() <= -500000.0) {
            AlertDialog.Builder(this)
                .setTitle("⚠ Риск банкротства")
                .setMessage(getString(R.string.bankruptcy_warning))
                .setPositiveButton("Понятно", null)
                .show()
        }
    }

    private fun applyDailyInterestIfNeeded() {
        DailyInterest.applyIfNeeded(prefs, transactions, credits)
    }

    private fun saveTransactions() = Persistence.saveTransactions(prefs, transactions)
    private fun loadTransactions() = Persistence.loadTransactions(prefs, transactions)
    private fun saveCredits() = Persistence.saveCredits(prefs, credits)
    private fun loadCredits() {
        Persistence.loadCredits(prefs, credits)
        adapter.notifyDataSetChanged()
    }
    private fun loadAll() {
        loadTransactions()
        loadCredits()
        adapter.notifyDataSetChanged()
    }
}
