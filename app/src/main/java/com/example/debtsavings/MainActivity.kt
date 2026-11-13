package com.example.debtsavings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
    private lateinit var journalScrollHint: TextView
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

    private lateinit var gameScenarioCard: View
    private lateinit var gameLevelText: TextView
    private lateinit var gameLevelProgress: ProgressBar
    private lateinit var gameProgressText: TextView
    private lateinit var gameMissionText: TextView
    private lateinit var gameBadgesText: TextView
    private lateinit var gameRulesText: TextView

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableNotifications()
        } else {
            prefs.edit().putBoolean(NotificationPreferences.PREF_ENABLED, false).apply()
            Toast.makeText(this, R.string.notifications_denied, Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.notifications_blocked_title)
                    .setMessage(R.string.notifications_blocked_message)
                    .setPositiveButton(R.string.notifications_open_settings) { _, _ ->
                        openNotificationSettings()
                    }
                    .setNegativeButton(R.string.notifications_later, null)
                    .show()
            }
        }
        invalidateOptionsMenu()
    }

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
                    defaultsSeeded = prefs.getBoolean(Persistence.DEFAULTS_SEEDED_KEY, false),
                    savingsGoalTarget = prefs.getFloat("savings_goal_target", 0f),
                    savingsMonthlyContribution = prefs.getFloat("savings_monthly_contribution", 0f),
                    monthlyEssentialExpenses = prefs.getFloat("monthly_essential_expenses", 0f),
                    emergencyMonths = prefs.getInt("emergency_months", 3),
                    debtPlanExtra = prefs.getFloat("debt_plan_extra", 0f),
                    gameModeEnabled = prefs.getBoolean(GameScenario.PREF_ENABLED, false),
                    notificationsEnabled = prefs.getBoolean(NotificationPreferences.PREF_ENABLED, true)
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
                        PaymentReminderScheduler.scheduleNext(this)
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
        gameScenarioCard = findViewById(R.id.gameScenarioCard)
        gameLevelText = findViewById(R.id.gameLevelText)
        gameLevelProgress = findViewById(R.id.gameLevelProgress)
        gameProgressText = findViewById(R.id.gameProgressText)
        gameMissionText = findViewById(R.id.gameMissionText)
        gameBadgesText = findViewById(R.id.gameBadgesText)
        gameRulesText = findViewById(R.id.gameRulesText)
        recycler = findViewById(R.id.recyclerJournal)
        emptyJournal = findViewById(R.id.emptyJournal)
        journalScrollHint = findViewById(R.id.journalScrollHint)
        btnUndo = findViewById(R.id.btnUndo)
        btnJournalFull = findViewById(R.id.btnJournalFull)
        adapter = TransactionAdapter(previewTx, credits) { deleteTransaction(it) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.isNestedScrollingEnabled = false
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
        applyDailyInterestIfNeeded()
        setupCreditSpinner()
        updateUI()
        InterestAlarmScheduler.scheduleNext(this)
        SubscriptionAlarmScheduler.scheduleNext(this)
        PaymentReminderScheduler.scheduleNext(this)
        SubscriptionNotifier.checkAndNotify(this)
        PaymentReminders.checkAndNotify(this)
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
        loadTransactions()
        loadCredits()
        applyDailyInterestIfNeeded()
        setupCreditSpinner()
        adapter.notifyDataSetChanged()
        updateUI()
        InterestAlarmScheduler.scheduleNext(this)
        SubscriptionAlarmScheduler.scheduleNext(this)
        PaymentReminderScheduler.scheduleNext(this)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu?): Boolean {
        menu?.findItem(R.id.menu_game_mode)?.isChecked =
            prefs.getBoolean(GameScenario.PREF_ENABLED, false)
        menu?.findItem(R.id.menu_notifications)?.isChecked =
            NotificationPreferences.isEnabled(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_game_mode -> {
                toggleGameMode()
                return true
            }
            R.id.menu_notifications -> {
                toggleNotifications()
                return true
            }
            R.id.menu_debt_plan -> {
                startActivity(Intent(this, DebtPlanActivity::class.java)); return true
            }
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
            R.id.menu_github_sync -> {
                showGitHubSyncDialog(); return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showGitHubSyncDialog() {
        if (!GitHubSyncSettings.isConfigured(this)) {
            showGitHubConnectDialog()
            return
        }

        val options = arrayOf(
            "Синхронизировать сейчас",
            "Загрузить данные телефона в GitHub",
            "Восстановить данные из GitHub",
            "Изменить токен / пароль",
            "Отключить GitHub"
        )
        AlertDialog.Builder(this)
            .setTitle("GitHub · ${GitHubSyncSettings.repoName(this)}")
            .setMessage(
                GitHubSyncSettings.status(this) + "\n\n" +
                    "Автосинхронизация включается после изменений и дополнительно запускается раз в час.\n" +
                    "Облачная копия зашифрована AES-256-GCM. Пароль синхронизации нужен на каждом устройстве."
            )
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        GitHubSyncSettings.setStatus(this, "Синхронизация поставлена в очередь…")
                        GitHubSyncScheduler.enqueueNow(this, "auto")
                        Toast.makeText(this, "Синхронизация запущена", Toast.LENGTH_SHORT).show()
                    }
                    1 -> AlertDialog.Builder(this)
                        .setTitle("Загрузить данные в GitHub?")
                        .setMessage("Текущая зашифрованная копия в GitHub будет сохранена в папке conflicts перед заменой. Продолжить?")
                        .setPositiveButton("Загрузить") { _, _ ->
                            GitHubSyncSettings.setStatus(this, "Загрузка данных в GitHub…")
                            GitHubSyncScheduler.enqueueNow(this, "upload")
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Восстановить из GitHub?")
                        .setMessage("Текущие данные на этом телефоне будут сохранены в локальную зашифрованную копию перед восстановлением. Продолжить?")
                        .setPositiveButton("Восстановить") { _, _ ->
                            GitHubSyncSettings.setStatus(this, "Восстановление из GitHub…")
                            GitHubSyncScheduler.enqueueNow(this, "download")
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                    3 -> showGitHubConnectDialog()
                    4 -> AlertDialog.Builder(this)
                        .setTitle("Отключить GitHub?")
                        .setMessage("Локальные данные не удалятся. Облачная копия в GitHub также останется.")
                        .setPositiveButton("Отключить") { _, _ ->
                            GitHubSyncSettings.disconnect(this)
                            Toast.makeText(this, "GitHub отключён. Данные на телефоне сохранены.", Toast.LENGTH_LONG).show()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showGitHubConnectDialog() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }

        val info = TextView(this).apply {
            text = "Создайте GitHub Personal Access Token. Для Fine-grained token нужны Contents: Read and write; для автоматического создания приватного репозитория дополнительно Administration: Read and write и доступ ко всем репозиториям. Можно заранее создать приватный репозиторий и выдать токену доступ только к нему.\n\nПароль синхронизации придумайте сами (не менее 8 символов) и введите такой же на каждом телефоне. Он не отправляется в GitHub."
            textSize = 14f
        }
        val tokenInput = EditText(this).apply {
            hint = "GitHub token"
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val repoInput = EditText(this).apply {
            hint = "Имя приватного репозитория"
            setText(GitHubSyncSettings.repoName(this@MainActivity))
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val passwordInput = EditText(this).apply {
            hint = "Пароль синхронизации (от 8 символов)"
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        form.addView(info)
        form.addView(tokenInput)
        form.addView(repoInput)
        form.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle("Подключить GitHub")
            .setView(form)
            .setPositiveButton("Подключить", null)
            .setNeutralButton("Создать токен") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new")))
                } catch (_: Exception) {
                    Toast.makeText(this, "Не удалось открыть GitHub", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val token = tokenInput.text?.toString().orEmpty().trim()
                        val repo = repoInput.text?.toString().orEmpty().trim()
                        val password = passwordInput.text?.toString().orEmpty()
                        try {
                            GitHubSyncSettings.save(this, token, repo, password)
                            GitHubSyncScheduler.ensurePeriodic(this)
                            GitHubSyncSettings.setStatus(this, "Проверяем токен и подключаем приватный репозиторий…")
                            GitHubSyncScheduler.enqueueNow(this, "auto")
                            Toast.makeText(this, "Настройки сохранены. Запущена первая синхронизация.", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        } catch (error: Exception) {
                            passwordInput.error = error.message ?: "Проверьте введённые данные"
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun toggleGameMode() {
        val enabled = !prefs.getBoolean(GameScenario.PREF_ENABLED, false)
        prefs.edit().putBoolean(GameScenario.PREF_ENABLED, enabled).apply()
        invalidateOptionsMenu()
        renderGameScenario()
        if (enabled && !NotificationPreferences.isEnabled(this)) {
            showNotificationsRationale()
        }
    }

    private fun toggleNotifications() {
        if (NotificationPreferences.isEnabled(this)) {
            prefs.edit().putBoolean(NotificationPreferences.PREF_ENABLED, false).apply()
            PaymentReminderScheduler.cancel(this)
            SubscriptionAlarmScheduler.cancel(this)
            NotificationManagerCompat.from(this).cancelAll()
            Toast.makeText(this, R.string.notifications_disabled, Toast.LENGTH_SHORT).show()
            invalidateOptionsMenu()
        } else {
            showNotificationsRationale()
        }
    }

    private fun showNotificationsRationale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notifications_title)
            .setMessage(R.string.notifications_rationale)
            .setPositiveButton(R.string.notifications_allow) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    prefs.edit().putBoolean(NotificationPreferences.PREF_ENABLED, true).apply()
                    if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                        enableNotifications()
                    } else {
                        openNotificationSettings()
                    }
                }
            }
            .setNegativeButton(R.string.notifications_later) { _, _ ->
                prefs.edit().putBoolean(NotificationPreferences.PREF_ENABLED, false).apply()
                invalidateOptionsMenu()
            }
            .show()
    }

    private fun enableNotifications() {
        if (!NotificationPreferences.hasRuntimePermission(this)) {
            showNotificationsRationale()
            return
        }
        prefs.edit().putBoolean(NotificationPreferences.PREF_ENABLED, true).apply()
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            openNotificationSettings()
            invalidateOptionsMenu()
            return
        }
        PaymentReminderScheduler.scheduleNext(this)
        SubscriptionAlarmScheduler.scheduleNext(this)
        Toast.makeText(this, R.string.notifications_enabled, Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu()
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.notifications_settings_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderGameScenario() {
        if (!prefs.getBoolean(GameScenario.PREF_ENABLED, false)) {
            gameScenarioCard.visibility = View.GONE
            return
        }

        val progress = GameScenario.calculate(transactions)
        gameScenarioCard.visibility = View.VISIBLE
        gameLevelText.text = getString(
            R.string.game_level_format,
            progress.levelNumber,
            progress.levelTitle,
            progress.xp
        )
        gameLevelProgress.progress = progress.levelProgressPercent
        gameProgressText.text = if (progress.nextLevelTitle != null) {
            getString(R.string.game_next_level_format, progress.nextLevelTitle, progress.xpToNextLevel)
        } else {
            getString(R.string.game_max_level)
        }
        gameMissionText.text = if (progress.weeklyMissionDone) {
            getString(R.string.game_week_mission_done)
        } else {
            getString(R.string.game_week_mission_pending)
        }
        gameBadgesText.text = if (progress.earnedBadges.isEmpty()) {
            getString(R.string.game_badges_empty)
        } else {
            getString(R.string.game_badges_format, progress.earnedBadges.joinToString(" · "))
        }
        gameRulesText.text = getString(R.string.game_rules)
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
            // Аннуитет (дата окончания): не в плановый день → весь платёж в тело
            val now = System.currentTimeMillis()
            val res = Annuity.applyPayment(credit, value, null, now) { moneyFormat.format(it) }
            transactions.add(0, Transaction(UUID.randomUUID().toString(), res.breakdown.total, now, res.note, creditId))
            saveCredits()
        } else if (credit != null && !positive) {
            credit.principal += value
            Annuity.recompute(credit)
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
    private fun reverseEffect(tx: Transaction) = Annuity.reverseEffect(credits, tx)

    // Общий баланс операций учитывает только свободные средства; кредитные проводки не смешиваются с накоплениями.
    private fun getBalance(): Double = transactions.filter { it.creditId == null }.sumOf { it.amount }

    private fun totalDebtBalance(): Double = credits.sumOf { it.principal + it.accruedInterest + it.accruedFees }

    private fun updateUI() {
        val balance = getBalance()
        val absStr = moneyFormat.format(abs(balance))
        if (balance >= 0) {
            labelBalance.text = getString(R.string.savings)
            textBalance.text = "$absStr ₽"
            textBalance.setTextColor(getColor(R.color.savings_green))
        } else {
            labelBalance.text = "Дефицит свободных средств"
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
        val journalEmpty = transactions.isEmpty()
        emptyJournal.visibility = if (journalEmpty) View.VISIBLE else View.GONE
        journalScrollHint.visibility = if (journalEmpty) View.VISIBLE else View.GONE
        recycler.visibility = if (journalEmpty) View.GONE else View.VISIBLE
        warningBanner.visibility = if (totalDebtBalance() >= 500000.0) View.VISIBLE else View.GONE
        renderGameScenario()
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
        if (totalDebtBalance() >= 500000.0) {
            AlertDialog.Builder(this)
                .setTitle("⚠ Большая долговая нагрузка")
                .setMessage("Суммарный остаток по кредитам, процентам и комиссиям превысил 500 000 ₽. Проверьте обязательные платежи и при необходимости свяжитесь с кредиторами.")
                .setPositiveButton("Понятно", null)
                .show()
        }
    }

    private fun applyDailyInterestIfNeeded() {
        DailyInterest.applyIfNeeded(prefs, transactions, credits)
    }

    private fun saveTransactions() = Persistence.saveTransactions(prefs, transactions)
    private fun loadTransactions() = Persistence.loadTransactions(prefs, transactions)
    private fun saveCredits() {
        Persistence.saveCredits(prefs, credits)
        PaymentReminderScheduler.scheduleNext(this)
    }
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
