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
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class TransactionAdapter(
    private val items: MutableList<Transaction>,
    private val credits: List<Credit>,
    private val onDelete: (Transaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.VH>() {

    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val amount: TextView = view.findViewById(R.id.txAmount)
        val date: TextView = view.findViewById(R.id.txDate)
        val note: TextView = view.findViewById(R.id.txNote)
        val delete: ImageButton = view.findViewById(R.id.btnDeleteTx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tx = items[position]
        val sign = if (tx.amount >= 0) "+" else ""
        holder.amount.text = "$sign${moneyFormat.format(tx.amount)} ₽"
        holder.amount.setTextColor(
            if (tx.amount >= 0)
                holder.itemView.context.getColor(R.color.savings_green)
            else
                holder.itemView.context.getColor(R.color.debt_red)
        )
        holder.date.text = dateFormat.format(Date(tx.timestamp))

        val creditName = tx.creditId?.let { id -> credits.find { it.id == id }?.name }
        val noteParts = mutableListOf<String>()
        if (tx.note.isNotBlank()) noteParts.add(tx.note)
        if (creditName != null) noteParts.add("Кредит: $creditName")

        if (noteParts.isNotEmpty()) {
            holder.note.visibility = View.VISIBLE
            holder.note.text = noteParts.joinToString(" · ")
        } else {
            holder.note.visibility = View.GONE
        }

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
    private val prefs by lazy { getSharedPreferences("debt_savings_prefs", Context.MODE_PRIVATE) }
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        editAmount = findViewById(R.id.editAmount)
        spinnerCredit = findViewById(R.id.spinnerCredit)
        textBalance = findViewById(R.id.textBalance)
        labelBalance = findViewById(R.id.labelBalance)
        textCreditInfo = findViewById(R.id.textCreditInfo)
        warningBanner = findViewById(R.id.warningBanner)
        recycler = findViewById(R.id.recyclerJournal)
        emptyJournal = findViewById(R.id.emptyJournal)
        btnUndo = findViewById(R.id.btnUndo)

        adapter = TransactionAdapter(transactions, credits) { tx -> deleteTransaction(tx) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btnPlus).setOnClickListener { applyAmount(positive = true) }
        findViewById<MaterialButton>(R.id.btnMinus).setOnClickListener { applyAmount(positive = false) }
        btnUndo.setOnClickListener { undoLast() }

        spinnerCredit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCreditId = if (position == 0) null else credits.getOrNull(position - 1)?.id
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedCreditId = null
            }
        }

        loadAll()
        applyDailyInterestIfNeeded()
        setupCreditSpinner()
        updateUI()
        InterestAlarmScheduler.scheduleNext(this)
    }

    override fun onResume() {
        super.onResume()
        loadCredits()
        applyDailyInterestIfNeeded()
        setupCreditSpinner()
        adapter.notifyDataSetChanged()
        updateUI()
        InterestAlarmScheduler.scheduleNext(this)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_credit) {
            startActivity(Intent(this, CreditCalculatorActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupCreditSpinner() {
        val labels = mutableListOf("Без кредита (общий баланс)")
        credits.forEach { labels.add(it.name) }
        val spinAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinnerCredit.adapter = spinAdapter
        val idx = if (selectedCreditId == null) 0
        else credits.indexOfFirst { it.id == selectedCreditId }.let { if (it >= 0) it + 1 else 0 }
        spinnerCredit.setSelection(idx.coerceIn(0, labels.size - 1))
    }

    private fun applyAmount(positive: Boolean) {
        val text = editAmount.text.toString().trim().replace(',', '.')
        val value = text.toDoubleOrNull()
        if (value == null || value <= 0) {
            Toast.makeText(this, "Введите корректную сумму", Toast.LENGTH_SHORT).show()
            return
        }
        val signed = if (positive) value else -value
        val creditId = selectedCreditId
        val credit = creditId?.let { id -> credits.find { it.id == id } }
        val note = when {
            credit != null && positive -> "Платёж: ${credit.name}"
            credit != null && !positive -> "Долг: ${credit.name}"
            positive -> "Пополнение"
            else -> "Списание"
        }

        transactions.add(0, Transaction(UUID.randomUUID().toString(), signed, System.currentTimeMillis(), note, creditId))

        if (credit != null) {
            if (positive) credit.principal = (credit.principal - value).coerceAtLeast(0.0)
            else credit.principal += value
            saveCredits()
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
        reversePrincipalEffect(last)
        adapter.notifyItemRemoved(0)
        saveTransactions()
        saveCredits()
        updateUI()
        Toast.makeText(this, "Операция отменена", Toast.LENGTH_SHORT).show()
    }

    private fun deleteTransaction(tx: Transaction) {
        AlertDialog.Builder(this)
            .setTitle("Удалить операцию?")
            .setMessage("Сумма и тело кредита будут пересчитаны")
            .setPositiveButton("Удалить") { _, _ ->
                val index = transactions.indexOfFirst { it.id == tx.id }
                if (index >= 0) {
                    val removed = transactions.removeAt(index)
                    reversePrincipalEffect(removed)
                    adapter.notifyItemRemoved(index)
                    saveTransactions()
                    saveCredits()
                    updateUI()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun reversePrincipalEffect(tx: Transaction) {
        val credit = tx.creditId?.let { id -> credits.find { it.id == id } } ?: return
        if (tx.amount > 0) credit.principal += tx.amount
        else credit.principal = (credit.principal + tx.amount).coerceAtLeast(0.0)
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

        if (credits.isNotEmpty()) {
            textCreditInfo.visibility = View.VISIBLE
            val totalPrincipal = credits.sumOf { it.principal }
            val lines = credits.joinToString("\n") { c ->
                val daily = c.principal * c.rate / 100.0 / 365.0
                "${c.name}: ${moneyFormat.format(c.principal)} ₽ (≈${moneyFormat.format(daily)} ₽/день)"
            }
            textCreditInfo.text = "Кредиты (тело всего ${moneyFormat.format(totalPrincipal)} ₽):\n$lines"
        } else {
            textCreditInfo.visibility = View.GONE
        }

        if (transactions.isEmpty()) {
            emptyJournal.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyJournal.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
        warningBanner.visibility = if (balance <= -500_000.0) View.VISIBLE else View.GONE
    }

    private fun checkBankruptcyWarning() {
        if (getBalance() <= -500_000.0) {
            AlertDialog.Builder(this)
                .setTitle("⚠ Риск банкротства")
                .setMessage(getString(R.string.bankruptcy_warning))
                .setPositiveButton("Понятно", null)
                .show()
        }
    }

    // Daily accrual uses Moscow calendar days, regardless of the device timezone.
    private fun applyDailyInterestIfNeeded() {
        val today = startOfDay(System.currentTimeMillis())
        var changed = false

        for (credit in credits) {
            if (credit.lastInterestDate == 0L) {
                credit.lastInterestDate = today
                changed = true
                continue
            }

            val days = TimeUnit.MILLISECONDS.toDays(today - credit.lastInterestDate).toInt()
            if (days <= 0) continue

            if (credit.principal > 0 && credit.rate > 0) {
                val dailyRate = credit.rate / 100.0 / 365.0
                val interest = credit.principal * dailyRate * days
                if (interest > 0) {
                    transactions.add(
                        0,
                        Transaction(
                            UUID.randomUUID().toString(),
                            -interest,
                            today,
                            "Проценты: ${credit.name} за $days дн.",
                            credit.id
                        )
                    )
                    credit.principal += interest
                    changed = true
                }
            }
            credit.lastInterestDate = today
            changed = true
        }

        if (changed) {
            saveTransactions()
            saveCredits()
            adapter.notifyDataSetChanged()
        }
    }

    private fun startOfDay(millis: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun saveTransactions() {
        val arr = JSONArray()
        transactions.forEach { tx ->
            val o = JSONObject()
            o.put("id", tx.id)
            o.put("amount", tx.amount)
            o.put("timestamp", tx.timestamp)
            o.put("note", tx.note)
            if (tx.creditId != null) o.put("creditId", tx.creditId)
            arr.put(o)
        }
        prefs.edit().putString("transactions", arr.toString()).apply()
    }

    private fun loadTransactions() {
        transactions.clear()
        val json = prefs.getString("transactions", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                transactions.add(Transaction(
                    o.getString("id"), o.getDouble("amount"), o.getLong("timestamp"),
                    o.optString("note", ""), if (o.has("creditId")) o.getString("creditId") else null
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCredits() {
        val arr = JSONArray()
        credits.forEach { c ->
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("rate", c.rate)
            o.put("termMonths", c.termMonths)
            o.put("monthlyPayment", c.monthlyPayment)
            o.put("principal", c.principal)
            o.put("lastInterestDate", c.lastInterestDate)
            arr.put(o)
        }
        prefs.edit().putString("credits", arr.toString()).apply()
    }

    private fun loadCredits() {
        credits.clear()
        val json = prefs.getString("credits", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                credits.add(Credit(
                    o.getString("id"), o.getString("name"), o.getDouble("rate"),
                    o.getInt("termMonths"), o.getDouble("monthlyPayment"),
                    o.getDouble("principal"), o.optLong("lastInterestDate", 0L)
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (credits.isEmpty() && prefs.getBoolean("credit_active", false)) {
            val old = Credit(
                UUID.randomUUID().toString(),
                "Основной кредит",
                prefs.getFloat("credit_rate", 0f).toDouble(),
                prefs.getInt("credit_term", 0),
                prefs.getFloat("credit_payment", 0f).toDouble(),
                prefs.getFloat("credit_principal", 0f).toDouble(),
                prefs.getLong("last_interest_date", 0L)
            )
            if (old.rate > 0) {
                credits.add(old)
                saveCredits()
                prefs.edit()
                    .remove("credit_active")
                    .remove("credit_rate")
                    .remove("credit_term")
                    .remove("credit_payment")
                    .remove("credit_principal")
                    .remove("last_interest_date")
                    .apply()
            }
        }
    }

    private fun loadAll() {
        loadTransactions()
        loadCredits()
        adapter.notifyDataSetChanged()
    }
}
