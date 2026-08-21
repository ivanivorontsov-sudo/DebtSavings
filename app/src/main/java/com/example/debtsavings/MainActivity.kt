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

class TransactionAdapter(private val items: MutableList<Transaction>, private val credits: List<Credit>, private val onDelete: (Transaction) -> Unit) : RecyclerView.Adapter<TransactionAdapter.VH>() {
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    class VH(view: View) : RecyclerView.ViewHolder(view) { val amount: TextView = view.findViewById(R.id.txAmount); val date: TextView = view.findViewById(R.id.txDate); val note: TextView = view.findViewById(R.id.txNote); val delete: ImageButton = view.findViewById(R.id.btnDeleteTx) }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) { val tx = items[position]; holder.amount.text = "${if (tx.amount >= 0) "+" else ""}${moneyFormat.format(tx.amount)} ₽"; holder.amount.setTextColor(if (tx.amount >= 0) holder.itemView.context.getColor(R.color.savings_green) else holder.itemView.context.getColor(R.color.debt_red)); holder.date.text = dateFormat.format(Date(tx.timestamp)); val creditName = tx.creditId?.let { id -> credits.find { it.id == id }?.name }; val notes = mutableListOf<String>(); if (tx.note.isNotBlank()) notes.add(tx.note); if (creditName != null) notes.add("Кредит: $creditName"); holder.note.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE; holder.note.text = notes.joinToString(" · "); holder.delete.setOnClickListener { onDelete(tx) } }
    override fun getItemCount() = items.size
}

