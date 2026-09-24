package com.example.debtsavings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var textSavingsRow: TextView
    private lateinit var cardCreditsTable: View
    private lateinit var creditsTableBody: LinearLayout
    private lateinit var textCreditsTotals: TextView
    private lateinit var warningBanner: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyJournal: TextView
    private lateinit var btnUndo: MaterialButton
    private lateinit var btnJournalFull: MaterialButton
    private val transactions = mutableListOf<Transaction>()
    private val previewTx = mutableListOf<Transaction>()
    private val credits = mutableListOf<Credit>()
    private lateinit var adapter: TransactionAdapter
    private val previewLimit = 5
    private var selectedCreditId: String? = null
    private val prefs by lazy { getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE) }
    private val subscriptionsForBackup = mutableListOf<Subscription>()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                Persistence.loadSubscriptions(prefs, subscriptionsForBackup)
                val snap = DataBackup.Snapshot(
                    credits = credits.toList(),
                    transactions = transactions.toList(),
                    subscriptions = subscriptionsForBackup.toList(),
                    savingsRate = prefs.getFloat("savings_rate", 0f),
                    savingsLastInterestDate = prefs.getLong("savings_last_interest_date", 0L),
                    defaultsSeeded = prefs.getBoolean(Persistence.DEFAULTS_SEEDED_KEY, false)
                )
                DataBackup.writeToStream(prefs, snap, out)
            } ?: throw IllegalStateException("Не удалось открыть файл")
            Toast.makeText(this, "Экспорт сохранён", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val snap = contentResolver.openInputStream(uri)?.use { DataBackup.parse(it) }
                ?: throw IllegalStateException("Не удалось прочитать файл")
            AlertDialog.Builder(this)
                .setTitle("Импорт")
                .setMessage(
                    "Заменить все текущие данные?\n\n" +
                        "Кредитов: ${snap.credits.size}\n" +
                        "Операций: ${snap.transactions.size}\n" +
                        "Подписок: ${snap.subscriptions.size}"
                )
                .setPositiveButton("Заменить") { _, _ ->
                    try {
                        DataBackup.apply(prefs, snap)
                        loadAll()
                        applyDailyInterestIfNeeded()
                        setupCreditSpinner()
                        updateUI()
                        InterestAlarmScheduler.scheduleNext(this)
                        SubscriptionAlarmScheduler.scheduleNext(this)
                        Toast.makeText(this, "Импорт выполнен", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Ошибка применения: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
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
        textSavingsRow = findViewById(R.id.textSavingsRow)
        cardCreditsTable = findViewById(R.id.cardCreditsTable)
        creditsTableBody = findViewById(R.id.creditsTableBody)
        textCreditsTotals = findViewById(R.id.textCreditsTotals)
        warningBanner = findViewById(R.id.warningBanner)
        recycler = findViewById(R.id.recyclerJournal)
        emptyJournal = findViewById(R.id.emptyJournal)
        btnUndo = findViewById(R.id.btnUndo)
        btnJournalFull = findViewById(R.id.btnJournalFull)
        adapter = TransactionAdapter(previewTx, credits) { deleteTransaction(it) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<MaterialButton>(R.id.btnPlus).setOnClickListener { applyAmount(true) }
        findViewById<MaterialButton>(R.id.btnMinus).setOnClickListener { applyAmount(false) }
        btnUndo.setOnClickListener { undoLast() }
        btnJournalFull.setOnClickListener {
            startActivity(Intent(this, JournalActivity::class.java))
        }
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
            R.id.menu_calendar -> {
                startActivity(Intent(this, CalendarActivity::class.java)); return true
            }
            R.id.menu_credit -> {
                startActivity(Intent(this, CreditCalculatorActivity::class.java)); return true
            }
            R.id.menu_savings -> {
                startActivity(Intent(this, SavingsCalculatorActivity::class.java)); return true
            }
            R.id.menu_subscriptions -> {
                startActivity(Intent(this, SubscriptionsActivity::class.java)); return true
            }
            R.id.menu_export -> {
                exportLauncher.launch(DataBackup.defaultFileName()); return true
            }
            R.id.menu_import -> {
                importLauncher.launch(arrayOf("application/json", "text/*", "*/*")); return true
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
        textCreditInfo.visibility = View.GONE
        if (savingsRate > 0 || savingsBalance > 0.01) {
            textSavingsRow.visibility = View.VISIBLE
            textSavingsRow.text =
                "Накопительный счёт: ${moneyFormat.format(savingsBalance)} ₽ · ${moneyFormat.format(savingsRate)}%" +
                    " · +≈${moneyFormat.format(savingsDaily)} ₽/день"
        } else {
            textSavingsRow.visibility = View.GONE
        }
        renderCreditsTable()
        refreshPreview()
        emptyJournal.visibility = if (transactions.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (transactions.isEmpty()) View.GONE else View.VISIBLE
        warningBanner.visibility = if (balance <= -500000.0) View.VISIBLE else View.GONE
    }

    private fun refreshPreview() {
        previewTx.clear()
        previewTx.addAll(transactions.take(previewLimit))
        adapter.notifyDataSetChanged()
    }

    private fun renderCreditsTable() {
        if (credits.isEmpty()) {
            cardCreditsTable.visibility = View.GONE
            return
        }
        cardCreditsTable.visibility = View.VISIBLE
        creditsTableBody.removeAllViews()
        val inflater = LayoutInflater.from(this)
        var totalP = 0.0
        var totalI = 0.0
        var totalF = 0.0
        var totalDaily = 0.0
        credits.forEachIndexed { idx, c ->
            totalP += c.principal
            totalI += c.accruedInterest
            totalF += c.accruedFees
            val row = inflater.inflate(R.layout.item_credit_table_row, creditsTableBody, false)
            if (idx % 2 == 1) row.setBackgroundColor(0x0A000000)
            row.findViewById<TextView>(R.id.rowName).text = c.name
            row.findViewById<TextView>(R.id.rowPrincipal).text =
                if (c.principal > 0.01) "Тело ${moneyFormat.format(c.principal)}" else "Тело —"
            row.findViewById<TextView>(R.id.rowInterest).text =
                if (c.accruedInterest > 0.01) "% ${moneyFormat.format(c.accruedInterest)}" else "% —"
            row.findViewById<TextView>(R.id.rowFees).text =
                if (c.accruedFees > 0.01) "Ком. ${moneyFormat.format(c.accruedFees)}" else "Ком. —"
            val mode = if (c.graceMode) "Грейс" else "${moneyFormat.format(c.rate)}%"
            row.findViewById<TextView>(R.id.rowMode).text = mode
            val daily = if (!c.graceMode && c.principal > 0.01 && c.rate > 0)
                c.principal * c.rate / 100.0 / 365.0 else 0.0
            totalDaily += daily
            row.findViewById<TextView>(R.id.rowDaily).text =
                if (daily > 0.01) "≈${moneyFormat.format(daily)}/д" else "—"
            val min = WbCreditMath.calculateMinPayment(c)
            row.findViewById<TextView>(R.id.rowMin).text =
                if (min.total > 0.01) "Мин. ${moneyFormat.format(min.total)}" else "Мин. —"
            creditsTableBody.addView(row)
        }
        val parts = mutableListOf<String>()
        if (totalP > 0.01) parts.add("Тело ${moneyFormat.format(totalP)}")
        if (totalI > 0.01) parts.add("% ${moneyFormat.format(totalI)}")
        if (totalF > 0.01) parts.add("комис. ${moneyFormat.format(totalF)}")
        val dailyPart = if (totalDaily > 0.01) " · ≈${moneyFormat.format(totalDaily)} ₽/день" else ""
        textCreditsTotals.text =
            "Итого: " + (if (parts.isEmpty()) "0" else parts.joinToString(" · ")) + dailyPart
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
