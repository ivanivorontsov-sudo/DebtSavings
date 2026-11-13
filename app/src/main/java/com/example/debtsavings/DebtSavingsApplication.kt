package com.example.debtsavings

import android.app.Application
import android.content.SharedPreferences

class DebtSavingsApplication : Application() {
    private val syncListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) GitHubSyncScheduler.scheduleDebounced(this)
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(Persistence.PREFS_NAME, MODE_PRIVATE)
        val transactions = mutableListOf<Transaction>()
        val credits = mutableListOf<Credit>()
        Persistence.loadTransactions(prefs, transactions)
        Persistence.loadCredits(prefs, credits)
        DailyInterest.applyIfNeeded(prefs, transactions, credits)
        prefs.registerOnSharedPreferenceChangeListener(syncListener)

        GitHubSyncScheduler.ensurePeriodic(this)
        InterestAlarmScheduler.scheduleNext(this)
        SubscriptionNotifier.ensureChannel(this)
        PaymentReminders.ensureChannel(this)
        SubscriptionNotifier.checkAndNotify(this)
        PaymentReminders.checkAndNotify(this)
        SubscriptionAlarmScheduler.scheduleNext(this)
        PaymentReminderScheduler.scheduleNext(this)
    }
}