class MainActivity : AppCompatActivity() {
    private lateinit var editAmount: EditText; private lateinit var spinnerCredit: Spinner; private lateinit var textBalance: TextView; private lateinit var labelBalance: TextView; private lateinit var textCreditInfo: TextView; private lateinit var warningBanner: TextView; private lateinit var recycler: RecyclerView; private lateinit var emptyJournal: TextView; private lateinit var btnUndo: MaterialButton
    private val transactions = mutableListOf<Transaction>(); private val credits = mutableListOf<Credit>(); private lateinit var adapter: TransactionAdapter; private var selectedCreditId: String? = null
    private val prefs by lazy { getSharedPreferences("debt_savings_prefs", Context.MODE_PRIVATE) }
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_main); setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar)); editAmount=findViewById(R.id.editAmount); spinnerCredit=findViewById(R.id.spinnerCredit); textBalance=findViewById(R.id.textBalance); labelBalance=findViewById(R.id.labelBalance); textCreditInfo=findViewById(R.id.textCreditInfo); warningBanner=findViewById(R.id.warningBanner); recycler=findViewById(R.id.recyclerJournal); emptyJournal=findViewById(R.id.emptyJournal); btnUndo=findViewById(R.id.btnUndo); adapter=TransactionAdapter(transactions,credits){deleteTransaction(it)}; recycler.layoutManager=LinearLayoutManager(this); recycler.adapter=adapter; findViewById<MaterialButton>(R.id.btnPlus).setOnClickListener{applyAmount(true)}; findViewById<MaterialButton>(R.id.btnMinus).setOnClickListener{applyAmount(false)}; btnUndo.setOnClickListener{undoLast()}; spinnerCredit.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){selectedCreditId=if(pos==0)null else credits.getOrNull(pos-1)?.id};override fun onNothingSelected(p:AdapterView<*>?){selectedCreditId=null}}; loadAll();applyDailyInterestIfNeeded();setupCreditSpinner();updateUI();InterestAlarmScheduler.scheduleNext(this) }
    override fun onResume(){super.onResume();loadTransactions();loadCredits();applyDailyInterestIfNeeded();setupCreditSpinner();adapter.notifyDataSetChanged();updateUI();InterestAlarmScheduler.scheduleNext(this)}
    override fun onCreateOptionsMenu(menu:android.view.Menu?):Boolean{menuInflater.inflate(R.menu.main_menu,menu);return true}
    override fun onOptionsItemSelected(item:MenuItem):Boolean{if(item.itemId==R.id.menu_credit){startActivity(Intent(this,CreditCalculatorActivity::class.java));return true};return super.onOptionsItemSelected(item)}
    private fun setupCreditSpinner(){val labels=mutableListOf("Без кредита (общий баланс)");credits.forEach{labels.add(it.name)};spinnerCredit.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,labels);val idx=if(selectedCreditId==null)0 else credits.indexOfFirst{it.id==selectedCreditId}.let{if(it>=0)it+1 else 0};spinnerCredit.setSelection(idx.coerceIn(0,labels.size-1))}
    private fun applyAmount(positive:Boolean){val value=editAmount.text.toString().trim().replace(',','.').toDoubleOrNull();if(value==null||value<=0){Toast.makeText(this,"Введите корректную сумму",Toast.LENGTH_SHORT).show();return};val signed=if(positive)value else -value;val creditId=selectedCreditId;val credit=creditId?.let{id->credits.find{it.id==id}};val note=when{credit!=null&&positive->"Платёж: ${credit.name}";credit!=null->"Долг: ${credit.name}";positive->"Пополнение";else->"Списание"};transactions.add(0,Transaction(UUID.randomUUID().toString(),signed,System.currentTimeMillis(),note,creditId));if(credit!=null){if(positive)credit.principal=(credit.principal-value).coerceAtLeast(0.0)else credit.principal+=value;saveCredits()};adapter.notifyItemInserted(0);recycler.scrollToPosition(0);editAmount.text?.clear();saveTransactions();updateUI();checkBankruptcyWarning()}
    private fun undoLast(){if(transactions.isEmpty()){Toast.makeText(this,"Нечего отменять",Toast.LENGTH_SHORT).show();return};val last=transactions.removeAt(0);reversePrincipalEffect(last);adapter.notifyItemRemoved(0);saveTransactions();saveCredits();updateUI()}
    private fun deleteTransaction(tx:Transaction){AlertDialog.Builder(this).setTitle("Удалить операцию?").setMessage("Сумма и тело кредита будут пересчитаны").setPositiveButton("Удалить"){_,_->val index=transactions.indexOfFirst{it.id==tx.id};if(index>=0){val removed=transactions.removeAt(index);reversePrincipalEffect(removed);adapter.notifyItemRemoved(index);saveTransactions();saveCredits();updateUI()}}.setNegativeButton("Отмена",null).show()}
    private fun reversePrincipalEffect(tx:Transaction){
        // Interest is a separate expense. Removing an interest transaction must
        // never change the credit principal.
        if (tx.note.startsWith("Проценты:")) return
        val credit=tx.creditId?.let{id->credits.find{it.id==id}}?:return
        if(tx.amount>0)credit.principal+=tx.amount else credit.principal=(credit.principal+tx.amount).coerceAtLeast(0.0)
    }
    private fun getBalance():Double=transactions.sumOf{it.amount}
    private fun updateUI(){val balance=getBalance();val absStr=moneyFormat.format(abs(balance));if(balance>=0){labelBalance.text=getString(R.string.savings);textBalance.text="$absStr ₽";textBalance.setTextColor(getColor(R.color.savings_green))}else{labelBalance.text=getString(R.string.debt);textBalance.text="$absStr ₽";textBalance.setTextColor(getColor(R.color.debt_red))};if(credits.isNotEmpty()){textCreditInfo.visibility=View.VISIBLE;val total=credits.sumOf{it.principal};val daily=credits.sumOf{c->if(c.principal>0&&c.rate>0)c.principal*c.rate/100.0/365.0 else 0.0};val lines=credits.joinToString("\n"){c->val d=if(c.principal>0&&c.rate>0)c.principal*c.rate/100.0/365.0 else 0.0;"${c.name}: ${moneyFormat.format(c.principal)} ₽ · ${c.rate}% · ≈${moneyFormat.format(d)} ₽/день"};textCreditInfo.text="Кредиты\nТело долга: ${moneyFormat.format(total)} ₽\nПроценты: ≈${moneyFormat.format(daily)} ₽/день · ≈${moneyFormat.format(daily*30)} ₽/30 дней · ≈${moneyFormat.format(daily*365)} ₽/год\n$lines"}else textCreditInfo.visibility=View.GONE;emptyJournal.visibility=if(transactions.isEmpty())View.VISIBLE else View.GONE;recycler.visibility=if(transactions.isEmpty())View.GONE else View.VISIBLE;warningBanner.visibility=if(balance<=-500000.0)View.VISIBLE else View.GONE}
    private fun checkBankruptcyWarning(){if(getBalance()<=-500000.0)AlertDialog.Builder(this).setTitle("⚠ Риск банкротства").setMessage(getString(R.string.bankruptcy_warning)).setPositiveButton("Понятно",null).show()}
    private fun applyDailyInterestIfNeeded(){val today=startOfDay(System.currentTimeMillis());var changed=false;for(credit in credits){if(credit.lastInterestDate==0L){credit.lastInterestDate=today;changed=true;continue};val days=TimeUnit.MILLISECONDS.toDays(today-credit.lastInterestDate).toInt();if(days<=0)continue;if(credit.principal>0&&credit.rate>0){val interest=credit.principal*credit.rate/100.0/365.0*days;if(interest>0){transactions.add(0,Transaction(UUID.randomUUID().toString(),-interest,today,"Проценты: ${credit.name} за $days дн.",credit.id));changed=true}};credit.lastInterestDate=today;changed=true};if(changed){saveTransactions();saveCredits();adapter.notifyDataSetChanged()}}
    private fun startOfDay(millis:Long):Long{val cal=Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"));cal.timeInMillis=millis;cal.set(Calendar.HOUR_OF_DAY,0);cal.set(Calendar.MINUTE,0);cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0);return cal.timeInMillis}
    private fun saveTransactions(){val arr=JSONArray();transactions.forEach{tx->JSONObject().apply{put("id",tx.id);put("amount",tx.amount);put("timestamp",tx.timestamp);put("note",tx.note);if(tx.creditId!=null)put("creditId",tx.creditId);arr.put(this)}};prefs.edit().putString("transactions",arr.toString()).apply()}
    private fun loadTransactions(){transactions.clear();val json=prefs.getString("transactions","[]")?:"[]";try{val arr=JSONArray(json);for(i in 0 until arr.length()){val o=arr.getJSONObject(i);transactions.add(Transaction(o.getString("id"),o.getDouble("amount"),o.getLong("timestamp"),o.optString("note",""),if(o.has("creditId"))o.getString("creditId")else null))}}catch(e:Exception){e.printStackTrace()}}
    private fun saveCredits(){val arr=JSONArray();credits.forEach{c->JSONObject().apply{put("id",c.id);put("name",c.name);put("rate",c.rate);put("termMonths",c.termMonths);put("monthlyPayment",c.monthlyPayment);put("principal",c.principal);put("lastInterestDate",c.lastInterestDate);arr.put(this)}};prefs.edit().putString("credits",arr.toString()).apply()}
    private fun loadCredits(){credits.clear();val json=prefs.getString("credits","[]")?:"[]";try{val arr=JSONArray(json);for(i in 0 until arr.length()){val o=arr.getJSONObject(i);credits.add(Credit(o.getString("id"),o.getString("name"),o.getDouble("rate"),o.getInt("termMonths"),o.getDouble("monthlyPayment"),o.getDouble("principal"),o.optLong("lastInterestDate",0L)))}}catch(e:Exception){e.printStackTrace()};adapter.notifyDataSetChanged()}
    private fun loadAll(){loadTransactions();loadCredits();adapter.notifyDataSetChanged()}
}
