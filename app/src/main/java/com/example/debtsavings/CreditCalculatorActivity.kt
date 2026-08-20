package com.example.debtsavings

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
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.pow

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

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.creditName)
        val details: TextView = v.findViewById(R.id.creditDetails)
        val principal: TextView = v.findViewById(R.id.creditPrincipal)
        val btnPay: MaterialButton = v.findViewById(R.id.btnPayCredit)
        val btnEdit: ImageButton = v.findViewById(R.id.btnEditCredit)
        val btnDelete: ImageButton = v.findViewById(R.id.btnDeleteCredit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_credit, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.name.text = c.name
        holder.details.text = "${c.rate} % годовых · ${c.termMonths} мес. · платёж ${moneyFormat.format(c.monthlyPayment)} ₽"

        val daily = c.principal * c.rate / 100.0 / 365.0
        holder.principal.text = if (c.principal > 0 && c.rate > 0) {
            "Тело: ${moneyFormat.format(c.principal)} ₽ · ≈${moneyFormat.format(daily)} ₽/день"
        } else {
            "Тело: ${moneyFormat.format(c.principal)} ₽"
        }

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

        recycler = findViewById(R.id.recyclerCredits)
        emptyText = findViewById(R.id.emptyCredits)

        adapter = CreditListAdapter(
            credits,
            onEdit = { showCreditDialog(it) },
            onDelete = { confirmDelete(it) },
            onPay = { showPaymentDialog(it) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAddCredit).setOnClickListener {
            showCreditDialog(null)
        }

        loadCredits()
        updateEmpty()
    }

    private fun showCreditDialog(existing: Credit?) {
        val view = layoutInflater.inflate(R.layout.dialog_credit, null)
        val editName = view.findViewById<EditText>(R.id.editCreditName)
        val editRate = view.findViewById<EditText>(R.id.editRate)
        val editTerm = view.findViewById<EditText>(R.id.editTerm)
        val editPayment = view.findViewById<EditText>(R.id.editPayment)
        val editPrincipal = view.findViewById<EditText>(R.id.editPrincipal)
        val textCalc = view.findViewById<TextView>(R.id.textCalcPreview)

        if (existing != null) {
            editName.setText(existing.name)
            editRate.setText(existing.rate.toString())
            editTerm.setText(existing.termMonths.toString())
            editPayment.setText(existing.monthlyPayment.toString())
            editPrincipal.setText(existing.principal.toString())
        }

        fun preview() {
            val rate = editRate.text.toString().replace(',', '.').toDoubleOrNull()
            val term = editTerm.text.toString().toIntOrNull()
            val payment = editPayment.text.toString().replace(',', '.').toDoubleOrNull()
            if (rate == null || rate <= 0 || term == null || term <= 0 || payment == null || payment <= 0) {
                textCalc.text = ""
                return
            }
            val monthlyRate = rate / 100.0 / 12.0
            val n = term.toDouble()
            val factor = (1 + monthlyRate).pow(n)
            val estPrincipal = payment * (factor - 1) / (monthlyRate * factor)
            val totalPaid = payment * n
            val over = totalPaid - estPrincipal
            textCalc.text = "Ориентир. тело по платежу: ${moneyFormat.format(estPrincipal)} ₽\n" +
                    "Всего выплат: ${moneyFormat.format(totalPaid)} ₽\n" +
                    "Переплата: ${moneyFormat.format(over)} ₽"
        }

        editRate.setOnFocusChangeListener { _, _ -> preview() }
        editTerm.setOnFocusChangeListener { _, _ -> preview() }
        editPayment.setOnFocusChangeListener { _, _ -> preview() }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Новый кредит" else "Редактировать кредит")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = editName.text.toString().trim().ifEmpty { "Кредит" }
                val rate = editRate.text.toString().replace(',', '.').toDoubleOrNull()
                val term = editTerm.text.toString().toIntOrNull()
                val payment = editPayment.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val principal = editPrincipal.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0

                if (rate == null || rate <= 0 || term == null || term <= 0) {
                    Toast.makeText(this, "Укажите ставку и срок", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (existing == null) {
                    val c = Credit(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        rate = rate,
                        termMonths = term,
                        monthlyPayment = payment,
                        principal = principal,
                        lastInterestDate = System.currentTimeMillis()
                    )
                    credits.add(0, c)
                    addTransaction(
                        amount = -principal,
                        note = "Получен кредит: $name (тело долга)",
                        creditId = c.id
                    )
                    adapter.notifyItemInserted(0)
                } else {
                    existing.name = name
                    existing.rate = rate
                    existing.termMonths = term
                    existing.monthlyPayment = payment
                    existing.principal = principal
                    val idx = credits.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) adapter.notifyItemChanged(idx)
                }
                saveCredits()
                updateEmpty()
                Toast.makeText(this, "Сохранено. Тело долга добавлено в общий баланс", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()

        preview()
    }

    private fun showPaymentDialog(credit: Credit) {
        val input = EditText(this).apply {
            hint = "Сумма платежа, ₽"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (credit.monthlyPayment > 0) credit.monthlyPayment.toString() else "")
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Платёж: ${credit.name}")
            .setMessage("Текущее тело: ${moneyFormat.format(credit.principal)} ₽")
            .setView(input)
            .setPositiveButton("Внести") { _, _ ->
                val amount = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(this, "Введите сумму", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val reduce = minOf(amount, credit.principal)
                credit.principal = (credit.principal - reduce).coerceAtLeast(0.0)
                addTransaction(reduce, "Платёж: ${credit.name}", credit.id)
                saveCredits()
                val idx = credits.indexOfFirst { it.id == credit.id }
                if (idx >= 0) adapter.notifyItemChanged(idx)
                Toast.makeText(this, "Платёж ${moneyFormat.format(reduce)} ₽ внесён", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDelete(credit: Credit) {
        AlertDialog.Builder(this)
            .setTitle("Удалить «${credit.name}»?")
            .setMessage("Операции в журнале останутся, но привязка к кредиту сохранится только как название.")
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
        val json = prefs.getString("transactions", "[]") ?: "[]"
        val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        val o = JSONObject()
        o.put("id", UUID.randomUUID().toString())
        o.put("amount", amount)
        o.put("timestamp", System.currentTimeMillis())
        o.put("note", note)
        o.put("creditId", creditId)
        val newArr = JSONArray()
        newArr.put(o)
        for (i in 0 until arr.length()) newArr.put(arr.get(i))
        prefs.edit().putString("transactions", newArr.toString()).apply()
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
                credits.add(
                    Credit(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        rate = o.getDouble("rate"),
                        termMonths = o.getInt("termMonths"),
                        monthlyPayment = o.getDouble("monthlyPayment"),
                        principal = o.getDouble("principal"),
                        lastInterestDate = o.optLong("lastInterestDate", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
