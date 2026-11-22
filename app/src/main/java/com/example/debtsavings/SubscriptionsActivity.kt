package com.example.debtsavings

import android.app.DatePickerDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.util.concurrent.TimeUnit

class SubListAdapter(
    private val items: MutableList<Subscription>,
    private val onEdit: (Subscription) -> Unit,
    private val onDelete: (Subscription) -> Unit
) : RecyclerView.Adapter<SubListAdapter.VH>() {
    private val money = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale("ru", "RU"))

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.subName)
        val details: TextView = v.findViewById(R.id.subDetails)
        val intentLabel: TextView = v.findViewById(R.id.subIntent)
        val notes: TextView = v.findViewById(R.id.subNotes)
        val btnEdit: ImageButton = v.findViewById(R.id.btnEditSub)
        val btnDelete: ImageButton = v.findViewById(R.id.btnDeleteSub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_subscription, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.name.text = s.name
        val periodLabel = when (s.period) {
            BillingPeriod.MONTHLY -> "ежемесячно"
            BillingPeriod.YEARLY -> "ежегодно"
            BillingPeriod.CUSTOM_DAYS -> "каждые ${s.customDays} дн."
        }
        val dateStr = if (s.nextChargeDate > 0) dateFmt.format(Date(s.nextChargeDate)) else "—"
        val today = DailyInterest.startOfMoscowDay(System.currentTimeMillis())
        val daysLeft = if (s.nextChargeDate > 0)
            TimeUnit.MILLISECONDS.toDays(DailyInterest.startOfMoscowDay(s.nextChargeDate) - today).toInt()
        else null
        val leftStr = daysLeft?.let { if (it >= 0) " · через $it дн." else " · просрочено" } ?: ""
        holder.details.text =
            "${SubscriptionPresets.label(s.category)}\n" +
                "${money.format(s.price)} ₽ · $periodLabel · $dateStr$leftStr" +
                if (!s.enabled) " · выкл." else ""
        when (s.intent) {
            SubscriptionIntent.NEED_PAY -> {
                holder.intentLabel.text = "Надо оплатить"
                holder.intentLabel.setTextColor(holder.itemView.context.getColor(R.color.accent))
            }
            SubscriptionIntent.NEED_CANCEL -> {
                holder.intentLabel.text =
                    if (s.category == SubscriptionCategory.DEBT_INSURANCE)
                        "Надо отключить до списания"
                    else "Надо отключить"
                holder.intentLabel.setTextColor(holder.itemView.context.getColor(R.color.warning_orange))
            }
        }
        if (s.notes.isNotBlank()) {
            holder.notes.visibility = View.VISIBLE
            holder.notes.text = s.notes
        } else {
            holder.notes.visibility = View.GONE
        }
        holder.btnEdit.setOnClickListener { onEdit(s) }
        holder.btnDelete.setOnClickListener { onDelete(s) }
    }

    override fun getItemCount() = items.size
}

