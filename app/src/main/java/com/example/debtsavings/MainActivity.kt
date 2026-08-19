package com.example.debtsavings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Transaction(
    val id: String,
    val amount: Double,          // positive = income/savings, negative = expense/debt increase
    val timestamp: Long,
    val note: String = ""
)

class TransactionAdapter(
    private val items: MutableList<Transaction>,
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

        if (tx.note.isNotBlank()) {
            holder.note.visibility = View.VISIBLE
            holder.note.text = tx.note
        } else {
            holder.note.visibility = View.GONE
        }

        holder.delete.setOnClickListener { onDelete(tx) }
    }

    override fun getItemCount() = items.size
}

class MainActivity : AppCompatActivity() {

    private lateinit var editAmount: EditText
    private lateinit var textBalance: TextView
    private lateinit var labelBalance: TextView
    private lateinit var textCreditInfo: TextView
    private lateinit var warningBanner: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyJournal: TextView
    private lateinit var btnUndo: MaterialButton

    private val transactions = mutableListOf<Transaction>()
    private lateinit var adapter: TransactionAdapter

    private val prefs by lazy { getSharedPreferences("debt_savings_prefs", Context.MODE_PRIVATE) }
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }

    // Credit settings
    private var creditActive = false
    private var creditRate = 0.0          // annual %
    private var creditTermMonths = 0
    private var creditMonthlyPayment = 0.0
    private var creditPrincipal = 0.0     // тело долга
    private var lastInterestDate = 0L     // day of last interest accrual

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        editAmount = findViewById(R.id.editAmount)
        textBalance = findViewById(R.id.textBalance)
        labelBalance = findViewById(R.id.labelBalance)
        textCreditInfo = findViewById(R.id.textCreditInfo)
        warningBanner = findViewById(R.id.warningBanner)
        recycler = findViewById(R.id.recyclerJournal)
        emptyJournal = findViewById(R.id.emptyJournal)
        btnUndo = findViewById(R.id.btnUndo)

        adapter = TransactionAdapter(transactions) { tx -> deleteTransaction(tx) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btnPlus).setOnClickListener { applyAmount(positive = true) }
        findViewById<MaterialButton>(R.id.btnMinus).setOnClickListener { applyAmount(positive = false) }
        btnUndo.setOnClickListener { undoLast() }

        loadAll()
        applyDailyInterestIfNeeded()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        // Reload credit settings in case they were changed in calculator
        loadCreditSettings()
        applyDailyInterestIfNeeded()
        updateUI()
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

    private fun applyAmount(positive: Boolean) {
        val text = editAmount.text.toString().trim().replace(',', '.')
        val value = text.toDoubleOrNull()
        if (value == null || value <= 0) {
            Toast.makeText(this, "Введите корректную сумму", Toast.LENGTH_SHORT).show()
            return
        }
        val signed = if (positive) value else -value
        val note = if (positive) "Пополнение" else "Списание"

        val tx = Transaction(
            id = UUID.randomUUID().toString(),
            amount = signed,
            timestamp = System.currentTimeMillis(),
            note = note
        )
        transactions.add(0, tx)

        // If credit is active and we are reducing debt (positive amount while in debt),
        // also reduce principal
        if (creditActive && positive && getBalance() < 0) {
            val reduce = minOf(value, creditPrincipal)
            creditPrincipal = (creditPrincipal - reduce).coerceAtLeast(0.0)
            saveCreditSettings()
        }
        // If increasing debt (minus), increase principal if credit active
        if (creditActive && !positive) {
            creditPrincipal += value
            saveCreditSettings()
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
        // Reverse effect on principal if credit active
        if (creditActive) {
            if (last.amount > 0) {
                // was a payment / reduction → add back to principal
                creditPrincipal += last.amount
            } else {
                // was an increase of debt → remove from principal
                creditPrincipal = (creditPrincipal + last.amount).coerceAtLeast(0.0) // amount is negative
            }
            saveCreditSettings()
        }
        adapter.notifyItemRemoved(0)
        saveTransactions()
        updateUI()
        Toast.makeText(this, "Операция отменена", Toast.LENGTH_SHORT).show()
    }

    private fun deleteTransaction(tx: Transaction) {
        AlertDialog.Builder(this)
            .setTitle("Удалить операцию?")
            .setMessage("Сумма будет пересчитана")
            .setPositiveButton("Удалить") { _, _ ->
                val index = transactions.indexOfFirst { it.id == tx.id }
                if (index >= 0) {
                    val removed = transactions.removeAt(index)
                    if (creditActive) {
                        if (removed.amount > 0) {
                            creditPrincipal += removed.amount
                        } else {
                            creditPrincipal = (creditPrincipal + removed.amount).coerceAtLeast(0.0)
                        }
                        saveCreditSettings()
                    }
                    adapter.notifyItemRemoved(index)
                    saveTransactions()
                    updateUI()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
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

        // Credit info line
        if (creditActive && creditPrincipal > 0) {
            textCreditInfo.visibility = View.VISIBLE
            val dailyRate = creditRate / 100.0 / 365.0
            val dailyInterest = creditPrincipal * dailyRate
            textCreditInfo.text = "Тело кредита: ${moneyFormat.format(creditPrincipal)} ₽  •  " +
                    "% в день ≈ ${moneyFormat.format(dailyInterest)} ₽"
        } else {
            textCreditInfo.visibility = View.GONE
        }

        // Journal empty state
        if (transactions.isEmpty()) {
            emptyJournal.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyJournal.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }

        // Bankruptcy banner
        if (balance <= -500_000.0) {
            warningBanner.visibility = View.VISIBLE
        } else {
            warningBanner.visibility = View.GONE
        }
    }

    private fun checkBankruptcyWarning() {
        val balance = getBalance()
        if (balance <= -500_000.0) {
            AlertDialog.Builder(this)
                .setTitle("⚠ Риск банкротства")
                .setMessage(getString(R.string.bankruptcy_warning))
                .setPositiveButton("Понятно", null)
                .show()
        }
    }

    // ---------- Daily interest ----------

    private fun applyDailyInterestIfNeeded() {
        if (!creditActive || creditPrincipal <= 0 || creditRate <= 0) return

        val today = startOfDay(System.currentTimeMillis())
        if (lastInterestDate == 0L) {
            lastInterestDate = today
            saveCreditSettings()
            return
        }

        val days = TimeUnit.MILLISECONDS.toDays(today - lastInterestDate).toInt()
        if (days <= 0) return

        val dailyRate = creditRate / 100.0 / 365.0
        val interest = creditPrincipal * dailyRate * days

        if (interest > 0) {
            // Increase debt (negative transaction) and principal
            val tx = Transaction(
                id = UUID.randomUUID().toString(),
                amount = -interest,
                timestamp = System.currentTimeMillis(),
                note = "Начисление %% за $days дн."
            )
            transactions.add(0, tx)
            creditPrincipal += interest
            adapter.notifyItemInserted(0)
            saveTransactions()
        }

        lastInterestDate = today
        saveCreditSettings()
    }

    private fun startOfDay(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ---------- Persistence ----------

    private fun saveTransactions() {
        val arr = JSONArray()
        transactions.forEach { tx ->
            val o = JSONObject()
            o.put("id", tx.id)
            o.put("amount", tx.amount)
            o.put("timestamp", tx.timestamp)
            o.put("note", tx.note)
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
                transactions.add(
                    Transaction(
                        id = o.getString("id"),
                        amount = o.getDouble("amount"),
                        timestamp = o.getLong("timestamp"),
                        note = o.optString("note", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCreditSettings() {
        prefs.edit()
            .putBoolean("credit_active", creditActive)
            .putFloat("credit_rate", creditRate.toFloat())
            .putInt("credit_term", creditTermMonths)
            .putFloat("credit_payment", creditMonthlyPayment.toFloat())
            .putFloat("credit_principal", creditPrincipal.toFloat())
            .putLong("last_interest_date", lastInterestDate)
            .apply()
    }

    private fun loadCreditSettings() {
        creditActive = prefs.getBoolean("credit_active", false)
        creditRate = prefs.getFloat("credit_rate", 0f).toDouble()
        creditTermMonths = prefs.getInt("credit_term", 0)
        creditMonthlyPayment = prefs.getFloat("credit_payment", 0f).toDouble()
        creditPrincipal = prefs.getFloat("credit_principal", 0f).toDouble()
        lastInterestDate = prefs.getLong("last_interest_date", 0L)
    }

    private fun loadAll() {
        loadTransactions()
        loadCreditSettings()
        adapter.notifyDataSetChanged()
    }
}
