package com.example.debtsavings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

/**
 * Полноэкранный журнал всех операций — удобный скролл всего списка.
 */
class JournalActivity : AppCompatActivity() {
    private val transactions = mutableListOf<Transaction>()
    private val credits = mutableListOf<Credit>()
    private lateinit var adapter: TransactionAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private val prefs by lazy { getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recycler = findViewById(R.id.recyclerJournalFull)
        empty = findViewById(R.id.emptyJournalFull)
        adapter = TransactionAdapter(transactions, credits) { deleteTransaction(it) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        Persistence.loadTransactions(prefs, transactions)
        Persistence.loadCredits(prefs, credits)
        adapter.notifyDataSetChanged()
        empty.visibility = if (transactions.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (transactions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun deleteTransaction(tx: Transaction) {
        AlertDialog.Builder(this)
            .setTitle("Удалить операцию?")
            .setMessage("Сумма и остатки кредита будут пересчитаны по возможности")
            .setPositiveButton("Удалить") { _, _ ->
                val index = transactions.indexOfFirst { it.id == tx.id }
                if (index < 0) return@setPositiveButton
                val removed = transactions.removeAt(index)
                reverseEffect(removed)
                Persistence.saveTransactions(prefs, transactions)
                Persistence.saveCredits(prefs, credits)
                adapter.notifyItemRemoved(index)
                empty.visibility = if (transactions.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (transactions.isEmpty()) View.GONE else View.VISIBLE
                Toast.makeText(this, "Удалено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun reverseEffect(tx: Transaction) {
        val credit = tx.creditId?.let { id -> credits.find { it.id == id } } ?: return
        val note = tx.note
        when {
            note.startsWith("Проценты:") || note.contains("начислены отдельно") -> {
                credit.accruedInterest = (credit.accruedInterest + tx.amount).coerceAtLeast(0.0)
            }
            note.startsWith("Комиссия грейса") -> {
                credit.accruedFees = (credit.accruedFees + tx.amount).coerceAtLeast(0.0)
            }
            note.contains("комиссии") && note.contains("тело") && tx.amount > 0 -> {
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
}