class SubscriptionsActivity : AppCompatActivity() {
    private val items = mutableListOf<Subscription>()
    private lateinit var adapter: SubListAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private val prefs by lazy { getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE) }
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale("ru", "RU"))
    private val categories = SubscriptionCategory.values().toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscriptions)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        recycler = findViewById(R.id.recyclerSubs)
        empty = findViewById(R.id.emptySubs)
        empty.text =
            "Нет подписок.\nНажмите + или выберите шаблон.\n\n" +
                "Категории:\n" +
                "• Уведомления по карте — SMS/push банка\n" +
                "• Страховка по долгу — часто надо отключить\n" +
                "• Увеличенные лимиты — буст лимита в банке\n" +
                "• Сторонние сервисы — стриминг, VPN, облако"
        adapter = SubListAdapter(items, { showDialog(it) }, { confirmDelete(it) })
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<FloatingActionButton>(R.id.fabAddSub).setOnClickListener { showDialog(null) }
        requestNotifPermission()
        SubscriptionNotifier.ensureChannel(this)
        load()
        SubscriptionNotifier.checkAndNotify(this)
        SubscriptionAlarmScheduler.scheduleNext(this)
        PaymentReminderScheduler.scheduleNext(this)
        updateEmpty()
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!ok) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 991
                )
            }
        }
    }

    private fun showPresetPicker(fill: (SubscriptionPreset) -> Unit) {
        val presets = SubscriptionPresets.all()
        val labels = presets.map {
            "[${SubscriptionPresets.label(it.category)}] ${it.name} · ${it.price.toInt()} ₽"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Шаблоны подписок")
            .setItems(labels) { _, which -> fill(presets[which]) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDialog(existing: Subscription?) {
        val view = layoutInflater.inflate(R.layout.dialog_subscription, null)
        val btnPresets = view.findViewById<MaterialButton>(R.id.btnSubPresets)
        val spinnerCat = view.findViewById<Spinner>(R.id.spinnerCategory)
        val helpCat = view.findViewById<TextView>(R.id.textCategoryHelp)
        val editName = view.findViewById<EditText>(R.id.editSubName)
        val editPrice = view.findViewById<EditText>(R.id.editSubPrice)
        val spinner = view.findViewById<Spinner>(R.id.spinnerPeriod)
        val layoutCustom = view.findViewById<View>(R.id.layoutCustomDays)
        val editCustom = view.findViewById<EditText>(R.id.editCustomDays)
        val editNext = view.findViewById<EditText>(R.id.editNextCharge)
        val radioIntent = view.findViewById<RadioGroup>(R.id.radioIntent)
        val radioPay = view.findViewById<RadioButton>(R.id.radioNeedPay)
        val radioCancel = view.findViewById<RadioButton>(R.id.radioNeedCancel)
        val editNotify = view.findViewById<EditText>(R.id.editNotifyDays)
        val editNotes = view.findViewById<EditText>(R.id.editSubNotes)
        val switchEn = view.findViewById<MaterialSwitch>(R.id.switchSubEnabled)

        val catLabels = categories.map { SubscriptionPresets.label(it) }
        spinnerCat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, catLabels)
        spinnerCat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val cat = categories[pos]
                helpCat.text = SubscriptionPresets.help(cat)
                // Страховка по умолчанию — «надо отключить»
                if (existing == null && cat == SubscriptionCategory.DEBT_INSURANCE) {
                    radioCancel.isChecked = true
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val periods = listOf("Ежемесячно", "Ежегодно", "Свой (дни)")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, periods)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                layoutCustom.visibility = if (pos == 2) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        var nextMillis = existing?.nextChargeDate
            ?: (DailyInterest.startOfMoscowDay(System.currentTimeMillis()) + 30L * 24 * 60 * 60 * 1000)

        fun applyPreset(pr: SubscriptionPreset) {
            editName.setText(pr.name)
            editPrice.setText(pr.price.toString())
            spinnerCat.setSelection(categories.indexOf(pr.category).coerceAtLeast(0))
            helpCat.text = SubscriptionPresets.help(pr.category)
            spinner.setSelection(
                when (pr.period) {
                    BillingPeriod.MONTHLY -> 0
                    BillingPeriod.YEARLY -> 1
                    BillingPeriod.CUSTOM_DAYS -> 2
                }
            )
            if (pr.intent == SubscriptionIntent.NEED_CANCEL) radioCancel.isChecked = true
            else radioPay.isChecked = true
            if (pr.notes.isNotBlank()) editNotes.setText(pr.notes)
            Toast.makeText(this, "Шаблон подставлен — проверьте цену и дату", Toast.LENGTH_SHORT).show()
        }

        btnPresets.setOnClickListener { showPresetPicker { applyPreset(it) } }

        if (existing != null) {
            editName.setText(existing.name)
            editPrice.setText(existing.price.toString())
            spinnerCat.setSelection(categories.indexOf(existing.category).coerceAtLeast(0))
            helpCat.text = SubscriptionPresets.help(existing.category)
            spinner.setSelection(
                when (existing.period) {
                    BillingPeriod.MONTHLY -> 0
                    BillingPeriod.YEARLY -> 1
                    BillingPeriod.CUSTOM_DAYS -> 2
                }
            )
            editCustom.setText(existing.customDays.toString())
            nextMillis = existing.nextChargeDate
            if (existing.intent == SubscriptionIntent.NEED_CANCEL) radioCancel.isChecked = true
            else radioPay.isChecked = true
            editNotify.setText(existing.notifyDays)
            editNotes.setText(existing.notes)
            switchEn.isChecked = existing.enabled
        } else {
            spinnerCat.setSelection(categories.indexOf(SubscriptionCategory.THIRD_PARTY))
            helpCat.text = SubscriptionPresets.help(SubscriptionCategory.THIRD_PARTY)
        }
        editNext.setText(if (nextMillis > 0) dateFmt.format(Date(nextMillis)) else "")

        editNext.setOnClickListener {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
            if (nextMillis > 0) cal.timeInMillis = nextMillis
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal.set(y, m, d, 0, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    nextMillis = cal.timeInMillis
                    editNext.setText(dateFmt.format(Date(nextMillis)))
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Новая подписка" else "Редактировать подписку")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = editName.text.toString().trim().ifEmpty { "Подписка" }
                val price = editPrice.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val cat = categories[spinnerCat.selectedItemPosition.coerceIn(0, categories.lastIndex)]
                val period = when (spinner.selectedItemPosition) {
                    1 -> BillingPeriod.YEARLY
                    2 -> BillingPeriod.CUSTOM_DAYS
                    else -> BillingPeriod.MONTHLY
                }
                val custom = editCustom.text.toString().toIntOrNull() ?: 30
                val intentMode = if (radioIntent.checkedRadioButtonId == R.id.radioNeedCancel)
                    SubscriptionIntent.NEED_CANCEL else SubscriptionIntent.NEED_PAY
                val notify = editNotify.text.toString().trim().ifEmpty { "7,3,1" }
                val notes = editNotes.text.toString().trim()
                if (existing == null) {
                    items.add(
                        0,
                        Subscription(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            price = price,
                            period = period,
                            customDays = custom,
                            nextChargeDate = nextMillis,
                            notes = notes,
                            intent = intentMode,
                            notifyDays = notify,
                            enabled = switchEn.isChecked,
                            category = cat
                        )
                    )
                    adapter.notifyItemInserted(0)
                } else {
                    existing.name = name
                    existing.price = price
                    existing.period = period
                    existing.customDays = custom
                    existing.nextChargeDate = nextMillis
                    existing.notes = notes
                    existing.intent = intentMode
                    existing.notifyDays = notify
                    existing.enabled = switchEn.isChecked
                    existing.category = cat
                    val idx = items.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) adapter.notifyItemChanged(idx)
                }
                save()
                updateEmpty()
                SubscriptionAlarmScheduler.scheduleNext(this)
        PaymentReminderScheduler.scheduleNext(this)
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDelete(s: Subscription) {
        AlertDialog.Builder(this)
            .setTitle("Удалить «${s.name}»?")
            .setPositiveButton("Удалить") { _, _ ->
                val idx = items.indexOfFirst { it.id == s.id }
                if (idx >= 0) {
                    items.removeAt(idx)
                    adapter.notifyItemRemoved(idx)
                    save()
                    updateEmpty()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun load() {
        Persistence.loadSubscriptions(prefs, items)
        adapter.notifyDataSetChanged()
    }

    private fun save() = Persistence.saveSubscriptions(prefs, items)

    private fun updateEmpty() {
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }
}
