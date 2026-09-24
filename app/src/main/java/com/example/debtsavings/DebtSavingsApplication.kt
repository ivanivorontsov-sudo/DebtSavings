package com.example.debtsavings

import android.app.Application

class DebtSavingsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(Persistence.PREFS_NAME, MODE_PRIVATE)
        val transactions = mutableListOf<Transaction>()
        val credits = mutableListOf<Credit>()
        Persistence.loadTransactions(prefs, transactions)
        Persistence.loadCredits(prefs, credits)
        Persistence.ensureDefaultSeed(prefs, credits, transactions)
        DailyInterest.applyIfNeeded(prefs, transactions, credits)
        InterestAlarmScheduler.scheduleNext(this)
        SubscriptionNotifier.ensureChannel(this)
        SubscriptionNotifier.checkAndNotify(this)
        SubscriptionAlarmScheduler.scheduleNext(this)
    }
}
